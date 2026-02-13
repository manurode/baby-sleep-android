package com.babysleepmonitor.logic

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Lightweight motion detector for BACKGROUND SERVICE use only.
 *
 * Uses pure OpenCV frame differencing — NO ML Kit (ObjectDetector, TextRecognizer).
 *
 * Why no ML Kit?
 *   1. ML Kit ObjectDetector in STREAM_MODE is a singleton pipeline.
 *      Two instances (foreground + background) corrupt each other's state,
 *      causing both to return 0 objects → motionScore=0.0 always.
 *   2. ML Kit constantly reinitializes in a background service
 *      ("Pipeline is reset" every ~1s), never producing stable results.
 *   3. ML Kit was added to filter camera timestamp overlays from causing
 *      false positives. In background monitoring, ~3 FPS snapshot polling
 *      with lower resolution (640×480) makes timestamp motion negligible.
 *      Simple contour-area + edge filtering is sufficient.
 *
 * Algorithm:
 *   1. Convert to grayscale
 *   2. CLAHE contrast enhancement
 *   3. Gaussian blur (21×21)
 *   4. Absolute difference with previous frame
 *   5. Binary threshold (5)
 *   6. Dilate (2 iterations)
 *   7. Find contours, filter by:
 *      - Minimum area (0.05% of frame — ignores tiny pixel noise)
 *      - Edge position (ignores small blobs in top/bottom 10% — timestamps)
 *   8. Sum remaining white pixels as motionScore
 */
class SimpleMotionDetector {
    private val TAG = "SimpleMotionDetector"
    private var lastFrame: Mat? = null
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    data class Result(
        val motionScore: Double,
        val width: Int,
        val height: Int
    )

    fun processFrame(bitmap: Bitmap): Result {
        val width = bitmap.width
        val height = bitmap.height

        val currentFrame = Mat()
        Utils.bitmapToMat(bitmap, currentFrame)

        // 1. Grayscale
        val gray = Mat()
        Imgproc.cvtColor(currentFrame, gray, Imgproc.COLOR_RGBA2GRAY)

        // 2. CLAHE
        clahe.apply(gray, gray)

        // 3. Gaussian blur
        Imgproc.GaussianBlur(gray, gray, Size(21.0, 21.0), 0.0)

        if (lastFrame == null) {
            lastFrame = gray
            currentFrame.release()
            return Result(0.0, width, height)
        }

        // 4. Absolute difference
        val diff = Mat()
        Core.absdiff(lastFrame!!, gray, diff)

        // 5. Binary threshold
        val thresh = Mat()
        Imgproc.threshold(diff, thresh, 5.0, 255.0, Imgproc.THRESH_BINARY)

        // 6. Dilate
        Imgproc.dilate(thresh, thresh, Mat(), Point(-1.0, -1.0), 2)

        // 7. Find contours and filter
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val filteredMask = Mat.zeros(thresh.size(), thresh.type())
        val minContourArea = (width * height) * 0.0005  // 0.05% of frame

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea) {
                val r = Imgproc.boundingRect(contour)

                // Filter: ignore small blobs at top/bottom 10% (camera timestamps)
                val isAtEdge = r.y < height * 0.10 || (r.y + r.height) > height * 0.90
                val isSmall = area < (width * height) * 0.01

                if (!isAtEdge || !isSmall) {
                    Imgproc.drawContours(filteredMask, listOf(contour), -1, Scalar(255.0), -1)
                }
            }
            contour.release()
        }

        val motionScore = Core.sumElems(filteredMask).`val`[0]
        filteredMask.release()

        // Update last frame
        lastFrame!!.release()
        lastFrame = gray
        currentFrame.release()
        diff.release()
        thresh.release()
        hierarchy.release()

        return Result(motionScore, width, height)
    }

    fun reset() {
        lastFrame?.release()
        lastFrame = null
    }
}
