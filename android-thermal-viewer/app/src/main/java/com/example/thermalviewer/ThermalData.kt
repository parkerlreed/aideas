package com.example.thermalviewer

const val THERMAL_WIDTH = 256
const val THERMAL_HEIGHT = 192

/**
 * A single captured frame of thermal data.
 *
 * [pixels] contains 256×192 raw uint16 values from the camera.
 * Temperature in Kelvin = (pixel.toInt() and 0xFFFF) / 64.0
 */
class ThermalData(val pixels: ShortArray) {

    init {
        require(pixels.size == THERMAL_WIDTH * THERMAL_HEIGHT) {
            "Expected ${THERMAL_WIDTH * THERMAL_HEIGHT} pixels, got ${pixels.size}"
        }
    }

    fun tempKelvin(x: Int, y: Int): Float {
        val raw = pixels[y * THERMAL_WIDTH + x].toInt() and 0xFFFF
        return raw / 64.0f
    }

    fun tempCelsius(x: Int, y: Int): Float = tempKelvin(x, y) - 273.15f

    val centerTempC: Float get() = tempCelsius(THERMAL_WIDTH / 2, THERMAL_HEIGHT / 2)

    data class Point(val x: Int, val y: Int, val tempC: Float)

    val minPoint: Point get() {
        var minIdx = 0
        var minVal = Int.MAX_VALUE
        for (i in pixels.indices) {
            val v = pixels[i].toInt() and 0xFFFF
            if (v < minVal) { minVal = v; minIdx = i }
        }
        return Point(minIdx % THERMAL_WIDTH, minIdx / THERMAL_WIDTH, (minVal / 64.0f) - 273.15f)
    }

    val maxPoint: Point get() {
        var maxIdx = 0
        var maxVal = 0
        for (i in pixels.indices) {
            val v = pixels[i].toInt() and 0xFFFF
            if (v > maxVal) { maxVal = v; maxIdx = i }
        }
        return Point(maxIdx % THERMAL_WIDTH, maxIdx / THERMAL_WIDTH, (maxVal / 64.0f) - 273.15f)
    }
}
