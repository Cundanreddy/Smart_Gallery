package com.example.smartgallery.utils

import java.util.concurrent.TimeUnit

fun formatRemainingMillis(ms: Long): String {
    if (ms <= 0L) return "Expired"
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
    val mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val secs = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        days > 0 -> "$days d ${hours} h"
        hours > 0 -> "${hours} h ${mins} m"
        mins > 0 -> "${mins} m ${secs} s"
        else -> "${secs} s"
    }
}
