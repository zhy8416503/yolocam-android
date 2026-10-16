package com.zhy8416503.yolocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 检测框 overlay: 归一化坐标按 aspect-fill 映射到屏幕 (与 iOS v3 一致)。
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var detections: List<Detection> = emptyList()
        set(value) { field = value; invalidate() }
    // 扶正后图像宽高比 (Wo/Ho)
    var orientedAspect: Float = 9f / 16f
        set(value) { field = value; invalidate() }

    private val boxPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.GREEN; textSize = 32f
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return

        // aspect-fill: 图像 (aspect=orientedAspect) 填满屏幕
        val scale = max(vw / orientedAspect, vh / 1f) / 1f
        // 实际上: scale = max(vw/Wo, vh/Ho), 其中 Wo/Ho = orientedAspect
        // 设 Ho=1, Wo=orientedAspect -> scale = max(vw/orientedAspect, vh)
        val s = max(vw / orientedAspect, vh)
        val drawnW = orientedAspect * s
        val drawnH = s
        val offX = (vw - drawnW) / 2
        val offY = (vh - drawnH) / 2

        for (d in detections) {
            val left = offX + d.x * drawnW
            val top = offY + d.y * drawnH
            val right = offX + (d.x + d.width) * drawnW
            val bottom = offY + (d.y + d.height) * drawnH
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val text = "${d.label} ${String.format("%.2f", d.confidence)}"
            val tw = textPaint.measureText(text)
            canvas.drawRect(left, top - 40, left + tw + 16, top, bgPaint)
            canvas.drawText(text, left + 8, top - 10, textPaint)
        }
    }
}
