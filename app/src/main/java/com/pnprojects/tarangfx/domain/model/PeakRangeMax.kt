package com.pnprojects.tarangfx.domain.model

import kotlin.math.max
import kotlin.math.min

/**
 * Range-maximum queries over waveform peaks, so drawing cost depends on screen width rather
 * than file length.
 *
 * Without it, every bar on screen scans all peaks it covers: a fully zoomed-out 60-minute file
 * (360 000 peaks at 100/s) costs ~360 000 reads per frame. Here peaks are grouped into blocks of
 * [BLOCK] and a bottom-up max tree is tabulated once over the block maxima
 * (`node[i] = max(node[2i], node[2i+1])`, a dynamic-programming fill from the leaves up).
 * A query scans at most two partial blocks plus O(log n) tree nodes.
 *
 * Memory: ~2 * n / [BLOCK] floats (about 180 KB for a 60-minute file) on top of the peaks.
 * Build: O(n), done once off the UI thread when the waveform is loaded.
 */
class PeakRangeMax(private val values: FloatArray) {
    private val blockCount = (values.size + BLOCK - 1) / BLOCK
    private val leafBase: Int
    private val tree: FloatArray

    init {
        var size = 1
        while (size < blockCount) size = size shl 1
        leafBase = size
        tree = FloatArray(2 * size)
        for (b in 0 until blockCount) {
            val from = b * BLOCK
            val to = min(from + BLOCK, values.size)
            var m = 0f
            for (i in from until to) if (values[i] > m) m = values[i]
            tree[size + b] = m
        }
        for (i in size - 1 downTo 1) tree[i] = max(tree[2 * i], tree[2 * i + 1])
    }

    /** Max of `values[from until to]` (clamped to the array); 0 for an empty range. */
    fun max(from: Int, to: Int): Float {
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(values.size)
        if (lo >= hi) return 0f
        // Short ranges (zoomed in): a linear scan is cheaper than any index walk.
        if (hi - lo <= 2 * BLOCK) return scan(lo, hi)

        val firstFull = (lo + BLOCK - 1) / BLOCK
        val lastFull = hi / BLOCK  // exclusive
        var res = max(scan(lo, firstFull * BLOCK), scan(lastFull * BLOCK, hi))
        // Bottom-up segment-tree query over whole blocks [firstFull, lastFull).
        var l = firstFull + leafBase
        var r = lastFull + leafBase
        while (l < r) {
            if (l and 1 == 1) { if (tree[l] > res) res = tree[l]; l++ }
            if (r and 1 == 1) { r--; if (tree[r] > res) res = tree[r] }
            l = l shr 1
            r = r shr 1
        }
        return res
    }

    private fun scan(from: Int, to: Int): Float {
        var m = 0f
        for (i in from until to) if (values[i] > m) m = values[i]
        return m
    }

    companion object {
        const val BLOCK = 16
    }
}
