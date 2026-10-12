package com.metavitae.lightlab

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calibration: how many brightness steps can the receiver tell apart, here
 * and now? The flashlight is on/off only, so a step is made by keeping it on
 * for part of every camera picture (the camera adds up the light it gets).
 *
 * Sender: 4 s beacon (passphrase rhythm), 1 s dark, then the staircase
 * off, 1/4, 1/2, 3/4, full, each held 1.5 s, played twice, then dark.
 * Receiver: pictures last exactly one on/off period, so every picture
 * gets the same share of light whatever the timing.
 */
object CalibFormat {
    const val BEACON_SEC = 4.0
    const val GAP_SEC = 1.0
    const val STEP_SEC = 1.5
    val LEVELS = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
    const val ROUNDS = 2
    const val PERIOD_SEC = 1.0 / 15   // one on/off cycle = one receiver picture (15 pictures per second)

    val totalSec get() = BEACON_SEC + GAP_SEC + ROUNDS * LEVELS.size * STEP_SEC

    /** Expected brightness share (0..1) at time t from the start of the send. Beacon counts as 0.5 on average. */
    fun expected(t: Double, beaconHz: Double): Double? = when {
        t < 0 -> 0.0
        t < BEACON_SEC -> null  // beacon: skip in matching
        t < BEACON_SEC + GAP_SEC -> 0.0
        t < totalSec -> LEVELS[(((t - BEACON_SEC - GAP_SEC) / STEP_SEC).toInt()) % LEVELS.size]
        else -> 0.0
    }
}

class CalibSender(private val ctx: Context) {
    @Volatile var running = false

    fun run(pass: String, progress: (String) -> Unit): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val (id, _) = torchCamera(ctx) ?: return "This phone has no flashlight an app can use."
        val hz = MessageFormat.beaconHz(pass)
        // Build the switch list (seconds from start, on/off).
        val sched = ArrayList<Pair<Double, Boolean>>()
        var t = 0.0
        val half = 1.0 / (2 * hz)
        var on = true
        while (t < CalibFormat.BEACON_SEC - 1e-9) { sched.add(t to on); on = !on; t += half }
        sched.add(CalibFormat.BEACON_SEC to false)
        t = CalibFormat.BEACON_SEC + CalibFormat.GAP_SEC
        repeat(CalibFormat.ROUNDS) {
            for (lvl in CalibFormat.LEVELS) {
                val end = t + CalibFormat.STEP_SEC
                var p = t
                while (p < end - 1e-9) {
                    when {
                        lvl <= 0.0 -> sched.add(p to false)
                        lvl >= 1.0 -> sched.add(p to true)
                        else -> { sched.add(p to true); sched.add(p + lvl * CalibFormat.PERIOD_SEC to false) }
                    }
                    p += CalibFormat.PERIOD_SEC
                }
                t = end
            }
        }
        sched.add(t to false)

        val sb = StringBuilder(Results.header("Calibration sender"))
        sb.append("Beacon ${hz} flashes/s for ${CalibFormat.BEACON_SEC} s, then steps ${CalibFormat.LEVELS.joinToString()} of each ${(CalibFormat.PERIOD_SEC * 1000).toInt()} ms, ${CalibFormat.STEP_SEC} s each, ${CalibFormat.ROUNDS} rounds\n")
        val late = ArrayList<Double>()
        val callUs = ArrayList<Double>()
        running = true
        val start = System.nanoTime() + 300_000_000
        var state: Boolean? = null
        var failure: String? = null
        for ((at, want) in sched) {
            if (!running) break
            val target = start + (at * 1e9).toLong()
            while (true) {
                val left = target - System.nanoTime()
                if (left <= 0) break
                if (left > 2_000_000) Thread.sleep((left - 1_500_000) / 1_000_000) else Thread.onSpinWait()
            }
            if (want == state) continue
            val t0 = System.nanoTime()
            try { cm.setTorchMode(id, want) } catch (e: Exception) { failure = e.message; break }
            late.add((t0 - target) / 1000.0)
            callUs.add((System.nanoTime() - t0) / 1000.0)
            state = want
            progress("Calibrating... ${"%.0f".format(at)} of ${"%.0f".format(CalibFormat.totalSec)} s")
        }
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        running = false
        sb.append(if (failure == null) "Finished.\n" else "STOPPED: $failure\n")
        sb.append("Started late (us): ${Stats(late).line("")}\nSwitch took (us): ${Stats(callUs).line("")}\n")
        return sb.toString()
    }
}

/** Collects pictures and works out how many brightness steps can be told apart. */
class CalibReceiver(private val pass: String) {
    private val times = ArrayList<Double>()
    private val grids = ArrayList<FloatArray>()
    private val lock = Any()

    fun add(tsNs: Long, fine: FloatArray) = synchronized(lock) {
        if (times.size < 15 * 120) { times.add(tsNs / 1e9); grids.add(fine.copyOf()) }
    }
    fun count() = synchronized(lock) { times.size }

    private fun power(t: List<Double>, x: List<Double>, hz: Double): Double {
        val m = x.average()
        var c = 0.0; var s = 0.0
        for (i in t.indices) { c += (x[i] - m) * cos(2 * PI * hz * t[i]); s += (x[i] - m) * sin(2 * PI * hz * t[i]) }
        return c * c + s * s
    }

