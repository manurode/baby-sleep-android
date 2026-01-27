package com.babysleepmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private var rects: List<Rect> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    
    fun updateBoxes(newRects: List<Rect>, width: Int, height: Int) {
        rects = newRects
        sourceWidth = width
        sourceHeight = height
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (sourceWidth == 0 || sourceHeight == 0) return
        
        // Handle aspect fit / fill logic if needed. 
        // For now assume simple stretch or view matches video aspect.
        
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        
        for (rect in rects) {
            // Scale the rect coordinates to the view size
            val left = rect.left * scaleX
            val top = rect.top * scaleY
            val right = rect.right * scaleX
            val bottom = rect.bottom * scaleY
            
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}
