package com.metavitae.lightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Oct 29: the "us" beacon. Each chat's 63-step pattern must match only itself: not itself shifted (a false start),
 * not another chat's pattern, not Find. The phone says "near" at 0.7. Pattern maths only, not a measurement.
 */
class BeaconTest {
    private fun best(a: IntArray, b: IntArray) = (0 until a.size).maxOf { BrightFormat.circCorr(a, b, it) }

    @Test fun patternsStayApart() {
        val keys = (1..30).map { "KEY WORDS $it" } + listOf("LIGHTCOMM", "LIGHTCOMM FACE TO FACE")
        val pats = keys.map { BrightFormat.beaconPattern(MessageFormat.norm(it)) }
        pats.forEach { assertEquals(BrightFormat.BEACON_SLOTS, it.size) }
        val find = IntArray(BrightFormat.BEACON_SLOTS) { BrightFormat.SHORT_SYNC[it % BrightFormat.SHORT_SYNC.size] }
        val self = pats.maxOf { p -> (1 until p.size).maxOf { BrightFormat.circCorr(p, p, it) } }
        val vsFind = pats.maxOf { best(it, find) }
        var other = 0.0
        for (i in pats.indices) for (j in i + 1 until pats.size) other = maxOf(other, best(pats[i], pats[j]))
        println(String.format(Locale.US, "beacon: worst self-shift %.2f, worst vs Find %.2f, worst between chats %.2f", self, vsFind, other))
        assertTrue("shifted pattern too close: $self", self < 0.4)
        assertTrue("too close to Find: $vsFind", vsFind < 0.4)
        assertTrue("two chats too close: $other", other < 0.6)
        // The same key words give the same pattern (both phones work it out on their own).
        assertTrue(BrightFormat.beaconPattern("LIGHTCOMM").contentEquals(pats[30]))
    }

    /** A ready-made message goes as 2 bytes (the sign and one letter): shorter on light than a phrase spelled out. */
    @Test fun readyMadeIsShorter() {
        val mode = BrightFormat.MODES[0]
        fun sec(t: String) = BrightFormat.build(t, "LIGHTCOMM", 1234, mode, 3, 20, BrightFormat.Link(1, 3, 20, 0), 1).wave.end
        val code = sec("\u001e" + (0x21 + 5).toChar())
        val words = sec("Need help, urgent")
        println(String.format(Locale.US, "ready-made: one code %.1f s, 'Need help, urgent' spelled %.1f s", code, words))
        assertTrue(code < words)
    }
}
