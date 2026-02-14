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
    private var totalFramesProcessed = 0L

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

    // Callback invoked once after calibration completes, with a debug Bitmap showing
    // the camera frame with masked (excluded) regions painted in red.
    // Set this before processing frames to receive the debug image.
    var onCalibrationComplete: ((debugBitmap: Bitmap) -> Unit)? = null

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

                // Generate debug overlay image for visual verification
                generateCalibrationDebugImage(currentFrame, width, height)
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

        totalFramesProcessed++
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
            Log.i(TAG, "Masked regions (first 10): $maskedRegions")
        }
        if (maskedCells == 0) {
            Log.i(TAG, "No persistent motion zones found — camera may not have timestamp overlay")
        }

        // Log compact visual heat map: shows hit count per cell as a character grid
        // '.' = 0 hits, '1'-'9' = 1-9 hits, '#' = 10+ hits, 'X' = masked (>= threshold)
        val sb = StringBuilder("\nHeat map (${GRID_ROWS}x${GRID_COLS}, threshold=$threshold):\n")
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val hits = motionHeatMap[row][col]
                sb.append(when {
                    hits >= threshold -> 'X'
                    hits == 0 -> '.'
                    hits < 10 -> hits.toString()[0]
                    else -> '#'
                })
            }
            sb.append('\n')
        }
        Log.d(TAG, sb.toString())
    }

    /**
     * Generate a debug image showing the camera frame with masked regions highlighted.
     * Red overlay = excluded zones (where timestamp/OSD was detected).
     * Green grid = cell boundaries for reference.
     * The image is passed to onCalibrationComplete callback for saving.
     */
    private fun generateCalibrationDebugImage(colorFrame: Mat, width: Int, height: Int) {
        try {
            val callback = onCalibrationComplete ?: return

            // Clone the color frame so we can draw on it
            val debugMat = colorFrame.clone()

            val cellW = width.toDouble() / GRID_COLS
            val cellH = height.toDouble() / GRID_ROWS
            val threshold = (calibrationFrameCount * PERSISTENT_THRESHOLD).toInt()

            // Draw semi-transparent red overlay on masked cells
            val redOverlay = Mat.zeros(debugMat.size(), debugMat.type())
            for (row in 0 until GRID_ROWS) {
                for (col in 0 until GRID_COLS) {
                    val hitCount = motionHeatMap[row][col]
                    if (hitCount >= threshold) {
                        val x = (col * cellW).toInt().coerceAtMost(width - 1)
                        val y = (row * cellH).toInt().coerceAtMost(height - 1)
                        val w = cellW.toInt().coerceAtMost(width - x)
                        val h = cellH.toInt().coerceAtMost(height - y)

                        // Red filled rectangle on the overlay
                        Imgproc.rectangle(
                            redOverlay,
                            Rect(x, y, w, h),
                            Scalar(255.0, 0.0, 0.0, 255.0),  // Red (RGBA)
                            -1
                        )
                    }
                }
            }

            // Blend: 70% original + 30% red overlay (semi-transparent effect)
            Core.addWeighted(debugMat, 0.7, redOverlay, 0.3, 0.0, debugMat)
            redOverlay.release()

            // Draw grid lines (thin, dark green) for reference
            val gridColor = Scalar(0.0, 100.0, 0.0, 128.0)
            for (col in 1 until GRID_COLS) {
                val x = (col * cellW).toInt()
                Imgproc.line(debugMat, Point(x.toDouble(), 0.0), Point(x.toDouble(), height.toDouble()), gridColor, 1)
            }
            for (row in 1 until GRID_ROWS) {
                val y = (row * cellH).toInt()
                Imgproc.line(debugMat, Point(0.0, y.toDouble()), Point(width.toDouble(), y.toDouble()), gridColor, 1)
            }

            // Draw hit counts on cells with motion (for cells with > 0 hits)
            val fontScale = 0.3
            for (row in 0 until GRID_ROWS) {
                for (col in 0 until GRID_COLS) {
                    val hitCount = motionHeatMap[row][col]
                    if (hitCount > 0) {
                        val cx = ((col + 0.2) * cellW).toInt()
                        val cy = ((row + 0.7) * cellH).toInt()
                        val color = if (hitCount >= threshold)
                            Scalar(255.0, 255.0, 255.0, 255.0)  // White on red cells
                        else
                            Scalar(200.0, 200.0, 0.0, 255.0)    // Yellow on normal cells
                        Imgproc.putText(
                            debugMat, "$hitCount", Point(cx.toDouble(), cy.toDouble()),
                            Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, color, 1
                        )
                    }
                }
            }

            // Add legend text at top
            Imgproc.putText(
                debugMat, "RED = Masked (excluded) zones | threshold=$threshold/$calibrationFrameCount",
                Point(10.0, 25.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7,
                Scalar(0.0, 255.0, 255.0, 255.0), 2
            )

            // Convert to Bitmap and invoke callback
            val debugBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(debugMat, debugBitmap)
            debugMat.release()

            callback(debugBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate calibration debug image: ${e.message}")
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

        var totalContours = contours.size
        var tooSmall = 0
        var filteredByAspect = 0
        var filteredByEdge = 0
        var accepted = 0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minContourArea) {
                val r = Imgproc.boundingRect(contour)

                // Filter 1: Aspect ratio — text/timestamps are very wide and short
                val aspectRatio = r.width.toDouble() / r.height.toDouble().coerceAtLeast(1.0)
                if (aspectRatio > 4.0 || aspectRatio < 0.25) {
                    // Text-like shape — skip
                    filteredByAspect++
                    contour.release()
                    continue
                }

                // Filter 2: Ignore small blobs at top/bottom 15% (camera timestamps)
                // Extended from 10% to 15% for better coverage
                val isAtEdge = r.y < height * 0.15 || (r.y + r.height) > height * 0.85
                val isSmall = area < (width * height) * 0.01

                if (!isAtEdge || !isSmall) {
                    Imgproc.drawContours(filteredMask, listOf(contour), -1, Scalar(255.0), -1)
                    accepted++
                } else {
                    filteredByEdge++
                }
            } else {
                tooSmall++
            }
            contour.release()
        }

        val motionScore = Core.sumElems(filteredMask).`val`[0]
        filteredMask.release()
        hierarchy.release()
        if (ownsmasked) masked.release()

        // Periodic detailed log every 50 frames
        if (isCalibrated && totalFramesProcessed % 50 == 0L) {
            Log.i(TAG, "Filters: contours=$totalContours → tooSmall=$tooSmall, " +
                "aspectRatio=$filteredByAspect, edge=$filteredByEdge, accepted=$accepted → " +
                "score=${motionScore.toInt()}, maskApplied=$applyTimestampMask")
        }

        return motionScore
    }

    fun reset() {
        lastFrame?.release()
        lastFrame = null
        timestampMask?.release()
        timestampMask = null
        isCalibrated = false
        calibrationFrameCount = 0
        totalFramesProcessed = 0L
        for (row in motionHeatMap) row.fill(0)
    }
}
