package com.metavitae.lightlab

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Listen for us": instead of looking for a bright dot, checks every small
 * zone of the picture (plus bigger zones and the whole picture) for our known
 * rhythm. A zone whose brightness rises and falls at exactly our rhythm is us,
 * however faint, reflected or small the light is.
 *
 * Strength = power at our rhythm divided by the typical power at nearby
 * rhythms in the same zone, over the last 4 seconds.
 */
class Listener(val flashesPerSec: Double) {
    data class Result(
        val tSec: Double,
        val best: Double, val bestX: Int, val bestY: Int,   // best fine zone (sensor grid)
        val coarse: Double,                                 // best of the 8x6 big zones
        val whole: Double,                                  // whole picture
        val map: FloatArray,                                // strength of every fine zone
    )

    private val zx = CamSession.ZX
    private val zy = CamSession.ZY
    private val cx = 8
    private val cy = 6
    private val nFine = zx * zy
    private val nZones = nFine + cx * cy + 1
    private val win = 120   // 4 s at 30 fps
    private val buf = Array(nZones) { FloatArray(win) }
    private val times = LongArray(win)
    private var count = 0
    private var head = 0
    private var t0 = 0L

    private val noiseRatios = doubleArrayOf(0.55, 0.7, 0.82, 1.18, 1.3, 1.45)

    fun add(tsNs: Long, fine: FloatArray) {
        if (t0 == 0L) t0 = tsNs
        val coarse = FloatArray(cx * cy)
        var whole = 0f
        for (y in 0 until zy) for (x in 0 until zx) {
            val v = fine[y * zx + x]
            coarse[(y * cy / zy) * cx + (x * cx / zx)] += v
            whole += v
        }
        for (i in 0 until nFine) buf[i][head] = fine[i]
        val per = (nFine / (cx * cy)).toFloat()
        for (i in 0 until cx * cy) buf[nFine + i][head] = coarse[i] / per
        buf[nZones - 1][head] = whole / nFine
        times[head] = tsNs
        head = (head + 1) % win
        if (count < win) count++
    }

    fun ready() = count >= win

    fun analyze(): Result? {
        if (!ready()) return null
        // Oldest-to-newest order, times in seconds.
        val idx = IntArray(win) { (head + it) % win }
        val t = DoubleArray(win) { (times[idx[it]] - times[idx[0]]) / 1e9 }
        val freqs = DoubleArray(1 + noiseRatios.size)
        freqs[0] = flashesPerSec
        for (i in noiseRatios.indices) freqs[i + 1] = flashesPerSec * noiseRatios[i]
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val cosT = Array(freqs.size) { f -> DoubleArray(win) { cos(2 * PI * freqs[f] * t[it]) * hann[it] } }
        val sinT = Array(freqs.size) { f -> DoubleArray(win) { sin(2 * PI * freqs[f] * t[it]) * hann[it] } }

        val strength = FloatArray(nZones)
        val p = DoubleArray(freqs.size)
        for (z in 0 until nZones) {
            val b = buf[z]
            var mean = 0.0
            for (k in 0 until win) mean += b[idx[k]]
            mean /= win
            for (f in freqs.indices) {
                var c = 0.0
                var s = 0.0
                val ct = cosT[f]
                val st = sinT[f]
                for (k in 0 until win) {
                    val v = b[idx[k]] - mean
                    c += v * ct[k]
                    s += v * st[k]
                }
                p[f] = c * c + s * s
            }
            val noise = p.copyOfRange(1, p.size).sorted().let { (it[2] + it[3]) / 2 }
            strength[z] = (p[0] / (noise + 1e-6)).toFloat()
        }
        var best = 0
        for (i in 0 until nFine) if (strength[i] > strength[best]) best = i
        var bestC = 0.0
        for (i in 0 until cx * cy) bestC = maxOf(bestC, strength[nFine + i].toDouble())
        return Result(
            (times[idx[win - 1]] - t0) / 1e9,
            strength[best].toDouble(), best % zx, best / zx,
            bestC, strength[nZones - 1].toDouble(),
            strength.copyOfRange(0, nFine),
        )
    }

    companion object {
        /**
         * Strength needed to say "we hear us". From the first test (Oct 11):
         * with no signal, the best of ~3000 small spots reached up to ~230 by
         * chance, big zones ~45, the whole picture ~27; with our light, all
         * three went into the thousands.
         */
        const val FOUND_SPOT = 500.0
        const val FOUND_ZONE = 150.0
        const val FOUND_WHOLE = 100.0
        const val FOUND = FOUND_SPOT
    }
}
