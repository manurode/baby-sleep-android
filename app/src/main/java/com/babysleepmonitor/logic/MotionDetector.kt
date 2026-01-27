package com.babysleepmonitor.logic

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Port of Python VideoCamera motion detection logic.
 */
class MotionDetector {
    private val TAG = "MotionDetector"
    private var lastFrame: Mat? = null
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    
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
        
        // 7. ROI Masking
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
        
        // 8. Motion Score
        val motionScore = Core.sumElems(thresh).`val`[0]
        
        // 9. Contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        val boxes = ArrayList<android.graphics.Rect>()
        for (contour in contours) {
            if (Imgproc.contourArea(contour) > 100) {
                val r = Imgproc.boundingRect(contour)
                boxes.add(android.graphics.Rect(r.x, r.y, r.x + r.width, r.y + r.height))
            }
            contour.release()
        }
        
        // Cleanup
        lastFrame!!.release()
        lastFrame = gray
        currentFrame.release()
        diff.release()
        thresh.release()
        hierarchy.release()
        
        return Result(motionScore, boxes, width, height)
    }
    
    fun reset() {
        lastFrame?.release()
        lastFrame = null
    }
}
