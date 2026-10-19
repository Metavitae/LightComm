package com.metavitae.lightlab

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Oct 19: bounced off the ceiling, the POCO found the 8T's replies at 0.82-0.87 and missed about 1 in 4 just under 0.80.
 * New rule (BrightDecoder.replyKind): 0.80 as before, or 0.70 when that kind clearly beats the other kind.
 * A false "got it" (a reply to another message, the room alone, the other phone's message) must never be taken.
 * Results are SIMULATED, not measured.
 */
class ReplyTest {
    private val pass = "LIGHTCOMM"
    private val dec = BrightDecoder(pass, 0.0325)

    /** Films a light pattern (switch list) and returns, over every channel, the decision and the best match. */
    private fun look(sw: List<Pair<Double, Boolean>>, end: Double, nonce: Int, rate: Int, room: BrightSim.Room, scale: Double, seed: Int): Pair<Boolean?, Double> {
        val r = Random(seed)
        fun lightOn(a: Double, b: Double): Double {
            var sum = 0.0; var state = false; var last = 0.0
            for ((t, on) in sw) { if (t > b) break; if (state) sum += max(0.0, min(t, b) - max(last, a)); state = on; last = t }
            if (state) sum += max(0.0, b - max(last, a))
            return sum
        }
        val ss = BrightSim.film(::lightOn, 1.0, end, BrightSim.CAM_POCO, room, r, scale)
        val replyLen = BrightFormat.replyWave(pass, nonce, true, rate).end
        var kind: Boolean? = null; var kindBest = 0.0; var near = 0.0
        for (s in ss) {
            val g = dec.replyMatch(s, 0.0, end - replyLen - 0.2, nonce, true, rate).second
            val c = dec.replyMatch(s, 0.0, end - replyLen - 0.2, nonce, false, rate).second
            near = max(near, max(g, c))
            val k = BrightDecoder.replyKind(g, c)
            if (k != null && max(g, c) > kindBest) { kind = k; kindBest = max(g, c) }
        }
        return kind to near
    }

    private fun play(w: BrightFormat.Wave, at: Double, r: Random, torch: BrightSim.Torch = BrightSim.T8_TORCH): List<Pair<Double, Boolean>> {
        var free = 0.0
        return BrightFormat.switches(w).map { (t, on) -> val st = max(t, free); val d = torch.call(r); free = st + d; at + st + 0.7 * d to on }
    }

    @Test fun weak_replies_found_and_nothing_false_taken() {
        var falseTaken = 0; var falseBest = 0.0
        for (rate in listOf(30, 15, 10)) for ((scale, what) in listOf(0.25 to "weak", 0.18 to "very weak")) {
            var oldFound = 0; var newFound = 0; var wrongKind = 0; var n = 0
            for (seed in 1..20) for (ok in listOf(true, false)) {
                val r = Random(seed * 7 + rate)
                val nonce = r.nextInt(65536)
                val w = BrightFormat.replyWave(pass, nonce, ok, rate)
                val (k, near) = look(play(w, 1.0 + r.nextDouble(), r), w.end + 3.0, nonce, rate, BrightSim.LIT, scale, seed)
                n++; if (near >= 0.8) oldFound++
                if (k != null) { newFound++; if (k != ok) wrongKind++ }
            }
            println(String.format(Locale.US, "real reply %2d/s, %-9s | found: old rule %2d/%d, new rule %2d/%d | wrong kind %d", rate, what, oldFound, n, newFound, n, wrongKind))
            assertEquals("wrong kind ($rate/s $what)", 0, wrongKind)
        }
        // Things that are NOT the reply we wait for: another message's reply, the room alone, the other phone's message.
        for (rate in listOf(30, 15, 10)) for (room in listOf(BrightSim.DIM, BrightSim.LIT)) for (seed in 1..20) {
            val r = Random(seed * 31 + rate)
            val nonce = r.nextInt(65536)
            val others = listOf(
                "reply to another message" to play(BrightFormat.replyWave(pass, nonce xor (1 + r.nextInt(65535)), r.nextBoolean(), rate), 1.5, r),
                "room alone" to emptyList(),
                "the other phone's message" to play(BrightFormat.build("C" + seed, pass, r.nextInt(65536), BrightFormat.MODES.first { it.perSec == rate }, 3, 12).wave, 1.0, r))
            for ((what, sw) in others) for (scale in listOf(1.0, 0.25)) {
                val (k, near) = look(sw, 10.0, nonce, rate, room, scale, seed)
                falseBest = max(falseBest, near)
                if (k != null) { falseTaken++; println("FALSE REPLY TAKEN: $what, $rate/s, ${room.name}, scale $scale, seed $seed, match ${"%.2f".format(near)}") }
            }
        }
        println(String.format(Locale.US, "not-the-reply cases: %d x 3 kinds x 2 strengths, best match %.2f, taken %d", 3 * 2 * 20, falseBest, falseTaken))
        assertEquals("a false reply was taken", 0, falseTaken)
    }
}
