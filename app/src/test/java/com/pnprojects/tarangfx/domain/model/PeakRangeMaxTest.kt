package com.pnprojects.tarangfx.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PeakRangeMaxTest {

    private fun bruteMax(v: FloatArray, from: Int, to: Int): Float {
        var m = 0f
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(v.size)) if (v[i] > m) m = v[i]
        return m
    }

    @Test
    fun `matches brute force on random ranges of every size`() {
        val rnd = Random(1234)
        for (n in listOf(1, 2, 15, 16, 17, 31, 32, 33, 100, 1000, 4097, 50_000)) {
            val v = FloatArray(n) { rnd.nextFloat() }
            val rmq = PeakRangeMax(v)
            repeat(2_000) {
                val a = rnd.nextInt(0, n + 1)
                val b = rnd.nextInt(0, n + 1)
                val from = minOf(a, b)
                val to = maxOf(a, b)
                assertEquals("n=$n [$from,$to)", bruteMax(v, from, to), rmq.max(from, to), 0f)
            }
        }
    }

    @Test
    fun `exhaustive on a size that crosses several blocks`() {
        val v = FloatArray(77) { ((it * 37) % 101) / 101f }
        val rmq = PeakRangeMax(v)
        for (from in 0..v.size) for (to in from..v.size) {
            assertEquals("[$from,$to)", bruteMax(v, from, to), rmq.max(from, to), 0f)
        }
    }

    @Test
    fun `edge cases - empty array, empty range, clamped range, single spike`() {
        assertEquals(0f, PeakRangeMax(FloatArray(0)).max(0, 10), 0f)
        val v = FloatArray(1000)
        v[777] = 0.9f
        val rmq = PeakRangeMax(v)
        assertEquals(0f, rmq.max(500, 500), 0f)        // empty
        assertEquals(0f, rmq.max(600, 400), 0f)        // inverted
        assertEquals(0.9f, rmq.max(-50, 5000), 0f)     // clamped to array
        assertEquals(0.9f, rmq.max(777, 778), 0f)      // single element
        assertEquals(0f, rmq.max(0, 777), 0f)          // just before the spike
        assertEquals(0f, rmq.max(778, 1000), 0f)       // just after
    }

    /**
     * Not a strict benchmark (JIT, GC), but it documents the algorithmic gap the change closes:
     * drawing a fully zoomed-out 60-minute file (360 000 peaks) as ~200 bars, 600 times
     * (10 s of 60 fps playback).
     */
    @Test
    fun `zoomed-out 60 minute waveform - indexed query is much faster than scanning`() {
        val n = 360_000
        val rnd = Random(7)
        val v = FloatArray(n) { rnd.nextFloat() }
        val rmq = PeakRangeMax(v)
        val bars = 200
        val frames = 600
        val span = n / bars

        fun drawScan(): Float { var s = 0f; for (b in 0 until bars) s += bruteMax(v, b * span, (b + 1) * span); return s }
        fun drawIndexed(): Float { var s = 0f; for (b in 0 until bars) s += rmq.max(b * span, (b + 1) * span); return s }

        repeat(50) { drawScan(); drawIndexed() }  // warm-up / JIT
        var check = 0f
        val t0 = System.nanoTime()
        repeat(frames) { check += drawScan() }
        val scanNs = System.nanoTime() - t0
        val t1 = System.nanoTime()
        repeat(frames) { check -= drawIndexed() }
        val idxNs = System.nanoTime() - t1

        assertEquals(0f, check, 1e-2f)  // identical results
        val speedup = scanNs.toDouble() / idxNs
        println("PeakRangeMax: scan ${scanNs / frames / 1000} us/frame, indexed ${idxNs / frames / 1000} us/frame, speed-up %.1fx".format(speedup))
        assertTrue("expected a large speed-up, got $speedup", speedup > 10)
    }
}
