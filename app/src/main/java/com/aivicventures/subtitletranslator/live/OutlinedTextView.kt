package com.aivicventures.subtitletranslator.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** Subtitle text with a black outline: the text is drawn once stroked, then filled on top. */
// A plain TextView on purpose: it is drawn in overlay windows, outside any AppCompat activity.
@SuppressLint("AppCompatCustomView")
class OutlinedTextView(context: Context) : TextView(context) {
    var outline = true
        set(v) {
            field = v
            invalidate()
        }
    var outlineColor = Color.BLACK
    private var drawingOutline = false

    override fun onDraw(canvas: Canvas) {
        if (!outline) {
            super.onDraw(canvas)
            return
        }
        val fill = currentTextColor
        drawingOutline = true
        setTextColor(outlineColor)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = textSize * 0.14f
        super.onDraw(canvas)
        setTextColor(fill)
        paint.style = Paint.Style.FILL
        drawingOutline = false
        super.onDraw(canvas)
    }

    // Swapping the colour inside onDraw must not schedule another frame.
    override fun invalidate() {
        if (!drawingOutline) super.invalidate()
    }
}
