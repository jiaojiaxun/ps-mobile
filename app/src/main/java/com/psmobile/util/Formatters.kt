package com.psmobile.util

import android.content.res.Resources
import com.psmobile.R
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** Groups digits so a six-figure pixel count stays readable: `247 517`. */
fun formatPixels(value: Int): String {
    val s = value.toString()
    if (s.length <= 4) return s
    return s.reversed().chunked(3).joinToString(" ").reversed()
}

fun formatDimensions(width: Int, height: Int): String =
    "${formatPixels(width)} × ${formatPixels(height)}"

fun formatMegapixels(width: Int, height: Int): String {
    val pixels = width.toLong() * height.toLong()
    val mp = pixels / 1_000_000.0
    return when {
        // Below a megapixel, "0.0 MP" says nothing; count thousands instead.
        pixels < 1_000_000 -> String.format(Locale.getDefault(), "%.0f K", pixels / 1000.0)
        mp < 10 -> String.format(Locale.getDefault(), "%.1f MP", mp)
        else -> String.format(Locale.getDefault(), "%.0f MP", mp)
    }
}

fun formatZoom(scale: Float): String = when {
    scale >= 8f -> "${Math.round(scale * 100)}%"
    scale >= 1f -> String.format(Locale.getDefault(), "%.0f%%", scale * 100)
    scale >= 0.1f -> String.format(Locale.getDefault(), "%.1f%%", scale * 100)
    else -> String.format(Locale.getDefault(), "%.2f%%", scale * 100)
}

/** Compact relative time for the recents list. */
fun formatRelativeTime(res: Resources, epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - epochMillis).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1L -> res.getString(R.string.time_now)
        minutes < 60L -> res.getString(R.string.time_minutes, minutes.toInt())
        hours < 24L -> res.getString(R.string.time_hours, hours.toInt())
        days < 7L -> res.getString(R.string.time_days, days.toInt())
        days < 30L -> res.getString(R.string.time_weeks, (days / 7).toInt())
        days < 365L -> res.getString(R.string.time_months, (days / 30).toInt())
        else -> res.getString(R.string.time_years, (days / 365).toInt())
    }
}
