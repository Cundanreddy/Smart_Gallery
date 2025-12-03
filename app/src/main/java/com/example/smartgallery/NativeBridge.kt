package com.example.smartgallery

object NativeBridge {
    init {
        System.loadLibrary("media_utils")
    }

    external fun nativeDHash(pixels: IntArray, width: Int, height: Int): String
    external fun nativeIsBlurry(pixels: IntArray, width: Int, height: Int, threshold: Float): Boolean
}
