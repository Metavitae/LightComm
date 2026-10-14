package com.metavitae.lightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick checks of the brightness-message code on a simulated room (not a measurement). */
class BrightSimTest {
    @Test fun gray_codebooks_round_trip() {
        for (m in BrightFormat.MODES) {
            val cb = BrightFormat.Codebook(m)
            for (label in 0 until (1 shl m.bits)) assertEquals(label, cb.decode(cb.encode(label)))
        }
    }

    @Test fun spanish_accents_fit() {
        val b = BrightFormat.utf8(BrightFormat.TEST_MESSAGES[3])
        assertEquals(61, b.size)
        assertEquals(BrightFormat.TEST_MESSAGES[3], String(b, Charsets.UTF_8))
    }

    @Test fun dim_room_reads_every_mode() {
        for (m in BrightFormat.MODES) {
            val o = BrightSim.run(BrightFormat.TEST_MESSAGES[1], m, BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 11, snrScale = 3.0)
            assertTrue("${m.name}: ${o.stage}", o.ok)
        }
    }

    @Test fun packed_text_round_trip_and_shorter() {
        val samples = BrightFormat.TEST_MESSAGES + listOf("Hola, ¿cómo estás? Ñandú ÁRBOL", "hola amigo", "SOS 911", "café ☕ 🙂", "a", "")
        for (t in samples) {
            val p = TextPack.pack(t)
            if (p != null) { assertEquals(t, TextPack.unpack(p)); assertTrue(p.size < BrightFormat.utf8(t).size) }
        }
        // The long test message must get clearly shorter.
        val long = BrightFormat.TEST_MESSAGES[2]
        assertTrue(TextPack.pack(long)!!.size * 10 < BrightFormat.utf8(long).size * 6)
    }

    @Test fun short_reply_found_and_not_confused() {
        for ((torch, cam) in listOf(BrightSim.POCO_TORCH to BrightSim.CAM_8T, BrightSim.T8_TORCH to BrightSim.CAM_POCO)) for (ok in listOf(true, false)) for (seed in 1..3) for (rate in listOf(30, 15, 10)) {
            val (hit, wrong) = BrightSim.reply(ok, torch, cam, BrightSim.LIT, seed, perSec = rate)
            assertTrue("found ${torch.name} ok=$ok seed=$seed", hit.first)
            assertEquals("kind ${torch.name} ok=$ok seed=$seed", ok, hit.second)
            assertTrue("a reply to another message must not match (${"%.2f".format(wrong)})", wrong < 0.7)
        }
    }

    @Test fun short_opening_reads_and_is_shorter() {
        for ((torch, cam) in listOf(BrightSim.POCO_TORCH to BrightSim.CAM_8T, BrightSim.T8_TORCH to BrightSim.CAM_POCO))
            for (m in listOf(0, 5, 6)) for (seq in listOf(1, 3)) for (room in listOf(BrightSim.DIM, BrightSim.LIT)) {
                val mode = BrightFormat.MODES[m]
                val o = BrightSim.run(BrightFormat.TEST_MESSAGES[1], mode, torch, cam, room, 31 * m + seq, seq = seq)
                assertTrue("short ${mode.name} ${torch.name} seq $seq ${room.name}: ${o.stage}", o.ok)
                val full = BrightSim.run(BrightFormat.TEST_MESSAGES[1], mode, torch, cam, room, 31 * m + seq)
                assertTrue("full ${mode.name}: ${full.stage}", full.ok)
                assertTrue("short must be shorter (${o.seconds} vs ${full.seconds})", o.seconds < full.seconds - 3)
            }
    }

    @Test fun short_and_full_syncs_never_confused() {
        val dec = BrightDecoder("LIGHTCOMM", 0.0325)
        for ((short, other) in listOf(true to false, false to true)) {
            val w = BrightFormat.syncWave(other)
            val t = DoubleArray(150) { it / 30.0 + 0.2 }
            val y = DoubleArray(t.size) { i -> val a = t[i] - 0.5; if (a < 0 || a >= w.end) 0.0 else 100 * w.light(a, a + 0.0325) / 0.0325 }
            val hits = dec.findStarts(BrightDecoder.Series(t, y), 0.0, 3.0, 0.6, short = short)
            assertTrue("short=$short found in the other sync: $hits", hits.isEmpty())
        }
    }

    @Test fun short_pictures_in_glare_read() {
        try {
            BrightSim.decExpFollows = true
            for (e in listOf(BrightListener.MIN_EXP)) for (m in listOf(5, 6)) for (seq in listOf(0, 2)) {
                BrightSim.realExp = e
                val o = BrightSim.run(BrightFormat.TEST_MESSAGES[1], BrightFormat.MODES[m], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.LIT, 7 * m + seq, seq = seq)
                assertTrue("exposure ${e * 1000} ms mode $m seq $seq: ${o.stage}", o.ok)
            }
        } finally { BrightSim.decExpFollows = false; BrightSim.realExp = 0.0325 }
    }

        @Test fun print_grid() { BrightSim.main(arrayOf("1")) }
}
