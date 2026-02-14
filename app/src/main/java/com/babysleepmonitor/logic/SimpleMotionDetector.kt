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
 * Timestamp/OSD Filtering Strategy (replaces ML Kit text recognition):
 *   Camera timestamps change every second, producing consistent small motion
 *   in the same screen region every frame. We detect these regions automatically:
 *
 *   CALIBRATION PHASE (first ~25 frames, ~5 seconds at 5 FPS):
 *     - Divide the frame into a grid of cells (e.g., 32×18 cells for 1080p).
 *     - For each frame diff, record which cells contain motion.
 *     - After calibration, cells with motion in >70% of frames are classified
 *       as "persistent motion zones" (timestamps, OSD overlays).
 *     - These cells are permanently masked out for all subsequent frames.
 *
 *   RUNTIME (after calibration):
 *     - Apply the learned timestamp mask before calculating motion score.
 *     - Additionally filter by contour aspect ratio (text-like shapes are
 *       typically very wide and short, ratio >4.0).
 *     - Edge-position filter for small blobs near top/bottom 15% of frame.
 *
 *   This approach is robust because:
 *     - Works with ANY camera regardless of timestamp position/format.
 *     - Self-calibrates — no manual configuration needed.
 *     - Pure OpenCV — no ML Kit dependencies or singleton conflicts.
 *     - Baby motion is intermittent; timestamp motion is persistent.
 *       The calibration easily distinguishes the two.
 *
 * Algorithm:
 *   1. Convert to grayscale
 *   2. CLAHE contrast enhancement
 *   3. Gaussian blur (21×21)
 *   4. Absolute difference with previous frame
 *   5. Binary threshold (5)
 *   6. Dilate (2 iterations)
 *   7. Apply timestamp exclusion mask (learned during calibration)
 *   8. Find contours, filter by:
 *      - Minimum area (0.05% of frame — ignores tiny pixel noise)
 *      - Aspect ratio (ignores text-like shapes with ratio >4.0 or <0.25)
 *      - Edge position (ignores small blobs in top/bottom 15% — timestamps)
 *   9. Sum remaining white pixels as motionScore
 */
class SimpleMotionDetector {
    private val TAG = "SimpleMotionDetector"
    private var lastFrame: Mat? = null
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    // --- Timestamp zone auto-detection ---
    // Grid-based heat map: divide frame into cells, track which cells always have motion.
    private companion object {
        const val GRID_COLS = 48       // Number of horizontal cells (finer grid = more precise masking)
        const val GRID_ROWS = 27       // Number of vertical cells (48:27 ≈ 16:9 aspect ratio)
        const val CALIBRATION_FRAMES = 25  // ~5 seconds at 5 FPS
        const val PERSISTENT_THRESHOLD = 0.70  // Cell with motion in >70% of frames = timestamp
    }

    // motionHeatMap[row][col] = number of frames where cell had motion
    private val motionHeatMap = Array(GRID_ROWS) { IntArray(GRID_COLS) }
    private var calibrationFrameCount = 0
    private var isCalibrated = false

    // The learned timestamp exclusion mask (same size as thresh image).
    // White = allowed region, Black = excluded (timestamp) region.
    // null until calibration completes.
    private var timestampMask: Mat? = null

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

        // --- Calibration phase: learn timestamp zones ---
        if (!isCalibrated) {
            updateCalibrationHeatMap(thresh, width, height)
            calibrationFrameCount++

            if (calibrationFrameCount >= CALIBRATION_FRAMES) {
                buildTimestampMask(width, height)
                isCalibrated = true
                Log.i(TAG, "✅ Timestamp calibration complete after $calibrationFrameCount frames")
            } else {
                // During calibration, still compute motion but with basic filtering only.
                // This avoids false "no breathing" alarms during the first 5 seconds.
                val motionScore = computeMotionScore(thresh, width, height, applyTimestampMask = false)

                lastFrame!!.release()
                lastFrame = gray
                currentFrame.release()
                diff.release()
                thresh.release()

                return Result(motionScore, width, height)
            }
        }

        // 7. Apply timestamp exclusion mask
        val motionScore = computeMotionScore(thresh, width, height, applyTimestampMask = true)

        // Update last frame
        lastFrame!!.release()
        lastFrame = gray
        currentFrame.release()
        diff.release()
        thresh.release()

