package com.babysleepmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom View for selecting a Region of Interest (ROI) on the video feed.
 * Handles touch events to draw a rectangle overlay.
 * Returns normalized coordinates (0.0-1.0) based on the video aspect ratio.
 */
class RoiSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ROI rectangle in view coordinates
    private var roiRect: RectF? = null
    
    // Touch tracking
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false
    
    // Video aspect ratio for coordinate normalization (16:9 default)
    var videoAspectRatio: Float = 16f / 9f
        set(value) {
            field = value
            invalidate()
        }
    
    // Callback for when ROI is selected
    var onRoiSelected: ((x: Float, y: Float, w: Float, h: Float) -> Unit)? = null
    
    // Paints
    private val roiPaint = Paint().apply {
        color = Color.parseColor("#00D4AA")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val roiFillPaint = Paint().apply {
        color = Color.parseColor("#2000D4AA")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00D4AA")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Calculate the video display area (assuming fitCenter scaling)
        val videoRect = calculateVideoRect()
        
        // Draw dimmed area outside video
        if (videoRect != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), videoRect.top, dimPaint)
            canvas.drawRect(0f, videoRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, videoRect.top, videoRect.left, videoRect.bottom, dimPaint)
            canvas.drawRect(videoRect.right, videoRect.top, width.toFloat(), videoRect.bottom, dimPaint)
        }
        
        // Draw current ROI or selection in progress
        val rectToDraw = if (isDragging) {
            RectF(
                min(startX, currentX),
                min(startY, currentY),
                max(startX, currentX),
                max(startY, currentY)
            )
        } else {
            roiRect
        }
        
        rectToDraw?.let { rect ->
            // Draw dimmed area outside ROI (within video area)
            videoRect?.let { vr ->
                // Top dim
                canvas.drawRect(vr.left, vr.top, vr.right, rect.top, dimPaint)
                // Bottom dim
                canvas.drawRect(vr.left, rect.bottom, vr.right, vr.bottom, dimPaint)
                // Left dim
                canvas.drawRect(vr.left, rect.top, rect.left, rect.bottom, dimPaint)
                // Right dim
                canvas.drawRect(rect.right, rect.top, vr.right, rect.bottom, dimPaint)
            }
            
            // Draw ROI rectangle fill
            canvas.drawRect(rect, roiFillPaint)
            
            // Draw ROI border
            canvas.drawRect(rect, roiPaint)
            
            // Draw corner handles
            val cornerSize = 24f
            val corners = listOf(
                RectF(rect.left - cornerSize/2, rect.top - cornerSize/2, rect.left + cornerSize/2, rect.top + cornerSize/2),
                RectF(rect.right - cornerSize/2, rect.top - cornerSize/2, rect.right + cornerSize/2, rect.top + cornerSize/2),
                RectF(rect.left - cornerSize/2, rect.bottom - cornerSize/2, rect.left + cornerSize/2, rect.bottom + cornerSize/2),
                RectF(rect.right - cornerSize/2, rect.bottom - cornerSize/2, rect.right + cornerSize/2, rect.bottom + cornerSize/2)
            )
            corners.forEach { corner ->
                canvas.drawOval(corner, cornerPaint)
            }
        }
        
        // Draw instructions if no ROI is selected
        if (roiRect == null && !isDragging) {
            canvas.drawText(
                "Drag to select detection zone",
                width / 2f,
                height / 2f,
                textPaint
            )
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val videoRect = calculateVideoRect() ?: return false
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Constrain start point to video area
                startX = event.x.coerceIn(videoRect.left, videoRect.right)
                startY = event.y.coerceIn(videoRect.top, videoRect.bottom)
                currentX = startX
                currentY = startY
                isDragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Constrain current point to video area
                currentX = event.x.coerceIn(videoRect.left, videoRect.right)
                currentY = event.y.coerceIn(videoRect.top, videoRect.bottom)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                
                // Calculate final rectangle
                val minX = min(startX, currentX)
                val minY = min(startY, currentY)
                val maxX = max(startX, currentX)
                val maxY = max(startY, currentY)
                
                // Only set ROI if it's a reasonable size
                val minSize = 50f
                if (abs(maxX - minX) > minSize && abs(maxY - minY) > minSize) {
                    roiRect = RectF(minX, minY, maxX, maxY)
                    
                    // Convert to normalized coordinates relative to video area
                    val normalizedX = (minX - videoRect.left) / videoRect.width()
                    val normalizedY = (minY - videoRect.top) / videoRect.height()
                    val normalizedW = (maxX - minX) / videoRect.width()
                    val normalizedH = (maxY - minY) / videoRect.height()
                    
                    onRoiSelected?.invoke(
                        normalizedX.coerceIn(0f, 1f),
                        normalizedY.coerceIn(0f, 1f),
                        normalizedW.coerceIn(0f, 1f),
                        normalizedH.coerceIn(0f, 1f)
                    )
                }
                
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    /**
     * Calculate the video display rectangle assuming fitCenter scaling.
     */
    private fun calculateVideoRect(): RectF? {
        if (width == 0 || height == 0) return null
        
        val viewAspect = width.toFloat() / height.toFloat()
        
        return if (videoAspectRatio > viewAspect) {
            // Video is wider - letterbox (bars on top/bottom)
            val videoHeight = width / videoAspectRatio
            val topPadding = (height - videoHeight) / 2
            RectF(0f, topPadding, width.toFloat(), topPadding + videoHeight)
        } else {
            // Video is taller - pillarbox (bars on sides)
            val videoWidth = height * videoAspectRatio
            val leftPadding = (width - videoWidth) / 2
            RectF(leftPadding, 0f, leftPadding + videoWidth, height.toFloat())
        }
    }
    
    /**
     * Set existing ROI from normalized coordinates.
     */
    fun setRoi(x: Float, y: Float, w: Float, h: Float) {
        val videoRect = calculateVideoRect() ?: return
        
        roiRect = RectF(
            videoRect.left + x * videoRect.width(),
            videoRect.top + y * videoRect.height(),
            videoRect.left + (x + w) * videoRect.width(),
            videoRect.top + (y + h) * videoRect.height()
        )
        invalidate()
    }
    
    /**
     * Clear the current ROI selection.
     */
    fun clearRoi() {
        roiRect = null
        invalidate()
    }
    
    /**
     * Check if ROI is currently set.
     */
    fun hasRoi(): Boolean = roiRect != null
}
