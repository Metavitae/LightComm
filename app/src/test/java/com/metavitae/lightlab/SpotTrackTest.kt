package com.metavitae.lightlab

import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 6: a far phone held in hand (simulated). The follow-the-spot series must read more, and never break a still light. */
class SpotTrackTest {
    private val text = "C1 FIRST HELLO FROM FAR AWAY, HELD IN HAND"
    private fun rate(mode: Int, wander: Double, shake: Double, follow: Boolean, n: Int, scale: Double): Int {
        var ok = 0
        try {
            BrightSim.patchWidth = 0.6; BrightSim.wander = wander; BrightSim.shake = shake; BrightSim.follow = follow
            for (seed in 1..n) if (BrightSim.run(text, BrightFormat.MODES[mode], BrightSim.POCO_TORCH, BrightSim.CAM_8T, BrightSim.DIM, 700 + seed, snrScale = scale).ok) ok++
        } finally { BrightSim.patchWidth = 3.0; BrightSim.wander = 0.0; BrightSim.shake = 0.0; BrightSim.follow = false }
        return ok
    }

    @Test fun following_the_spot_reads_more_when_held() {
        var before = 0; var after = 0; var stillBefore = 0; var stillAfter = 0
        for (mode in listOf(0, 6)) for (scale in listOf(1.0, 0.5)) {
            val n = 8
            val s0 = rate(mode, 0.0, 0.0, false, n, scale); val s1 = rate(mode, 0.0, 0.0, true, n, scale)
            val h0 = rate(mode, 3.0, 0.4, false, n, scale); val h1 = rate(mode, 3.0, 0.4, true, n, scale)
            println("mode $mode scale $scale: still $s0 -> $s1 of $n; held (wander 3, shake 0.4) $h0 -> $h1 of $n")
            stillBefore += s0; stillAfter += s1; before += h0; after += h1
        }
        println("TOTAL still $stillBefore -> $stillAfter; held $before -> $after")
        assertTrue("a still light must not read worse ($stillBefore -> $stillAfter)", stillAfter >= stillBefore)
        assertTrue("held in hand must read better ($before -> $after)", after > before)
    }
}
