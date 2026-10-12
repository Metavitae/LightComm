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

    @Test fun print_grid() { BrightSim.main(arrayOf("1")) }
}
