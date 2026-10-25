package com.bletestclient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom Dice View matching the "Ludo BLE Controller.html" design.
 * Draws a 3D-styled white rounded tile with color-adaptive circular pips (1-6).
 */
class DiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var diceValue: Int = 1
        set(value) {
            field = value.coerceIn(1, 6)
            invalidate()
        }

    var pipColor: Int = Color.parseColor("#E5484D")
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44000000")
        style = Paint.Style.FILL
    }

    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private val shadowRect = RectF()
    private var isPressedDown = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Maintain 1:1 aspect ratio
        setMeasuredDimension(width, width)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressedDown = true
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressedDown = false
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedDown = false
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val radius = w * 0.22f
        val shadowHeight = if (isPressedDown) w * 0.02f else w * 0.08f
        val topOffset = if (isPressedDown) w * 0.06f else 0f

        // Draw bottom 3D shadow
        shadowRect.set(0f, shadowHeight, w, h)
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

        // Draw top face
        rect.set(0f, topOffset, w, h - shadowHeight + topOffset)
        bgPaint.alpha = if (isEnabled) 255 else 160
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // Draw pips
        pipPaint.color = pipColor
        pipPaint.alpha = if (isEnabled) 255 else 120

        val pipRadius = w * 0.085f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val left = rect.left + rect.width() * 0.26f
        val right = rect.right - rect.width() * 0.26f
        val top = rect.top + rect.height() * 0.26f
        val bottom = rect.bottom - rect.height() * 0.26f

        when (diceValue) {
            1 -> {
                canvas.drawCircle(cx, cy, pipRadius, pipPaint)
            }
            2 -> {
                canvas.drawCircle(left, top, pipRadius, pipPaint)
                canvas.drawCircle(right, bottom, pipRadius, pipPaint)
            }
            3 -> {
                canvas.drawCircle(left, top, pipRadius, pipPaint)
                canvas.drawCircle(cx, cy, pipRadius, pipPaint)
                canvas.drawCircle(right, bottom, pipRadius, pipPaint)
            }
            4 -> {
                canvas.drawCircle(left, top, pipRadius, pipPaint)
                canvas.drawCircle(right, top, pipRadius, pipPaint)
                canvas.drawCircle(left, bottom, pipRadius, pipPaint)
                canvas.drawCircle(right, bottom, pipRadius, pipPaint)
            }
            5 -> {
                canvas.drawCircle(left, top, pipRadius, pipPaint)
                canvas.drawCircle(right, top, pipRadius, pipPaint)
                canvas.drawCircle(cx, cy, pipRadius, pipPaint)
                canvas.drawCircle(left, bottom, pipRadius, pipPaint)
                canvas.drawCircle(right, bottom, pipRadius, pipPaint)
            }
            6 -> {
                canvas.drawCircle(left, top, pipRadius, pipPaint)
                canvas.drawCircle(left, cy, pipRadius, pipPaint)
                canvas.drawCircle(left, bottom, pipRadius, pipPaint)
                canvas.drawCircle(right, top, pipRadius, pipPaint)
                canvas.drawCircle(right, cy, pipRadius, pipPaint)
                canvas.drawCircle(right, bottom, pipRadius, pipPaint)
            }
        }
    }
}
