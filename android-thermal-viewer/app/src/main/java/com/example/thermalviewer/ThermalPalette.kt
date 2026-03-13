package com.example.thermalviewer

import android.graphics.Color

/**
 * Color palettes for rendering thermal images.
 *
 * Each palette maps a normalized [0, 1] float (coldest → hottest) to an ARGB int.
 * The gradient tables below are ported from thermal-cat's ThermalGradient.
 */
enum class ThermalPalette(val displayName: String) {

    GNUPLOT("Gnuplot") {
        override fun getColor(t: Float) = interpolate(t, GNUPLOT_GRADIENT)
    },
    COLD_WARM("Cold → Warm") {
        override fun getColor(t: Float) = interpolate(t, COLD_WARM_GRADIENT)
    },
    WHITE_HOT("White Hot") {
        override fun getColor(t: Float): Int {
            val v = (t.coerceIn(0f, 1f) * 255).toInt()
            return Color.rgb(v, v, v)
        }
    },
    BLACK_HOT("Black Hot") {
        override fun getColor(t: Float): Int {
            val v = 255 - (t.coerceIn(0f, 1f) * 255).toInt()
            return Color.rgb(v, v, v)
        }
    };

    abstract fun getColor(t: Float): Int

    companion object {
        // Ported from thermal-cat's gnuplot gradient
        private val GNUPLOT_GRADIENT = arrayOf(
            floatArrayOf(0f,   0f,   0f),
            floatArrayOf(66f,  0f,   104f),
            floatArrayOf(93f,  1f,   190f),
            floatArrayOf(114f, 2f,   243f),
            floatArrayOf(132f, 5f,   254f),
            floatArrayOf(147f, 9f,   221f),
            floatArrayOf(161f, 16f,  150f),
            floatArrayOf(174f, 26f,  53f),
            floatArrayOf(186f, 39f,  0f),
            floatArrayOf(198f, 55f,  0f),
            floatArrayOf(208f, 76f,  0f),
            floatArrayOf(218f, 101f, 0f),
            floatArrayOf(228f, 131f, 0f),
            floatArrayOf(237f, 166f, 0f),
            floatArrayOf(246f, 207f, 0f),
            floatArrayOf(255f, 255f, 0f),
        )

        // Ported from thermal-cat's Cold-warm gradient
        private val COLD_WARM_GRADIENT = arrayOf(
            floatArrayOf(0f,   0f,   0f),
            floatArrayOf(0f,   0f,   255f),
            floatArrayOf(0f,   255f, 255f),
            floatArrayOf(0f,   255f, 0f),
            floatArrayOf(255f, 255f, 0f),
            floatArrayOf(255f, 128f, 0f),
            floatArrayOf(255f, 0f,   0f),
            floatArrayOf(255f, 0f,   255f),
            floatArrayOf(255f, 255f, 255f),
        )

        private fun interpolate(t: Float, gradient: Array<FloatArray>): Int {
            val clamped = t.coerceIn(0f, 1f)
            val n = gradient.size
            if (n == 1) return Color.rgb(gradient[0][0].toInt(), gradient[0][1].toInt(), gradient[0][2].toInt())

            val segLen = 1f / (n - 1)
            val seg = (clamped / segLen).toInt().coerceAtMost(n - 2)
            val localT = (clamped - seg * segLen) / segLen
            val a = gradient[seg]
            val b = gradient[seg + 1]
            return Color.rgb(
                (a[0] + (b[0] - a[0]) * localT).toInt(),
                (a[1] + (b[1] - a[1]) * localT).toInt(),
                (a[2] + (b[2] - a[2]) * localT).toInt(),
            )
        }
    }
}
