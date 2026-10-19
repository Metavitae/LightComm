package com.metavitae.lightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Oct 18 light test: a send stopped by the person after its header was read kept the receiver waiting for its planned
 * end, so the next message (12 s after the stop) was missed. The real receiver (BrightListener) is fed camera pictures
 * made from flashlight switches: a stopped send must be dropped without a reply, and the next one read.
 * Results are SIMULATED, not measured.
 */
class StopDropTest {
    private val pass = "LIGHTCOMM"
    private val fps = 30.0
    private val exp = 0.0325

    /** Light on-share during [a, b] from a list of (time, on) switches. */
    private fun onShare(sw: List<Pair<Double, Boolean>>, a: Double, b: Double): Double {
        var sum = 0.0; var state = false; var last = 0.0
        for ((t, on) in sw) {
            if (t > b) break
            if (state) sum += maxOf(0.0, minOf(t, b) - maxOf(last, a))
            state = on; last = t
        }
        if (state) sum += maxOf(0.0, b - maxOf(last, a))
        return sum / (b - a)
    }

    /** Switches of one full-opening send starting at t0, cut off at stopAt (seconds into it; -1 = not stopped). */
    private fun send(text: String, mode: BrightFormat.Mode, nonce: Int, t0: Double, stopAt: Double = -1.0): List<Pair<Double, Boolean>> {
        val sync = BrightFormat.syncWave(false)
        val s = BrightFormat.build(text, pass, nonce, mode, BrightFormat.phoneTag("KB2007"), 12)
        val all = BrightFormat.switches(sync) + BrightFormat.switches(s.wave, sync.n, s.wave.n, startState = sync.on[sync.n - 1] > 0)
        val cut = if (stopAt < 0) all else all.filter { it.first < stopAt } + listOf(stopAt to false)
        return (cut + listOf(s.wave.end to false)).map { (t, on) -> t0 + t to on }
    }

    /** Films the switches (light on a 4x4 patch of the 64x48 grid), scanning like the talk loop does; returns log lines and results read. */
    private fun film(sw: List<Pair<Double, Boolean>>, until: Double, seed: Int): Pair<List<String>, List<BrightDecoder.Result>> {
        val r = Random(seed)
        val rx = BrightListener(android.content.ContextWrapper(null), pass, exp)
        rx.readyAt = 1.0
        val lines = ArrayList<String>(); val got = ArrayList<BrightDecoder.Result>()
        val zx = CamSession.ZX; val zy = CamSession.ZY
        var t = 0.0; var nextScan = 1.7
        val sorted = sw.sortedBy { it.first }
        while (t < until) {
            val share = onShare(sorted, t, t + exp)
            val fine = FloatArray(zx * zy) { 30f + 1.5f * (r.nextFloat() - 0.5f) }
            for (y in 20 until 24) for (x in 30 until 34) fine[y * zx + x] += (120 * share).toFloat()
            rx.add((t * 1e9).toLong(), fine)
            if (t >= nextScan) {
                lines += rx.scan()
                while (true) got += rx.heard.poll() ?: break
                nextScan += 0.7
            }
            t += 1 / fps
        }
        return lines to got
    }

    @Test fun stopped_send_dropped_and_next_read() {
        val mode = BrightFormat.MODES[6]   // 2 levels, 10 per second (the Oct 18 case)
        val first = send("THIS ONE GETS STOPPED WHILE BLINKING", mode, 1111, 2.0, stopAt = 18.0)
        val stoppedAt = 2.0 + 18.0
        val second = send("AFTER A REAL STOP", mode, 2222, stoppedAt + 12.0)
        val (lines, got) = film(first + second, second.last().first + 4.0, 1)
        lines.forEach { print(it) }
        assertTrue("stop noticed", lines.any { it.contains("stopped by the sender") })
        assertEquals("only the second message read", listOf("AFTER A REAL STOP"), got.filter { it.ok }.map { it.text })
        assertTrue("no 'could not read' for the stopped one", got.none { !it.ok && it.nonce == 1111 })
    }

    @Test fun whole_sends_never_dropped() {
        // Every on/off mode, several texts: a send that doesn't stop must never be taken for a stopped one.
        var n = 0
        for (m in listOf(0, 5, 6, 7)) for (seed in 1..4) {
            val mode = BrightFormat.MODES[m]
            val text = listOf("WE ARE AT THE NORTH GATE. ALL GOOD", "OK", "CAN YOU BRING THE TRUCK TO THE ROAD?", "0000000000 ........").get(seed - 1)
            val sw = send(text, mode, 3000 + seed, 2.0)
            val (lines, got) = film(sw, sw.last().first + 4.0, seed)
            assertTrue("mode $m seed $seed not dropped", lines.none { it.contains("stopped by the sender") })
            assertEquals("mode $m seed $seed read", listOf(text), got.filter { it.ok }.map { it.text })
            n++
        }
        println("$n whole sends read, none dropped")
    }
}
