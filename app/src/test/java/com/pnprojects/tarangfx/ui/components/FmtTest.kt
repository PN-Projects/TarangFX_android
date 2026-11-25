package com.pnprojects.tarangfx.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test
    fun `time with tenths`() {
        assertEquals("00:00.0", Fmt.timeTenths(0))
        assertEquals("00:11.6", Fmt.timeTenths(11_650))
        assertEquals("59:59.9", Fmt.timeTenths(3_599_999))
        assertEquals("1:00:00.0", Fmt.timeTenths(3_600_000))
        assertEquals("1:02:03.4", Fmt.timeTenths(3_723_456))
        assertEquals("00:01.5", Fmt.timeTenths(-1_500))  // magnitude only
    }

    @Test
    fun `time without tenths`() {
        assertEquals("00:37", Fmt.time(37_999))
        assertEquals("10:00", Fmt.time(600_000))
        assertEquals("2:00:00", Fmt.time(7_200_000))
    }

    @Test
    fun `sizes and rates`() {
        assertEquals("512 B", Fmt.size(512))
        assertEquals("1.50 KB", Fmt.size(1_536))
        assertEquals("1.00 MB", Fmt.size(1_048_576))
        assertEquals("2.00 GB", Fmt.size(2_147_483_648))
        assertEquals("Original", Fmt.rate(0))
        assertEquals("48 kHz", Fmt.rate(48_000))
        assertEquals("44.10 kHz", Fmt.rate(44_100))
        assertEquals("44.1kHz", Fmt.rateShort(44_100))
        assertEquals("16kHz", Fmt.rateShort(16_000))
    }
}
