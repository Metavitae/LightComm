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
        for ((torch, cam) in listOf(BrightSim.POCO_TORCH to BrightSim.CAM_8T, BrightSim.T8_TORCH to BrightSim.CAM_POCO)) for (ok in listOf(true, false)) for (seed in 1..3) {
            val (hit, wrong) = BrightSim.reply(ok, torch, cam, BrightSim.LIT, seed)
            assertTrue("found ${torch.name} ok=$ok seed=$seed", hit.first)
            assertEquals("kind ${torch.name} ok=$ok seed=$seed", ok, hit.second)
            assertTrue("a reply to another message must not match (${"%.2f".format(wrong)})", wrong < 0.7)
        }
    }

    @Test fun print_grid() { BrightSim.main(arrayOf("1")) }
}
