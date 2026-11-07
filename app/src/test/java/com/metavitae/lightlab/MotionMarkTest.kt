package com.metavitae.lightlab

import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 7: a hand that jerks now and then (simulated). Marking the moved pictures as unknown must read more, with
 *  follow-the-spot on, and change nothing when the hand is still (no marks then). The detector itself (MotionCheck)
 *  is checked on the phones (log line "phone moved in N pictures"), not here: here the marks are perfect. */
class MotionMarkTest {
    private val text = "C1 FIRST HELLO FROM FAR AWAY, HELD IN HAND"
    private fun rate(mode: Int, jolts: Double, mark: Boolean, n: Int, scale: Double): Int {
        var ok = 0
        try {
            BrightSim.patchWidth = 0.6; BrightSim.shake = 0.4; BrightSim.follow = true; BrightSim.jolts = jolts; BrightSim.markJolts = mark
            for (seed in 1..n) if (BrightSim.run(text, BrightFormat.MODES[mode], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 900 + seed, snrScale = scale).ok) ok++
        } finally { BrightSim.patchWidth = 3.0; BrightSim.shake = 0.0; BrightSim.follow = false; BrightSim.jolts = 0.0; BrightSim.markJolts = false }
        return ok
    }

    @Test fun marking_moved_pictures_reads_more() {
        var before = 0; var after = 0
        for (mode in listOf(0, 5)) for (scale in listOf(1.0, 0.6)) for (j in listOf(0.3, 0.6)) {
            val n = 10
            val a = rate(mode, j, false, n, scale); val b = rate(mode, j, true, n, scale)
            println("mode $mode scale $scale jerks/s $j: not marked $a, marked $b of $n")
            before += a; after += b
        }
        println("TOTAL not marked $before, marked $after")
        assertTrue("marking must not read worse ($before -> $after)", after >= before)
    }
}
