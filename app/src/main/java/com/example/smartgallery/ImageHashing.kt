package com.example.smartgallery

import android.graphics.Bitmap
import android.util.Log

object ImageHashing {

    fun dhashFromBitmap(bmp: Bitmap): String {
        return try {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            NativeBridge.nativeDHash(pixels, w, h)
        } catch (t: Throwable) {
            // fallback to kotlin implementation (previous code)
            Log.w("ImageHashing", "Fallback to dhash kotlin impl", t)
            kotlinDHash(bmp)
        }
    }

    fun isBlurryFromBitmap(bmp: Bitmap, threshold: Float = 100.0f): Boolean {
        return try {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            NativeBridge.nativeIsBlurry(pixels, w, h, threshold)
        } catch (t: Throwable) {
            Log.w("ImageHashing", "Fallback to blurry kotlin impl", t)
            kotlinIsBlurry(bmp, threshold)
        }
    }

    // NEW: get raw variance for calibration
    fun lapVarianceFromBitmap(bmp: Bitmap): Double {
        return try {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            NativeBridge.nativeLapVariance(pixels, w, h)
        } catch (t: Throwable) {
            // fallback compute approximate variance (simple)
            Log.w("ImageHashing", "Fallback to approximate variance", t)
            kotlinLapVarianceApprox(bmp)
        }
    }

    // ---------- FALLBACK Kotlin impls (kept for robustness) ----------
    private fun kotlinDHash(bmp: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val bits = StringBuilder()
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = small.getPixel(x, y)
                val right = small.getPixel(x + 1, y)
                val leftL = rgbToGray(left)
                val rightL = rgbToGray(right)
                bits.append(if (leftL > rightL) '1' else '0')
            }
        }
        val hex = bits.toString().chunked(4).joinToString("") { Integer.parseInt(it, 2).toString(16) }
        return hex
    }

    private fun kotlinIsBlurry(bmp: Bitmap, threshold: Float = 100f): Boolean {
        return kotlinLapVarianceApprox(bmp) < threshold
    }

    private fun kotlinLapVarianceApprox(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        var sum = 0.0
        var cnt = 0
        for (y in 1 until h - 1 step 4) {
            for (x in 1 until w - 1 step 4) {
                val center = rgbToGray(bmp.getPixel(x, y))
                val left = rgbToGray(bmp.getPixel(x - 1, y))
                val right = rgbToGray(bmp.getPixel(x + 1, y))
                val up = rgbToGray(bmp.getPixel(x, y - 1))
                val down = rgbToGray(bmp.getPixel(x, y + 1))
                val lap = (left + right + up + down) - 4 * center
                sum += (lap * lap).toDouble()
                cnt++
            }
        }
        return if (cnt > 0) sum / cnt else 0.0
    }

    private fun rgbToGray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // Hamming helper (same as earlier)
    fun hammingDistanceHex(a: String, b: String): Int {
        val len = minOf(a.length, b.length)
        var dist = 0
        for (i in 0 until len) {
            val na = Character.digit(a[i], 16)
            val nb = Character.digit(b[i], 16)
            val x = na xor nb
            dist += Integer.bitCount(x)
        }
        return dist
    }
}