        return Result(motionScore, width, height)
    }

    /**
     * During calibration, record which grid cells contain motion in this frame.
     */
    private fun updateCalibrationHeatMap(thresh: Mat, width: Int, height: Int) {
        val cellW = width.toDouble() / GRID_COLS
        val cellH = height.toDouble() / GRID_ROWS

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val x = (col * cellW).toInt().coerceAtMost(width - 1)
                val y = (row * cellH).toInt().coerceAtMost(height - 1)
                val w = cellW.toInt().coerceAtMost(width - x)
                val h = cellH.toInt().coerceAtMost(height - y)

                if (w <= 0 || h <= 0) continue

                val cellRect = Rect(x, y, w, h)
                val cellMat = thresh.submat(cellRect)
                val cellSum = Core.sumElems(cellMat).`val`[0]
                cellMat.release()

                // If this cell has any white pixels (motion), increment counter
                if (cellSum > 0) {
                    motionHeatMap[row][col]++
                }
            }
        }
    }

    /**
     * After calibration, build the exclusion mask from the heat map.
     * Cells with motion in >PERSISTENT_THRESHOLD of calibration frames are timestamps.
     */
    private fun buildTimestampMask(width: Int, height: Int) {
        // Start with all-white mask (everything allowed)
        timestampMask = Mat(height, width, CvType.CV_8UC1, Scalar(255.0))

        val cellW = width.toDouble() / GRID_COLS
        val cellH = height.toDouble() / GRID_ROWS
        val threshold = (calibrationFrameCount * PERSISTENT_THRESHOLD).toInt()

        var maskedCells = 0
        val maskedRegions = mutableListOf<String>()

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val hitCount = motionHeatMap[row][col]
                if (hitCount >= threshold) {
                    // This cell had persistent motion → timestamp/OSD → mask it out
                    val x = (col * cellW).toInt().coerceAtMost(width - 1)
                    val y = (row * cellH).toInt().coerceAtMost(height - 1)
                    val w = cellW.toInt().coerceAtMost(width - x)
                    val h = cellH.toInt().coerceAtMost(height - y)

                    // Add margin around the detected cell to catch blur/ghosting artifacts
                    val margin = (cellW * 0.5).toInt()
                    val mx = (x - margin).coerceAtLeast(0)
                    val my = (y - margin).coerceAtLeast(0)
                    val mw = (w + margin * 2).coerceAtMost(width - mx)
                    val mh = (h + margin * 2).coerceAtMost(height - my)

                    Imgproc.rectangle(
                        timestampMask!!,
                        Rect(mx, my, mw, mh),
                        Scalar(0.0),  // Black = excluded
                        -1  // Filled
                    )
                    maskedCells++
                    if (maskedCells <= 10) {
                        maskedRegions.add("($col,$row) hits=$hitCount/${calibrationFrameCount}")
                    }
                }
            }
        }

        Log.i(TAG, "Timestamp mask: $maskedCells cells masked out of ${GRID_ROWS * GRID_COLS} " +
            "(threshold=$threshold hits in $calibrationFrameCount frames)")
        if (maskedRegions.isNotEmpty()) {
            Log.d(TAG, "Masked regions (first 10): $maskedRegions")
        }
        if (maskedCells == 0) {
            Log.i(TAG, "No persistent motion zones found — camera may not have timestamp overlay")
        }
    }

    /**
     * Compute the motion score from the thresholded diff image.
     * Applies timestamp mask and contour filtering.
     */
    private fun computeMotionScore(thresh: Mat, width: Int, height: Int, applyTimestampMask: Boolean): Double {
        // Apply timestamp exclusion mask if available
        val masked = if (applyTimestampMask && timestampMask != null) {
            val result = Mat()
            Core.bitwise_and(thresh, timestampMask!!, result)
            result
        } else {
            thresh // Use original (not owned — don't release)
        }
        val ownsmasked = applyTimestampMask && timestampMask != null

        // Find contours and filter
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(masked, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val filteredMask = Mat.zeros(thresh.size(), thresh.type())
        val minContourArea = (width * height) * 0.0005  // 0.05% of frame

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea) {
                val r = Imgproc.boundingRect(contour)

                // Filter 1: Aspect ratio — text/timestamps are very wide and short
                val aspectRatio = r.width.toDouble() / r.height.toDouble().coerceAtLeast(1.0)
                if (aspectRatio > 4.0 || aspectRatio < 0.25) {
                    // Text-like shape — skip
                    contour.release()
                    continue
                }

                // Filter 2: Ignore small blobs at top/bottom 15% (camera timestamps)
                // Extended from 10% to 15% for better coverage
                val isAtEdge = r.y < height * 0.15 || (r.y + r.height) > height * 0.85
                val isSmall = area < (width * height) * 0.01

                if (!isAtEdge || !isSmall) {
                    Imgproc.drawContours(filteredMask, listOf(contour), -1, Scalar(255.0), -1)
                }
            }
            contour.release()
        }

        val motionScore = Core.sumElems(filteredMask).`val`[0]
        filteredMask.release()
        hierarchy.release()
        if (ownsmasked) masked.release()

        return motionScore
    }

    fun reset() {
        lastFrame?.release()
        lastFrame = null
        timestampMask?.release()
        timestampMask = null
        isCalibrated = false
        calibrationFrameCount = 0
        for (row in motionHeatMap) row.fill(0)
    }
}
