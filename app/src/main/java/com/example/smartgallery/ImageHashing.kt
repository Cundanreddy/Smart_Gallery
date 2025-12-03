package com.example.smartgallery

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object ImageHashing {
    // Fallback pure-Kotlin implementations (kept but we will prefer native)
    private fun kotlinDHash(bmp: Bitmap): String { /* existing Kotlin dHash code */
        // copy earlier dhash implementation here
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

    private fun kotlinIsBlurry(bmp: Bitmap, threshold: Float = 100.0f): Boolean {
        // use your earlier Kotlin isBlurry code (approx)
        val w = bmp.width
        val h = bmp.height
        var sum = 0.0
        var cnt = 0
        for (y in 1 until h-1 step 4) {
            for (x in 1 until w-1 step 4) {
                val c = rgbToGray(bmp.getPixel(x,y))
                val gx = -rgbToGray(bmp.getPixel(x-1,y)) + rgbToGray(bmp.getPixel(x+1,y))
                val gy = -rgbToGray(bmp.getPixel(x,y-1)) + rgbToGray(bmp.getPixel(x,y+1))
                val lap = (gx*gx + gy*gy).toDouble()
                sum += lap
                cnt++
            }
        }
        val variance = if (cnt > 0) (sum / cnt) else 0.0
        return variance < threshold
    }

    private fun rgbToGray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // Public API: accept a Bitmap thumbnail and return dhash
    fun dhashFromBitmap(bmp: Bitmap): String {
        return try {
            // get pixels into IntArray (will be used by native)
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            // call native (native expects ARGB ints)
            NativeBridge.nativeDHash(pixels, w, h)
        } catch (e: Throwable) {
            // fallback
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
        } catch (e: Throwable) {
            kotlinIsBlurry(bmp, threshold)
        }
    }

    // helper: Hamming distance remains same as before
    fun hammingDistanceHex(a: String, b: String): Int { /* same as before */
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
