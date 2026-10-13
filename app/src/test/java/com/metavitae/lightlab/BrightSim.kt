package com.metavitae.lightlab

import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Computer simulation of a brightness-message send, phone to phone. Every number that
 * describes the phones comes from the Oct 11 measurements (flashlight switch times,
 * camera picture rates, rolling-shutter scan, step noise). Results are SIMULATED, not measured.
 */
object BrightSim {
    /** How a phone's flashlight switch behaves (Oct 11 facts: time each switch call took). */
    class Torch(val name: String, val call: (Random) -> Double)
    // POCO: 5.9 +- 1.9 ms at 30 switches/s, a few slow ones (p95 32 ms at 10/s). 8T: about 11 +- 1.7 ms, rare slow one (max 41 ms).
    // POCO: the 1798 real switch times from the Oct 11 flashlight runs (median 6.0 ms, 0.6% over 15 ms, max 18 ms).
    val POCO_CALLS: DoubleArray = BrightSim::class.java.getResourceAsStream("/poco_calls.txt")?.bufferedReader()?.readLines()
        ?.mapNotNull { it.trim().toDoubleOrNull()?.div(1000) }?.toDoubleArray() ?: doubleArrayOf(6e-3)
    val POCO_TORCH = Torch("POCO") { r -> POCO_CALLS[r.nextInt(POCO_CALLS.size)] }
    // 8T: only summaries exist (10-13 ms mean, spread 1.7 ms; one 41 ms call in 20): about 1% slow calls assumed.
    val T8_TORCH = Torch("8T") { r -> if (r.nextDouble() < 0.01) 15e-3 + 26e-3 * r.nextDouble() else max(7e-3, 10.6e-3 + 1.7e-3 * r.gauss()) }

    class Room(val name: String, val swingOverNoise: Double, val bgOverSwing: Double, val flicker: Double, val driftPerSec: Double)
    // Swing/noise per picture on the best zone at 1/30 s exposure (Oct 11 values at 1/15 s, divided by sqrt 2).
    val DIM = Room("dim", 24.0, 0.45, 0.0, 0.002)
    val LIT = Room("lit", 15.0, 14.0, 0.25, 0.004)

    class Cam(val name: String, val fps: Double, val skew: Double)
    val CAM_8T = Cam("8T", 30.11, 0.02697)
    val CAM_POCO = Cam("POCO", 30.00, 0.02821)

    fun Random.gauss(): Double { var u = 0.0; repeat(12) { u += nextDouble() }; return u - 6.0 }

    /** Stress knobs (defaults = plain case). */
    @JvmField var realExp = 0.0325      // exposure the camera really used
    @JvmField var patchWidth = 3.0      // how wide our light's glow is, in blocks
    @JvmField var ppm = 0.0             // sender clock faster than the receiver's, parts per million
    @JvmField var personAt = -1.0       // seconds into the send when someone walks past (-1 = nobody)

    data class Outcome(val ok: Boolean, val bitErrors: Int, val bits: Int, val stage: String, val seconds: Double)

