package com.example.thermalviewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that renders a [ThermalData] frame using a chosen [ThermalPalette]
 * and optionally draws center / min / max crosshair overlays.
 */
class ThermalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var thermalData: ThermalData? = null
        set(value) {
            field = value
            if (value != null) rebuildBitmap(value)
            invalidate()
        }

    var palette: ThermalPalette = ThermalPalette.GNUPLOT
        set(value) {
            field = value
            thermalData?.let { rebuildBitmap(it) }
            invalidate()
        }

    var showCenter: Boolean = true
        set(value) { field = value; invalidate() }

    var showMinMax: Boolean = true
        set(value) { field = value; invalidate() }

    // --- private state ---

    private var bitmap: Bitmap? = null
    private val pixelBuf = IntArray(THERMAL_WIDTH * THERMAL_HEIGHT)

    // Smoothed display range — exponential moving average to prevent flicker.
    // Alpha controls adaptation speed: lower = smoother but slower to track changes.
    private var smoothMin = Float.NaN
    private var smoothMax = Float.NaN
    private val rangeAlpha = 0.08f

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Paint for crosshair lines / circles
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // Paint for label shadow
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = Color.BLACK
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
    }

    // Paint for label foreground
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        style = Paint.Style.FILL
    }

    // Pre-allocated RectF to avoid allocations in onDraw
    private val dstRect = RectF()

    // -----------------------------------------------------------------------

    private fun rebuildBitmap(data: ThermalData) {
        // Find min/max raw values for this frame
        var minRaw = Int.MAX_VALUE
        var maxRaw = 0
        for (p in data.pixels) {
            val v = p.toInt() and 0xFFFF
            if (v < minRaw) minRaw = v
            if (v > maxRaw) maxRaw = v
        }

        // Smooth the range with an EMA to prevent per-frame flicker
        if (smoothMin.isNaN()) {
            smoothMin = minRaw.toFloat()
            smoothMax = maxRaw.toFloat()
        } else {
            smoothMin = smoothMin * (1f - rangeAlpha) + minRaw * rangeAlpha
            smoothMax = smoothMax * (1f - rangeAlpha) + maxRaw * rangeAlpha
        }
        val range = (smoothMax - smoothMin).coerceAtLeast(1f)

        for (i in data.pixels.indices) {
            val v = data.pixels[i].toInt() and 0xFFFF
            pixelBuf[i] = palette.getColor(((v - smoothMin) / range).coerceIn(0f, 1f))
        }

        var bmp = bitmap
        if (bmp == null || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(THERMAL_WIDTH, THERMAL_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap = bmp
        }
        bmp.setPixels(pixelBuf, 0, THERMAL_WIDTH, 0, 0, THERMAL_WIDTH, THERMAL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val data = thermalData ?: return

        // Rotate canvas 90° CW so the thermal image appears upright.
        // After rotation, the effective fitting area swaps: height fits the image
        // width and width fits the image height.
        canvas.save()
        canvas.rotate(90f, width / 2f, height / 2f)

        val scaleX = height.toFloat() / THERMAL_WIDTH
        val scaleY = width.toFloat() / THERMAL_HEIGHT
        val scale = minOf(scaleX, scaleY)
        val ox = (width  - THERMAL_WIDTH  * scale) / 2f
        val oy = (height - THERMAL_HEIGHT * scale) / 2f
        dstRect.set(ox, oy, ox + THERMAL_WIDTH * scale, oy + THERMAL_HEIGHT * scale)

        canvas.drawBitmap(bmp, null, dstRect, imgPaint)

        // Helper: thermal (x, y) → view centre of that pixel (in rotated canvas space)
        fun tv(x: Int, y: Int) = PointF(ox + (x + 0.5f) * scale, oy + (y + 0.5f) * scale)

        if (showCenter) {
            val cx = THERMAL_WIDTH / 2
            val cy = THERMAL_HEIGHT / 2
            drawCrosshair(canvas, tv(cx, cy), Color.WHITE, data.centerTempC)
        }

        if (showMinMax) {
            val mn = data.minPoint
            val mx = data.maxPoint
            drawCrosshair(canvas, tv(mn.x, mn.y), Color.CYAN,    mn.tempC)
            drawCrosshair(canvas, tv(mx.x, mx.y), Color.rgb(255, 80, 80), mx.tempC)
        }

        canvas.restore()
    }

    private fun drawCrosshair(canvas: Canvas, pt: PointF, color: Int, tempC: Float) {
        val arm = 12f
        hairPaint.color = color

        // Cross lines and circle (drawn in the rotated canvas space)
        canvas.drawLine(pt.x - arm, pt.y, pt.x + arm, pt.y, hairPaint)
        canvas.drawLine(pt.x, pt.y - arm, pt.x, pt.y + arm, hairPaint)
        canvas.drawCircle(pt.x, pt.y, arm / 2f, hairPaint)

        // Label: counter-rotate so text reads upright regardless of video rotation
        val label = "%.1f°C".format(tempC)
        val lx = pt.x + arm + 4f
        val ly = pt.y + shadowPaint.textSize / 3f

        canvas.save()
        canvas.rotate(-90f, pt.x, pt.y)
        canvas.drawText(label, lx, ly, shadowPaint)
        labelPaint.color = color
        canvas.drawText(label, lx, ly, labelPaint)
        canvas.restore()
    }
}