    fun analyze(): String {
        val (t, g) = synchronized(lock) { ArrayList(times) to ArrayList(grids) }
        if (t.size < 15 * 15) return "Too short: only ${t.size} pictures.\n"
        val sb = StringBuilder()
        val hz = MessageFormat.beaconHz(pass)
        val zx = CamSession.ZX
        val nf = zx * CamSession.ZY
        // Channels: every small spot, the 8x6 big zones, the whole picture.
        fun chan(kind: Int, idx: Int) = g.map { Listener.channel(it, kind, idx).toDouble() }
        val whole = chan(2, 0)

        // 1) Find where the sender is: strongest beacon rhythm.
        var bestSpot = 0; var bestSpotP = -1.0
        for (i in 0 until nf) { val p = power(t, g.map { it[i].toDouble() }, hz) / (1e-9 + g.map { it[i].toDouble() }.let { v -> val m = v.average(); v.sumOf { (it - m) * (it - m) } }); if (p > bestSpotP) { bestSpotP = p; bestSpot = i } }
        var bestZone = 0; var bestZoneP = -1.0
        for (i in 0 until 48) { val v = chan(1, i); val p = power(t, v, hz) / (1e-9 + v.let { x -> val m = x.average(); x.sumOf { (it - m) * (it - m) } }); if (p > bestZoneP) { bestZoneP = p; bestZone = i } }
        val channels = listOf("best small spot" to chan(0, bestSpot), "best big zone" to chan(1, bestZone), "whole picture" to whole)

        // 2) Line up the staircase in time (search the start that best matches the expected shape).
        val t0s = t.first()
        var bestStart = 0.0; var bestCorr = -2.0
        val ref = channels[1].second
        var cand = t0s
        while (cand < t.last() - CalibFormat.totalSec + 0.5) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in t.indices) { val e = CalibFormat.expected(t[i] - cand, hz) ?: continue; xs.add(e); ys.add(ref[i]) }
            val c = corr(xs, ys)
            if (c > bestCorr) { bestCorr = c; bestStart = cand }
            cand += 1.0 / 30
        }
        sb.append("Staircase found ${"%.1f".format(bestStart - t0s)} s into the recording (match ${"%.2f".format(bestCorr)}, 1.00 = perfect)\n")

        // 3) Background before the sender started: noise and rhythms already in the room.
        val bgIdx = t.indices.filter { t[it] < bestStart - 0.3 }
        if (bgIdx.size >= 30) {
            val bt = bgIdx.map { t[it] }; val bv = bgIdx.map { whole[it] }
            val st = Stats(bv)
            val peaks = (1..28).map { it * 0.5 }.map { f -> f to power(bt, bv, f) }.sortedByDescending { it.second }.take(3)
            sb.append("Background (${"%.1f".format(bt.last() - bt.first())} s before sending): whole-picture brightness ${"%.1f".format(st.mean)}, noise ${"%.2f".format(st.std)}\n")
            sb.append("Strongest rhythms already in the room: ${peaks.joinToString { "${it.first} /s" }}\n")
        } else sb.append("Background: not enough pictures before the sender started.\n")

        // 4) Per channel: brightness of each step, and how many steps can be told apart.
        var bestN = 0
        for ((name, v) in channels) {
            sb.append("\n$name:\n")
            val means = DoubleArray(CalibFormat.LEVELS.size)
            val stds = DoubleArray(CalibFormat.LEVELS.size)
            for ((li, lvl) in CalibFormat.LEVELS.withIndex()) {
                val vals = ArrayList<Double>()
                for (r in 0 until CalibFormat.ROUNDS) {
                    val s0 = bestStart + CalibFormat.BEACON_SEC + CalibFormat.GAP_SEC + (r * CalibFormat.LEVELS.size + li) * CalibFormat.STEP_SEC
                    for (i in t.indices) if (t[i] > s0 + 0.3 && t[i] < s0 + CalibFormat.STEP_SEC - 0.2) vals.add(v[i])
                }
                val s = Stats(vals)
                means[li] = s.mean; stds[li] = s.std
                sb.append(String.format(Locale.US, "  step %.2f: brightness %.2f, spread %.2f (%d pictures)\n", lvl, s.mean, s.std, s.n))
            }
            // Count steps in a chain where each is clearly above the previous (gap > 3 x the spreads).
            var n = 1
            var last = 0
            for (li in 1 until means.size) {
                val gap = means[li] - means[last]
                if (gap > 3 * (stds[li] + stds[last]) / 2 + 0.5) { n++; last = li }
            }
            sb.append("  -> steps it can tell apart: $n of ${means.size}\n")
            bestN = maxOf(bestN, n)
        }
        val bits = when { bestN >= 4 -> 2; bestN >= 2 -> 1; else -> 0 }
        sb.insert(0, "RESULT: up to $bestN brightness steps told apart = $bits bit${if (bits == 1) "" else "s"} per flash${if (bestN >= 3 && bestN < 4) " (3 steps = about 1.6 bits)" else ""}\n")
        return sb.toString()
    }

    private fun corr(a: List<Double>, b: List<Double>): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb) }
        return num / (sqrt(da * db) + 1e-9)
    }
}
