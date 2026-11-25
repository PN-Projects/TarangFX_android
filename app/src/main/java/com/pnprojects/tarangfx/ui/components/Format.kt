package com.pnprojects.tarangfx.ui.components

import java.util.Locale
import kotlin.math.abs

object Fmt {
    /** 00:11.6 (mm:ss.t) or 1:02:03.4 for > 1 h. */
    fun timeTenths(ms: Long): String {
        val t = abs(ms)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val d = (t / 100) % 10
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, d)
        else String.format(Locale.US, "%02d:%02d.%d", m, s, d)
    }

    /** 00:37 or 1:02:03. */
    fun time(ms: Long): String {
        val t = abs(ms)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun rate(hz: Int): String = if (hz <= 0) "Original" else if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.2f kHz", hz / 1000.0)
    fun rateShort(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}kHz" else String.format(Locale.US, "%.1fkHz", hz / 1000.0)
}
