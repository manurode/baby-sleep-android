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
 *
 * Timestamp/OSD is NOT present in background frames:
 *   LibVLC connects with --no-spu (no Subtitle Processing Unit), which suppresses
 *   OSD/timestamp overlays that cameras send as separate subtitle/text tracks.
 *   Confirmed via debug image capture: background RTSP frames contain clean video
 *   with no timestamp overlay. Therefore no timestamp filtering is needed.
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
 *   8. Sum remaining white pixels as motionScore
 */
class SimpleMotionDetector {
    private val TAG = "SimpleMotionDetector"
    private var lastFrame: Mat? = null
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    private var totalFramesProcessed = 0L

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
        currentFrame.release()

        // 2. CLAHE
        clahe.apply(gray, gray)

        // 3. Gaussian blur
        Imgproc.GaussianBlur(gray, gray, Size(21.0, 21.0), 0.0)

        if (lastFrame == null) {
            lastFrame = gray
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

        // 7. Compute motion score with contour filtering
        val motionScore = computeMotionScore(thresh, width, height)

        // Update last frame
        lastFrame!!.release()
        lastFrame = gray
        diff.release()
        thresh.release()

        totalFramesProcessed++
        return Result(motionScore, width, height)
    }

    /**
     * Compute the motion score from the thresholded diff image.
     * Filters contours by minimum area to ignore pixel noise.
     */
    private fun computeMotionScore(thresh: Mat, width: Int, height: Int): Double {
        // Find contours and filter
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val filteredMask = Mat.zeros(thresh.size(), thresh.type())
        val minContourArea = (width * height) * 0.0005  // 0.05% of frame

        var totalContours = contours.size
        var tooSmall = 0
        var accepted = 0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea) {
                Imgproc.drawContours(filteredMask, listOf(contour), -1, Scalar(255.0), -1)
                accepted++
            } else {
                tooSmall++
            }
            contour.release()
        }

        val motionScore = Core.sumElems(filteredMask).`val`[0]
        filteredMask.release()
        hierarchy.release()

        // Periodic detailed log every 50 frames
        if (totalFramesProcessed % 50 == 0L) {
            Log.i(TAG, "Filters: contours=$totalContours → tooSmall=$tooSmall, " +
                "accepted=$accepted → score=${motionScore.toInt()}")
        }

        return motionScore
    }

    fun reset() {
        lastFrame?.release()
        lastFrame = null
        totalFramesProcessed = 0L
    }
}
