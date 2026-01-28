package com.babysleepmonitor.logic

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Port of Python VideoCamera motion detection logic.
 */
class MotionDetector {
    private val TAG = "MotionDetector"
    private var lastFrame: Mat? = null
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    
    // Text Recognizer for avoiding timestamp/OSD false positives
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Normalized ROI (0.0 - 1.0)
    var roi: Rect2d? = null
    
    data class Result(
        val motionScore: Double,
        val boxes: List<android.graphics.Rect>,
        val width: Int,
        val height: Int
    )

    init {
        try {
            if (OpenCVLoader.initDebug()) {
                Log.i(TAG, "OpenCV loaded successfully")
            } else {
                Log.e(TAG, "OpenCV initialization failed!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading OpenCV: ${e.message}")
        }
    }

    fun processFrame(bitmap: Bitmap): Result {
        val width = bitmap.width
        val height = bitmap.height
        
        val currentFrame = Mat()
        Utils.bitmapToMat(bitmap, currentFrame)
        
        // 1. Grayscale
        val gray = Mat()
        // Handle channel config (Bitmap is usually RGBA or ARGB)
        Imgproc.cvtColor(currentFrame, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // 2. CLAHE
        clahe.apply(gray, gray)
        
        // 3. Blur
        Imgproc.GaussianBlur(gray, gray, Size(21.0, 21.0), 0.0)
        
        if (lastFrame == null) {
            lastFrame = gray
            currentFrame.release()
            return Result(0.0, emptyList(), width, height)
        }
        
        // 4. AbsDiff
        val diff = Mat()
        Core.absdiff(lastFrame!!, gray, diff)
        
        // 5. Threshold (5, 255)
        val thresh = Mat()
        Imgproc.threshold(diff, thresh, 5.0, 255.0, Imgproc.THRESH_BINARY)
        
        // 6. Dilate
        Imgproc.dilate(thresh, thresh, Mat(), Point(-1.0, -1.0), 2)

        // 7. Object Detection Masking
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        try {
            val task = objectDetector.process(inputImage)
            val detectedObjects = Tasks.await(task)

            val objectMask = Mat.zeros(thresh.size(), thresh.type())

            if (detectedObjects.isNotEmpty()) {
                var foundCount = 0
                for (obj in detectedObjects) {
                    val rect = obj.boundingBox
                    
                    val rectArea = rect.width() * rect.height()
                    val frameArea = width * height
                    val areaPercent = (rectArea.toDouble() / frameArea) * 100
                    
                    // Filter: Ignore objects that are too small (like timestamps)
                    // Increased threshold to 2.0% to filter out small artifacts like timestamps more aggressively.
                    // A baby or person should be significantly larger than 2% of the frame.
                    if (areaPercent < 2.0) {
                        Log.d(TAG, "Ignored object (too small): area=${String.format("%.2f", areaPercent)}%, rect=$rect")
                        continue
                    }

                    // Filter: Ignore objects with extreme aspect ratios (likely text banners/timestamps)
                    val aspectRatio = rect.width().toDouble() / rect.height().toDouble()
                    if (aspectRatio > 5.0 || aspectRatio < 0.2) {
                        Log.d(TAG, "Ignored object (aspect ratio): ratio=${String.format("%.2f", aspectRatio)}, rect=$rect")
                        continue
                    }

                    val x = rect.left.coerceAtLeast(0)
                    val y = rect.top.coerceAtLeast(0)
                    val w = (rect.width()).coerceAtMost(width - x)
                    val h = (rect.height()).coerceAtMost(height - y)
                    Imgproc.rectangle(objectMask, Rect(x, y, w, h), Scalar(255.0), -1)
                    foundCount++
                }
                
                if (foundCount > 0) {
                    // Mask the threshold image: keep only motion inside objects
                    val tempWithObject = Mat()
                    Core.bitwise_and(thresh, objectMask, tempWithObject)
                    tempWithObject.copyTo(thresh)
                    tempWithObject.release()
                } else {
                    // All detected objects were filtered out (likely noise/timestamps)
                    Log.d(TAG, "No valid objects found after filtering.")
                    thresh.setTo(Scalar(0.0))
                }
            } else {
                // No objects -> No meaningful motion
                thresh.setTo(Scalar(0.0))
            }
            objectMask.release()

            // 7.5 Text/Number Masking (Timestamp Filter)
            // Use ML Kit Text Recognition to find timestamps/numbers and black them out in 'thresh'
            try {
                val textTask = textRecognizer.process(inputImage)
                val textResult = Tasks.await(textTask)
                
                for (block in textResult.textBlocks) {
                     val rect = block.boundingBox
                     if (rect != null) {
                         // Inflate the text mask to cover artifacts/compression ghosting around numbers.
                         // Timestamps often have "halos" of changed pixels that ML Kit doesn't include in the tight character box.
                         val margin = 30 // px, generous margin to catch "box next to number"
                         
                         val x = (rect.left - margin).coerceAtLeast(0)
                         val y = (rect.top - margin).coerceAtLeast(0)
                         val w = (rect.width() + (margin * 2)).coerceAtMost(width - x)
                         val h = (rect.height() + (margin * 2)).coerceAtMost(height - y)
                         
                         // Black out the INFLATED text area in the threshold image
                         Imgproc.rectangle(thresh, Rect(x, y, w, h), Scalar(0.0), -1)
                     }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Text detection error: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Object detection error: ${e.message}")
            // Fallback: If detection fails, suppress motion to avoid false positives from timestamp
            thresh.setTo(Scalar(0.0))
        }

        // 8. ROI Masking (renamed from 7)
        
        // 8. ROI Masking
        roi?.let { r ->
            // Convert normalized ROI to pixels
            val x = (r.x * width).toInt()
            val y = (r.y * height).toInt()
            val w = (r.width * width).toInt()
            val h = (r.height * height).toInt()
            
            // Create a mask that is 0 everywhere except ROI
            // Actually simpler: just blackout the thresh Mat outside ROI
            
            // Create a submat for the ROI (this refers to the data in thresh)
            // But we want to KEEP the ROI and ZERO the rest.
            // Easiest way in OpenCV: Create black mask, draw white rect, bitwise AND.
            val mask = Mat.zeros(thresh.size(), thresh.type())
            Imgproc.rectangle(mask, Rect(x, y, w, h), Scalar(255.0), -1)
            
            val temp = Mat()
            Core.bitwise_and(thresh, mask, temp)
            mask.release()
            
            // Replace thresh with masked version
            temp.copyTo(thresh)
            temp.release()
        }
        
        // 9. Motion Score
        // Recalculate motion score based on what's left after filtering
        // Note: We might want to filter small blobs BEFORE calculating the final score
        
        // 10. Contours & Filtering
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        val boxes = ArrayList<android.graphics.Rect>()
        var totalMotionArea = 0.0
        
        // Dynamic threshold: 0.005% of total area (approx 100px on 1080p)
        // Lowered from 0.1% to allow subtle movement detection (breathing).
        // False positives (timestamps) are handled by the Object Detection Mask above.
        val minContourArea = (width * height) * 0.00005 
        
        val filteredThresh = Mat.zeros(thresh.size(), thresh.type())

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea) {
                val r = Imgproc.boundingRect(contour)

                // Basic OSD/Timestamp Filter:
                // Timestamps are typically at the top or bottom edge.
                // If a small motion blob is detected near the vertical edges, ignore it.
                // 10% margin at top/bottom.
                val isAtVerticalEdge = r.y < height * 0.1 || (r.y + r.height) > height * 0.9
                
                // Only filter if the blob is small (e.g., < 1% of screen). 
                // Larger movements at the edge (parent entering) should still trigger.
                val isSmallArtifact = area < (width * height) * 0.01

                if (!isAtVerticalEdge || !isSmallArtifact) {
                    boxes.add(android.graphics.Rect(r.x, r.y, r.x + r.width, r.y + r.height))
                    
                    // Redraw this valid contour onto a new mask to calculate accurate score
                    Imgproc.drawContours(filteredThresh, listOf(contour), -1, Scalar(255.0), -1)
                    totalMotionArea += area
                }
            }
            contour.release()
        }
        
        // Update the motion score based on the Filtered Large Blobs only
        val motionScore = Core.sumElems(filteredThresh).`val`[0]
        filteredThresh.release()
        
        // Cleanup
        lastFrame!!.release()
        lastFrame = gray
        currentFrame.release()
        diff.release()
        thresh.release() // Original thresh
        hierarchy.release()
        
        return Result(motionScore, boxes, width, height)
    }
    
    fun reset() {
        lastFrame?.release()
        lastFrame = null
    }
}
