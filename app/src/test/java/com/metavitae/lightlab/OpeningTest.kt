package com.metavitae.lightlab

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Oct 19: how short can the short opening be? Three openings, both phones using the same:
 * A = today (31-step pattern, Barker), B = no Barker, C = 15-step pattern and no Barker.
 * A 2-byte message stands in for one table number. Results are SIMULATED, not measured.
 */
class OpeningTest {
    private val variants = listOf(Triple("A today", false, true), Triple("B no Barker", false, false), Triple("C 15 steps, no Barker", true, false))
    private fun use(v: Triple<String, Boolean, Boolean>) { BrightFormat.shortSync15 = v.second; BrightFormat.shortBarker = v.third }

    @Test fun shorter_openings_read_and_time() {
        val texts = listOf("OK", "A1", "B2", "C3", "SI", "NO")
        try {
            for (v in variants) {
                use(v)
                for (m in listOf(0, 1, 5, 2)) {
                    val mode = BrightFormat.MODES[m]
                    val row = StringBuilder()
                    var secs = 0.0
                    for ((scale, far) in listOf(1.0 to "near", 0.35 to "far")) {
                        var ok = 0; var n = 0
                        for (room in listOf(BrightSim.DIM, BrightSim.LIT)) for ((torch, cam) in listOf(BrightSim.POCO_TORCH to BrightSim.CAM_8T, BrightSim.T8_TORCH to BrightSim.CAM_POCO))
                            for (k in texts.indices) {
                                val o = BrightSim.run(texts[k], mode, torch, cam, room, 100 * m + 7 * k + (if (far == "far") 5000 else 0), snrScale = scale, seq = 1 + k % 3)
                                n++; if (o.ok) ok++; secs = o.seconds
                            }
                        row.append(" | $far $ok/$n")
                    }
                    println(String.format(Locale.US, "%-22s | %-22s | %.2f s%s", v.first, mode.name, secs, row))
                }
            }
        } finally { BrightFormat.resetKnobs() }
    }

    /** Nothing sent: how often does the room alone look like a short opening (the receiver then tries a header)? */
    @Test fun controls_nothing_sent() {
        try {
            for (v in listOf(variants[0], variants[2])) {
                use(v)
                val dec = BrightDecoder("LIGHTCOMM", 0.0325)
                for (room in listOf(BrightSim.DIM, BrightSim.LIT)) {
                    var hits = 0; var best = 0.0; val secs = 300.0
                    for (seed in 1..2) {
                        val ss = BrightSim.film({ _, _ -> 0.0 }, 0.0, secs, BrightSim.CAM_8T, room, Random(seed), 1.0)
                        for (s in ss.filterIndexed { i, _ -> i % 8 == 0 || i == ss.size - 1 }) {
                            hits += dec.findStarts(s, 0.0, secs - 2, 0.8, short = true).size
                            best = maxOf(best, dec.findStarts(s, 0.0, secs - 2, 0.3, short = true).firstOrNull()?.second ?: 0.0)
                        }
                    }
                    println(String.format(Locale.US, "%-22s | %s room, 2 x %.0f s, 13 channels | false openings %d | best match %.2f (needs 0.80)", v.first, room.name, secs, hits, best))
                }
            }
        } finally { BrightFormat.resetKnobs() }
    }

    /**
     * Why the 15-step opening was dropped (Oct 19): some "could not read" replies at 10 per second match it at 0.81-0.83
     * (a reader needs 0.80), and a lit room alone gave 8 false openings in 10 minutes (controls test). 31 steps: none.
     */
    @Test fun short15_confused_so_dropped() {
        try {
            use(variants[2])
            val dec = BrightDecoder("LIGHTCOMM", 0.0325)
            fun scan(w: BrightFormat.Wave, what: String): Double {
                val t = DoubleArray(((w.end + 2) * 30).toInt()) { it / 30.0 + 0.2 }
                val y = DoubleArray(t.size) { i -> val a = t[i] - 0.5; if (a < 0 || a >= w.end) 0.0 else 100 * w.light(a, a + 0.0325) / 0.0325 }
                val best = dec.findStarts(BrightDecoder.Series(t, y), 0.0, w.end, 0.3, short = true).firstOrNull()?.second ?: 0.0
                println(String.format(Locale.US, "15-step opening inside %s: best match %.2f", what, best))
                return best
            }
            var worst = scan(BrightFormat.syncWave(false), "the full opening")
            for (n in listOf(1, 777, 4242)) for (rate in listOf(30, 15, 10)) for (ok in listOf(true, false))
                worst = maxOf(worst, scan(BrightFormat.replyWave("LIGHTCOMM", n, ok, rate), "reply nonce $n $rate/s ok=$ok"))
            worst = maxOf(worst, scan(BrightFormat.aimWave(15, 6.0), "the aiming pattern"))
            assertTrue("15-step opening no longer confused (best match %.2f): look again".format(worst), worst >= 0.8)
            // and the other way round: a full opening is not found in the 15-step one
            val w = BrightFormat.syncWave(true)
            val t = DoubleArray(150) { it / 30.0 + 0.2 }
            val y = DoubleArray(t.size) { i -> val a = t[i] - 0.5; if (a < 0 || a >= w.end) 0.0 else 100 * w.light(a, a + 0.0325) / 0.0325 }
            assertTrue(dec.findStarts(BrightDecoder.Series(t, y), 0.0, 3.0, 0.6, short = false).isEmpty())
        } finally { BrightFormat.resetKnobs() }
    }
}