    fun run(text: String, mode: BrightFormat.Mode, torch: Torch, cam: Cam, room: Room, seed: Int, verbose: Boolean = false, snrScale: Double = 1.0): Outcome {
        val r = Random(seed)
        val pass = "LIGHTCOMM"
        val nonce = r.nextInt(65536)
        // Sender: each switch is a call that takes a while; the light changes near its end; calls queue up.
        // The sync part is played first while the sender times its own switches.
        val lead = 1.0 + r.nextDouble()           // receiver starts recording this long before the send
        val effects = ArrayList<Pair<Double, Boolean>>()
        var free = 0.0
        val calls = ArrayList<Double>()
        fun play(list: List<Pair<Double, Boolean>>) {
            for ((t, on) in list) {
                val start = max(t, free)
                val d = torch.call(r)
                calls.add(d)
                effects.add((lead + start + 0.7 * d) * (1 - ppm * 1e-6) to on)
                free = start + d
            }
        }
        val syncW = BrightFormat.syncWave()
        play(BrightFormat.switches(syncW))
        val send = BrightFormat.build(text, pass, nonce, mode, BrightFormat.phoneTag("2312FPCA6G"), BrightFormat.pminMs(calls))
        play(BrightFormat.switches(send.wave, syncW.n, send.wave.n, startState = syncW.on[syncW.n - 1] > 0))
        fun lightOn(a: Double, b: Double): Double {
            var sum = 0.0; var state = false; var last = 0.0
            for ((t, on) in effects) {
                if (t > b) break
                if (state) sum += max(0.0, min(t, b) - max(last, a))
                state = on; last = t
            }
            if (state) sum += max(0.0, b - max(last, a))
            return sum
        }
        // Receiver: 12 row bands x 8 column blocks; our light falls on a patch of them.
        val nb = 12; val nc = 8
        val exp = realExp
        val total = lead + send.wave.end + 1.0
        val phase = r.nextDouble() / cam.fps
        val nFrames = ((total - phase) * cam.fps).toInt()
        val pr = 2 + r.nextInt(6); val pc = 1 + r.nextInt(5)   // patch centre
        val gains = DoubleArray(nb * nc) { i -> val b = i / nc; val c = i % nc; exp(-((b - pr) * (b - pr) / (8.0 * patchWidth / 3) + (c - pc) * (c - pc) / (3.0 * patchWidth / 3))) }
        val swing = 10.0
        val sigma = swing / (room.swingOverNoise * snrScale)
        val bg = DoubleArray(nb * nc) { swing * room.bgOverSwing * (0.7 + 0.6 * r.nextDouble()) }
        val drift = DoubleArray(nb * nc)
        val series = Array(nb * nc + 1) { DoubleArray(nFrames) }
        val times = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            val ts = phase + f / cam.fps
            times[f] = ts
            var whole = 0.0
            for (b in 0 until nb) {
                val a = ts + cam.skew * (b + 0.5) / nb
                val on = lightOn(a, a + exp) / exp
                // mains lamps flicker at 120 per second; an exposure of ~4 full cycles mostly cancels it
                val fl = 1.0 + room.flicker * (sin(2 * PI * 120 * (a + exp)) - sin(2 * PI * 120 * a)) / (2 * PI * 120 * exp)
                for (c in 0 until nc) {
                    val i = b * nc + c
                    drift[i] += room.driftPerSec * swing / cam.fps * r.gauss()
                    // a person walks past: over 0.4 s blocks half our light and a fifth of the room light, for 1.5 s, then over 0.4 s back
                    val u = if (personAt < 0) 0.0 else { val x = ts - lead - personAt; when { x < 0 || x > 2.3 -> 0.0; x < 0.4 -> x / 0.4; x < 1.9 -> 1.0; else -> (2.3 - x) / 0.4 } }
                    val lin = bg[i] * fl * (1 - 0.2 * u) + drift[i] + swing * gains[i] * on * (1 - 0.5 * u)
                    // the camera's brightness curve (not straight), then rounding to whole steps after averaging
                    val y = 255.0 * (max(0.0, lin) / 255.0).pow(0.8) + sigma * r.gauss()
                    series[i][f] = y; whole += y
                }
            }
            series[nb * nc][f] = whole / (nb * nc)
        }
        if (verbose) { BrightDecoder.debugHdr = IntArray(BrightFormat.HDR_SYMS) { k -> if (send.wave.on[BrightFormat.SYNC_SLOTS + k] > 0) 1 else 0 }; BrightDecoder.debugRate = mode.perSec
            println("  shadow from ${personAt} s; header runs ${"%.1f".format(send.wave.start[BrightFormat.SYNC_SLOTS])}..${"%.1f".format(send.wave.start[BrightFormat.SYNC_SLOTS + BrightFormat.HDR_SYMS])} s") }
        val ss = series.map { BrightDecoder.Series(times, it) }
        val dec = BrightDecoder(pass, 0.0325)
        val starts = dec.findStarts(ss.last(), 0.0, lead + 1.0, 0.6).take(3)
        var res: BrightDecoder.Result? = null
        for ((t0, c) in starts) { val x = dec.decode(ss, t0, c); if (res == null || x.ok) res = x; if (x.ok) break }
        val bits = send.bodyCoded.size
        val errs = res?.coded?.takeIf { it.size == bits }?.let { cd -> cd.indices.count { cd[it] != send.bodyCoded[it] } } ?: bits / 2
        if (verbose) println("  starts=${starts.map { "%.3f/%.2f".format(it.first - lead, it.second) }} -> ${res?.stage} ${res?.text} ch=${res?.channels} snr=${"%.1f".format(res?.snr ?: 0.0)} apart=${res?.levelsApart} pmin=${res?.pminMs} ${res?.note}")
        return Outcome(res?.ok == true && res.text == text, errs, bits, res?.stage ?: "staircase not found", send.wave.end)
    }

    @JvmStatic fun main(args: Array<String>) {
        val reps = args.getOrNull(0)?.toInt() ?: 5
        val modes = args.getOrNull(1)?.split(",")?.map { BrightFormat.MODES[it.toInt()] } ?: BrightFormat.MODES
        val scale = args.getOrNull(2)?.toDouble() ?: 1.0
        val verbose = args.getOrNull(3) == "v"
        args.getOrNull(4)?.toDouble()?.let { BrightDecoder.SOFT_CAP = it }
        args.getOrNull(5)?.let { kv -> kv.split(";").forEach { val (k, v) = it.split("="); when (k) {
            "exp" -> realExp = v.toDouble(); "patch" -> patchWidth = v.toDouble(); "ppm" -> ppm = v.toDouble(); "person" -> personAt = v.toDouble(); "env" -> BrightDecoder.ENV_HALF = v.toInt(); "weak" -> BrightDecoder.WEAK_SWING = v.toDouble() } } }
        println("stress: real exposure ${realExp * 1000} ms (decoder assumes 32.5), patch $patchWidth blocks, clock $ppm ppm, person at $personAt s")
        println("SIMULATED (not measured). reps=$reps per cell, noise scale 1/$scale, soft cap ${BrightDecoder.SOFT_CAP}")
        println("mode | room | sender->receiver | right | bit errors before repair | seconds per send (msg 2)")
        for (room in listOf(DIM, LIT)) for ((torch, cam) in listOf(POCO_TORCH to CAM_8T, T8_TORCH to CAM_POCO)) for (mode in modes) {
            var ok = 0; var n = 0; var errs = 0; var bits = 0; var secs = 0.0
            val stages = HashMap<String, Int>()
            for (m in BrightFormat.TEST_MESSAGES.indices) for (k in 0 until reps) {
                val o = run(BrightFormat.TEST_MESSAGES[m], mode, torch, cam, room, 1000 * m + k + 77 * mode.id, verbose, scale)
                n++; if (o.ok) ok++; errs += o.bitErrors; bits += o.bits
                if (m == 1) secs = o.seconds
                if (!o.ok) stages[o.stage] = (stages[o.stage] ?: 0) + 1
            }
            println(String.format(Locale.US, "%-22s | %s | %s->%s | %d/%d | %.2f%% | %.1f s %s", mode.name, room.name, torch.name, cam.name, ok, n, 100.0 * errs / bits, secs,
                if (stages.isEmpty()) "" else stages.toString()))
        }
    }
}
