package com.metavitae.lightlab

import android.content.Context
import android.os.Build
import java.util.Locale
import kotlin.math.max

/**
 * Mirror check (Oct 30, founder picked "alone, then together": the alone part of the tutorial uses a mirror).
 * One phone faces a mirror: the camera itself blinks a real message (2 levels, 15 per second, one flashlight
 * state per picture) and films its own light in the glass. The app's own decoder then tries to read it.
 * Tried at a few exposures, because the reflection can be much brighter than another phone across a room.
 */
class MirrorCheck(private val ctx: Context, private val pass: String, private val text: String, private val exposuresUs: List<Int>, private val iso: Int) {
    class Run(val expUs: Int, val nonce: Int, val t0: Double, val end: Double, val frames: ArrayList<CamSession.Frame> = ArrayList())

    @Volatile var alive = true
    private val runs = ArrayList<Run>()
    @Volatile private var cur: Run? = null

    /** Blinks and films each exposure in turn; returns the report (also what is saved). */
    fun go(camId: String, show: (String) -> Unit): String {
        val mode = BrightFormat.MODES.first { it.levels == 2 && it.perSec == 15 }
        val tag = BrightFormat.phoneTag(Build.MODEL)
        val c = CamSession(ctx, camId, null) { f -> cur?.frames?.add(f) }
        c.manual = true; c.fps = 30; c.iso = iso; c.expNs = exposuresUs[0] * 1000L
        var err = ""
        c.onError = { e -> err = e }
        var ready = false
        c.open { ready = true }
        val t0 = System.currentTimeMillis()
        while (!ready && err.isEmpty() && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20)
        if (!ready) { c.close(); return "Camera did not open: $err\n" }
        Thread.sleep(1500)
        for (us in exposuresUs) {
            if (!alive) break
            c.expNs = us * 1000L; c.apply(); Thread.sleep(800)
            val s = BrightFormat.build(text, pass, java.security.SecureRandom().nextInt(65536), mode, tag, 12)
            val n = Math.ceil(s.wave.end * 30).toInt()
            // One flashlight state per picture: on when the light is on for most of that 1/30 s.
            val states = BooleanArray(n) { k -> s.wave.light(k / 30.0, (k + 1) / 30.0) > 0.5 / 30 }
            val r = Run(us, s.nonce, System.nanoTime() / 1e9, s.wave.end)
            runs.add(r); cur = r
            show("Blinking at ${us / 1000.0} ms exposure (${runs.size} of ${exposuresUs.size}), ${Math.round(s.wave.end)} s...")
            c.queueTorch(states)
            Thread.sleep(((s.wave.end + 2.0) * 1000).toLong())
            while (alive && c.queuedBursts > 0) Thread.sleep(50)
            Thread.sleep(1500)
            cur = null
        }
        c.closeAndWait(2000)
        return report()
    }

    private fun report(): String {
        val sb = StringBuilder()
        sb.append("Text: $text\nISO $iso, 30 pictures a second, 2 levels at 15 per second, one flashlight state per picture\n\n")
        for (r in runs) {
            val fr = r.frames
            if (fr.size < 30) { sb.append("${r.expUs} us: only ${fr.size} pictures\n"); continue }
            val tFirst = fr[0].tsNs / 1e9
            val t = DoubleArray(fr.size) { fr[it].tsNs / 1e9 - tFirst }
            val lines = mutableListOf<String>()
            var best: BrightDecoder.Result? = null
            for ((name, pick) in listOf<Pair<String, (CamSession.Frame) -> Int>>("block" to { it.block }, "peak" to { it.peak }, "mean" to { it.mean })) {
                val ser = BrightDecoder.Series(t, DoubleArray(fr.size) { pick(fr[it]).toDouble() })
                val dec = BrightDecoder(pass, r.expUs / 1e6)
                val st = dec.findStarts(ser, 0.0, t.last() - BrightFormat.syncWave().end - 0.3, 0.6).firstOrNull()
                val res = st?.let { dec.decode(listOf(ser), it.first, it.second) }
                lines.add(String.format(Locale.US, "  %-5s opening match %.2f: %s", name, st?.second ?: 0.0,
                    res?.let { if (it.ok) "READ \"${it.text}\" (snr %.1f)".format(Locale.US, it.snr) else "not read (${it.stage})" } ?: "no opening found"))
                if (res?.ok == true && best == null) best = res
            }
            val on = fr.map { it.block }.sorted()
            sb.append(String.format(Locale.US, "%.1f ms exposure: %s  (pictures %d, brightest-block low %d / high %d)\n", r.expUs / 1000.0,
                if (best?.text == text) "READ RIGHT" else if (best != null) "READ WRONG" else "NOT READ", fr.size, on[on.size / 10], on[max(0, on.size * 9 / 10)]))
            lines.forEach { sb.append(it).append('\n') }
        }
        sb.append("\nPictures (exposure us, seconds, brightest block, brightest pixel, whole-picture average, flash state):\n")
        for (r in runs) { val f0 = r.frames.firstOrNull()?.tsNs ?: 0L
            for (f in r.frames) sb.append(String.format(Locale.US, "%d,%.4f,%d,%d,%d,%d\n", r.expUs, (f.tsNs - f0) / 1e9, f.block, f.peak, f.mean, f.flashState)) }
        return sb.toString()
    }
}
