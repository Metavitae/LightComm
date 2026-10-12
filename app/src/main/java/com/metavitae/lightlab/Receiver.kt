package com.metavitae.lightlab

import java.util.Locale

/**
 * Keeps every frame the camera reported and turns them into plain numbers:
 * how bright "on" and "off" look, whether they can be told apart, and how
 * fast the light really changed.
 */
class Receiver {
    private val frames = ArrayList<CamSession.Frame>()
    private val lock = Any()
    private val maxFrames = 30 * 60 * 15  // 15 minutes at 30 fps

    fun reset() = synchronized(lock) { frames.clear() }
    fun count() = synchronized(lock) { frames.size }

    fun add(f: CamSession.Frame) = synchronized(lock) {
        if (frames.size < maxFrames) frames.add(f)
    }

    /** The brightest-spot values of the last 5 seconds. */
    fun recent(): List<Int> = synchronized(lock) { frames.takeLast(150).map { it.block } }

    fun threshold(): Int {
        val v = recent()
        if (v.isEmpty()) return 0
        return (v.minOrNull()!! + v.maxOrNull()!!) / 2
    }

    fun liveLine(): String {
        val fr = synchronized(lock) { frames.takeLast(60) }
        if (fr.size < 5) return "Waiting for camera..."
        val v = fr.map { it.block }
        val lo = v.minOrNull()!!
        val hi = v.maxOrNull()!!
        val t = (lo + hi) / 2
        var changes = 0
        for (i in 1 until v.size) if ((v[i] > t) != (v[i - 1] > t)) changes++
        val secs = (fr.last().tsNs - fr.first().tsNs) / 1e9
        val fps = (fr.size - 1) / secs
        val clear = hi - lo > 30
        return "Brightest spot now: ${v.last()}  (range $lo-$hi)\n" +
            (if (clear) "Light changes per second seen: ${String.format(Locale.US, "%.1f", changes / secs)}" else "No clear blinking seen") +
            "\nCamera speed: ${String.format(Locale.US, "%.1f", fps)} frames per second"
    }

    fun report(): String {
        val fr = synchronized(lock) { ArrayList(frames) }
        val sb = StringBuilder()
        if (fr.size < 10) return "Too few frames recorded (${fr.size}).\n"
        val secs = (fr.last().tsNs - fr.first().tsNs) / 1e9
        val gaps = fr.zipWithNext { a, b -> (b.tsNs - a.tsNs) / 1e6 }
        val expected = gaps.sorted()[gaps.size / 2]
        sb.append("\nRESULT\n")
        sb.append("Frames: ${fr.size} over ${Stats.f(secs)} s = ${Stats.f((fr.size - 1) / secs)} fps\n")
        sb.append("Gap between frames (ms): ${Stats(gaps).line("")}\n")
        sb.append("Late or dropped frames (gap > 1.5x usual): ${gaps.count { it > expected * 1.5 }}\n")
        val skew = fr.map { it.skewNs / 1e6 }.filter { it > 0 }
        sb.append("Rolling-shutter readout reported: ${if (skew.isEmpty()) "no" else Stats.f(skew.average()) + " ms"}\n")

        for ((name, sel) in listOf<Pair<String, (CamSession.Frame) -> Int>>(
            "Brightest block" to { f -> f.block },
            "Brightest single pixel" to { f -> f.peak },
        )) {
            val v = fr.map { sel(it).toDouble() }
            val s = Stats(v)
            val lo = s.pct(0.05)
            val hi = s.pct(0.95)
            val t = (lo + hi) / 2
            val on = v.filter { it > t }
            val off = v.filter { it <= t }
            val sOn = Stats(on)
            val sOff = Stats(off)
            val gap = sOn.mean - sOff.mean
            val noise = maxOf(1.0, (sOn.std + sOff.std) / 2)
            var changes = 0
            val runs = mutableListOf<Double>()
            var run = 1
            for (i in 1 until v.size) {
                if ((v[i] > t) != (v[i - 1] > t)) { changes++; runs.add(run.toDouble()); run = 1 } else run++
            }
            sb.append("\n$name (0-255):\n")
            sb.append("  all frames: ${s.line("")}\n")
            sb.append("  'on' frames: ${sOn.line("")}\n")
            sb.append("  'off' frames: ${sOff.line("")}\n")
            sb.append("  separation (difference / noise): ${Stats.f(gap / noise)}  " +
                (when {
                    gap < 15 -> "-> no usable on/off difference"
                    gap / noise > 8 -> "-> very clear"
                    gap / noise > 4 -> "-> clear"
                    else -> "-> weak, errors likely"
                }) + "\n")
            sb.append("  light changes seen: $changes = ${Stats.f(changes / secs)} per second\n")
            sb.append("  frames per steady stretch: ${Stats(runs).line("")}\n")
        }

        sb.append("\nPER FRAME\nt_ms,block,block_x,block_y,peak,mean,exposure_us,iso,flash_state\n")
        val t0 = fr.first().tsNs
        for (f in fr) {
            sb.append(String.format(Locale.US, "%.2f,%d,%d,%d,%d,%d,%d,%d,%d\n",
                (f.tsNs - t0) / 1e6, f.block, f.bx, f.by, f.peak, f.mean, f.expNs / 1000, f.iso, f.flashState))
        }
        return sb.toString()
    }
}
