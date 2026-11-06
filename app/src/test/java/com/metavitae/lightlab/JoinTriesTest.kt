package com.metavitae.lightlab

import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 6: a failed try of a message is joined with the next try of the same message (same nonce). Simulated room. */
class JoinTriesTest {
    private fun stats(scale: Double, mode: Int, pairs: Int): IntArray {
        // [single tries read, pairs where both singles failed, of those read when joined, wrong texts]
        val out = IntArray(4)
        val text = "~c1/3 E1 LONG MESSAGE. WE ARE AT THE NORTH GATE WITH TWO PEOPLE, ONE HAS A HURT "
        for (p in 0 until pairs) {
            synchronized(BrightDecoder.EARLIER) { BrightDecoder.EARLIER.clear() }
            val nonce = 1000 + p * 37
            val a = BrightSim.run(text, BrightFormat.MODES[mode], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 500 + 2 * p, snrScale = scale, nonceIs = nonce)
            if (a.ok) out[0]++
            if (!a.ok && a.stage != "check byte wrong") continue
            val kept = synchronized(BrightDecoder.EARLIER) { BrightDecoder.EARLIER.containsKey(nonce) }
            val b = BrightSim.run(text, BrightFormat.MODES[mode], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 501 + 2 * p, snrScale = scale, nonceIs = nonce)
            if (b.ok && !kept) out[0]++
            if (!a.ok && kept) {
                // would b alone have read? run it again with nothing kept
                synchronized(BrightDecoder.EARLIER) { BrightDecoder.EARLIER.clear() }
                val bAlone = BrightSim.run(text, BrightFormat.MODES[mode], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 501 + 2 * p, snrScale = scale, nonceIs = nonce)
                if (!bAlone.ok) { out[1]++; if (b.ok) out[2]++ }
                if (!b.ok && b.stage == "read") out[3]++
            }
        }
        return out
    }

    @Test fun joined_tries_read_what_single_tries_miss() {
        var tough = 0; var saved = 0
        for (scale in listOf(0.12, 0.09, 0.07, 0.05)) for (mode in listOf(0, 6)) {
            val s = stats(scale, mode, 12)
            println("scale $scale mode $mode: singles read ${s[0]}, both failed ${s[1]}, joined read ${s[2]}, wrong ${s[3]}")
            tough += s[1]; saved += s[2]
            assertTrue("a joined read gave a wrong text", s[3] == 0)
        }
        println("TOTAL both failed $tough, joined read $saved")
        assertTrue("joining should rescue some ($saved of $tough)", tough == 0 || saved > 0)
    }
}
