package com.metavitae.lightlab

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** A results file that grows while a test runs (one line per send), in Download/LightLab. */
class LiveFile(private val ctx: Context, kind: String) {
    val name: String
    private var uri: Uri? = null
    init {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        name = "LightLab_${kind}_${Build.MODEL}_$stamp.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LightLab")
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {}
    }
    val path get() = if (uri != null) "Download/LightLab/$name" else "NOT SAVED"
    @Synchronized fun append(s: String) {
        val u = uri ?: return
        try { ctx.contentResolver.openOutputStream(u, "wa")?.use { it.write(s.toByteArray()) } } catch (_: Exception) {}
    }
}

/**
 * Sends brightness messages with the flashlight. The sync part is played first while
 * the phone times its own switches; the rest is built with the shortest pulse it can make.
 */
class BrightSender(private val ctx: Context) {
    @Volatile var running = false

    data class Sent(val text: String, val mode: BrightFormat.Mode, val nonce: Int, val pminMs: Int, val startMs: Long,
                    val seconds: Double, val lateUs: Stats, val callUs: Stats, val firstCallUs: Double, val failure: String?)

    /**
     * One blink before the first send of a run, so a flashlight that has been idle for a
     * while is awake before the sync plays. Returns how long its first switch took (us).
     */
    fun warmUp(): Double {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = torchCamera(ctx)?.first ?: return 0.0
        return try {
            val c0 = System.nanoTime(); cm.setTorchMode(id, true); val us = (System.nanoTime() - c0) / 1000.0
            Thread.sleep(200); cm.setTorchMode(id, false); Thread.sleep(1500); us
        } catch (_: Exception) { 0.0 }
    }

    private fun waitUntil(t: Long) {
        while (true) {
            val left = t - System.nanoTime()
            if (left <= 0) return
            if (left > 2_000_000) Thread.sleep((left - 1_500_000) / 1_000_000) else Thread.onSpinWait()
        }
    }

    /**
     * One send. beaconSec > 0 plays the passphrase rhythm first (for "Listen for us").
     * link + seq >= 1: short opening (the link's shortest pulse is used, so both phones agree).
     * nonce >= 0: send with this nonce (a resend of the same full message).
     */
    fun send(text: String, mode: BrightFormat.Mode, pass: String, beaconSec: Double,
             link: BrightFormat.Link? = null, seq: Int = 0, nonce0: Int = -1, progress: (String) -> Unit): Sent {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = torchCamera(ctx)?.first ?: return Sent(text, mode, 0, 0, 0, 0.0, Stats(emptyList()), Stats(emptyList()), 0.0, "no flashlight")
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val short = link != null && seq >= 1
        var nonce = if (nonce0 >= 0) nonce0 else java.security.SecureRandom().nextInt(65536)
        val late = ArrayList<Double>(); val calls = ArrayList<Double>()
        var failure: String? = null
        var state = false
        val t0 = System.nanoTime() + 300_000_000
        val startMs = SystemClock.elapsedRealtime() + 300
        fun play(list: List<Pair<Double, Boolean>>, offset: Double, measure: Boolean) {
            for ((t, on) in list) {
                if (!running || failure != null) return
                val target = t0 + ((offset + t) * 1e9).toLong()
                waitUntil(target)
                if (on == state) continue
                val c0 = System.nanoTime()
                try { cm.setTorchMode(id, on) } catch (e: Exception) { failure = e.message; return }
                val d = (System.nanoTime() - c0) / 1e9
                late.add((c0 - target) / 1000.0)
                if (measure) calls.add(d)
                state = on
            }
        }
        running = true
        var offset = 0.0
        if (beaconSec > 0) {
            val hz = MessageFormat.beaconHz(pass)
            val b = ArrayList<Pair<Double, Boolean>>()
            var t = 0.0; var on = true
            while (t < beaconSec - 1e-9) { b.add(t to on); on = !on; t += 1.0 / (2 * hz) }
            b.add(beaconSec to false)
            progress("Beacon...")
            play(b, 0.0, false)
            offset = beaconSec + 0.5
        }
        progress(if (short) "Short opening #$seq..." else "Sync pattern (and timing my own flashlight)...")
        val sync = BrightFormat.syncWave(short)
        play(BrightFormat.switches(sync), offset, true)
        val pmin = if (short) link!!.pminMs else BrightFormat.pminMs(calls)
        val tag = BrightFormat.phoneTag(Build.MODEL)
        val s = BrightFormat.build(text, pass, nonce, mode, tag, pmin, link, seq)
        nonce = s.nonce
        progress("Sending \"${text.take(20)}\" in ${mode.name} (shortest pulse $pmin ms)...")
        play(BrightFormat.switches(s.wave, sync.n, s.wave.n, startState = state), offset, true)
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        val ok = running
        running = false
        return Sent(text, mode, nonce, pmin, startMs, offset + s.wave.end, Stats(late), Stats(calls.map { it * 1e6 }),
            calls.firstOrNull()?.times(1e6) ?: 0.0, failure ?: if (!ok) "stopped" else null)
    }

    /**
     * Short reply ("got it" when ok, else "saw it but could not read it") to message `nonce`, right away.
     * Returns (seconds, switch late mean us, failure or null).
     */
    fun reply(pass: String, nonce: Int, ok: Boolean, perSec: Int = 30): Triple<Double, Double, String?> {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = torchCamera(ctx)?.first ?: return Triple(0.0, 0.0, "no flashlight")
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val w = BrightFormat.replyWave(pass, nonce, ok, perSec)
        val late = ArrayList<Double>()
        var state = false
        val t0 = System.nanoTime() + 50_000_000
        running = true
        for ((t, on) in BrightFormat.switches(w)) {
            if (!running) break
            val target = t0 + (t * 1e9).toLong()
            waitUntil(target)
            if (on == state) continue
            val c0 = System.nanoTime()
            try { cm.setTorchMode(id, on) } catch (e: Exception) { running = false; return Triple(0.0, 0.0, e.message) }
            late.add((c0 - target) / 1000.0); state = on
        }
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        running = false
        return Triple(w.end, if (late.isEmpty()) 0.0 else late.average(), null)
    }

    /** Aiming: play the aiming pattern over and over until stopped or `maxSec` is up. */
    fun aim(perSec: Int, maxSec: Double) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = torchCamera(ctx)?.first ?: return
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val w = BrightFormat.aimWave(perSec, maxSec)
        var state = false
        val t0 = System.nanoTime() + 50_000_000
        running = true
        for ((t, on) in BrightFormat.switches(w)) {
            if (!running) break
            waitUntil(t0 + (t * 1e9).toLong())
            if (on == state) continue
            try { cm.setTorchMode(id, on) } catch (_: Exception) { break }
            state = on
        }
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        running = false
    }

    /** Line for the sender's results file. */
    fun line(i: Int, s: Sent) = String.format(Locale.US, "%d,%s,%d,%d,%d,%d,%.1f,%.0f,%.0f,%.0f,%.0f,%s,\"%s\"\n",
        i, Build.MODEL, s.mode.id, s.mode.levels, s.mode.perSec, s.pminMs, s.seconds, s.lateUs.mean, s.callUs.mean, s.callUs.pct(0.95), s.firstCallUs,
        s.failure ?: "ok", s.text.replace("\"", "'"))

    companion object {
        const val HEAD = "send,phone,mode,levels,per_sec,shortest_pulse_ms,seconds,late_us_mean,switch_us_mean,switch_us_p95,first_switch_us,status,text\n"
    }
}

/**
 * Receiving side: keeps the last ~2.5 minutes of pictures as 12 row bands x 8 column
 * blocks (+ the whole picture), looks for sends every 2 seconds, reads them and writes
 * one line per send with all the conditions.
 */
class BrightListener(private val ctx: Context, private val pass: String, exp0: Double) {
    /** How long each picture is exposed (s). Shorter in glare (see `settle`); the reader's light model follows it. */
    @Volatile var expSec: Double = exp0
        set(v) { field = v; dec = BrightDecoder(pass, v) }
    companion object {
        const val NB = 12
        const val NC = 8
        const val NCH = NB * NC + 1
        /** Fine squares (Oct 14): 32 x 24, for a small far light on a bright background (8 x smaller than a block). */
        const val FX = 32
        const val FY = 24
        /** How many of the most flickering fine squares are searched and read, besides the known ones. */
        const val FINE_PICK = 8
        const val MIN_EXP = 0.016

        /**
         * Camera setting from what it sees now (Oct 14, sun glare on a door turned parts of the picture white:
         * a white spot can't show our blinks). Aim: background around 40 of 255 and the brightest spots under
         * about 170. Sensitivity first (ISO 100-1600 at a full 1/30 s picture); below ISO 100 the picture gets
         * shorter instead, down to 16 ms. SIMULATED, POCO flashlight, 36 sends each: 32.5 ms 36/36, 16 ms 35/36,
         * 12 ms 25/36, 8 ms 31/36, 4 ms and shorter half or worse (its switch times vary by several ms; a short
         * picture catches the light mid-switch, a long one averages that out). Direct sun glare: not solved, shade helps.
         * Returns (iso, exposure s).
         */
        fun settle(bg: Double, top: Double, iso: Int, expSec: Double): Pair<Int, Double> {
            var g = if (bg > 0.5) 40.0 / bg else 1.0
            if (top > 1) g = minOf(g, 170.0 / top)
            if (top >= 250) g = minOf(g, 0.35)     // white: we can't tell how far over, so step well down
            val total = iso * expSec * g             // ISO x seconds
            val full = 0.0325
            return if (total / full >= 100) (total / full).toInt().coerceIn(100, 1600) to full
                   else 100 to (total / 100).coerceIn(MIN_EXP, full)
        }
        const val CAP = 30 * 150
        const val HEAD = "time,from_phone,to_phone,mode,levels,per_sec,result,bit_errors_before_repair,coded_bits,symbol_errors,symbols," +
            "text_seconds,send_seconds,channels,signal_to_noise,levels_apart,shortest_pulse_ms,sync_match,room,lux,background,iso,exposure_ms,paces,note,text\n"
    }

    private val times = DoubleArray(CAP)
    private val vals = Array(CAP) { FloatArray(NCH) }
    /** Fine squares, brightness x 100 (compact: 32 x 24 x 4500 pictures is about 7 MB). */
    private val fineVals = Array(CAP) { ShortArray(FX * FY) }
    private var count = 0L     // frames ever added
    private val lock = Any()
    @Volatile var scanFrom = 0.0
    /** Receiver clock time from which pictures count (set once the sensitivity has settled); 0 = not ready yet. */
    @Volatile var readyAt = 0.0
    /** Also look this many seconds before "ready" (talk: a send's dark start may come while the camera settles). */
    @Volatile var earlySec = 0.0
    private var lastWeakT = -1e9
    /** Nonce of our own message whose short reply we are waiting for (-1 = none). */
    @Volatile var expect = -1
    /** Speed of the expected reply (the speed our message went at). */
    @Volatile var expectRate = 30
    private var replyFrom = 0.0
    private var lastReplyFine = false
    /** Blocks that carried the last send read right (up to 6), searched for the next sync too. */
    private var known: List<Int> = emptyList()
    /** Fine squares that carried the last send read right. */
    private var knownFine: List<Int> = emptyList()
    /** Seconds of raw brightness to record after "ready" (0 = none), for replaying a miss on the computer. */
    @Volatile var traceSec = 0.0
    private val trace = StringBuilder()
    private var traceDone = false
    @Volatile var lux = Float.NaN
    @Volatile var room = "dim"
    @Volatile var paces = "hand-width"
    @Volatile var iso = 0
    var lastUsed: Set<Int> = emptySet()
        private set
    /** Link with the other phone (set by the talk loop): short openings from it are looked for too. */
    @Volatile var inLink: BrightFormat.Link? = null
    /** Texts the other phone is expected to send (to count errors before repair). */
    @Volatile var expectTexts: List<String> = BrightFormat.TEST_MESSAGES
    /** Everything read (or read up to the header) and every short reply found, for the talk loop. */
    val heard = java.util.concurrent.LinkedBlockingQueue<BrightDecoder.Result>()
    val replies = java.util.concurrent.LinkedBlockingQueue<Pair<Int, Boolean>>()
    /** A send has started and is still coming (don't start sending over it). */
    @Volatile var hearing = false
    /** Send whose header was read but whose end is still coming: (start, end); skip re-reading it until then. */
    private var pending: Pair<Double, Double>? = null
    val tally = LinkedHashMap<String, IntArray>()   // "mode|from" -> (right, sent)
    var lastLine = ""

    fun add(tsNs: Long, fine: FloatArray) {
        val zx = CamSession.ZX; val zy = CamSession.ZY
        synchronized(lock) {
            val k = (count % CAP).toInt()
            times[k] = tsNs / 1e9
            val v = vals[k]
            java.util.Arrays.fill(v, 0f)
            for (y in 0 until zy) {
                val b = y * NB / zy
                for (x in 0 until zx) v[b * NC + x * NC / zx] += fine[y * zx + x]
            }
            val per = (zy / NB) * (zx / NC)
            var whole = 0f
            for (i in 0 until NB * NC) { v[i] = v[i] / per; whole += v[i] }
            v[NB * NC] = whole / (NB * NC)
            val fv = fineVals[k]
            val fsum = FloatArray(FX * FY)
            for (y in 0 until zy) { val r = (y * FY / zy) * FX; for (x in 0 until zx) fsum[r + x * FX / zx] += fine[y * zx + x] }
            val fper = (zy / FY) * (zx / FX)
            for (i in fsum.indices) fv[i] = (fsum[i] / fper * 100).toInt().coerceIn(0, 32767).toShort()
            count++
            if (traceSec > 0 && readyAt > 0 && !traceDone && times[k] >= readyAt) {
                if (times[k] - readyAt <= traceSec) {
                    trace.append(String.format(Locale.US, "%.5f", times[k] - readyAt))
                    for (x in v) trace.append(',').append(Math.round(x * 10) / 10.0)   // cheap: runs on every picture
                    trace.append('\n')
                } else traceDone = true
            }
        }
    }

    /** Brightness of the brightest fine squares lately (98th percentile of each square's highest value over 10 pictures). */
    fun brightTop(): Double = synchronized(lock) {
        if (count < 10) return 0.0
        val mx = DoubleArray(FX * FY)
        for (j in 1..10) { val v = fineVals[((count - j) % CAP).toInt()]; for (z in mx.indices) mx[z] = maxOf(mx[z], v[z] / 100.0) }
        mx.sort(); mx[(mx.size * 0.98).toInt()]
    }

    /** Whole-picture brightness lately (to report the room). */
    fun background(): Double = synchronized(lock) {
        if (count == 0L) return 0.0
        var s = 0.0; val n = minOf(30L, count).toInt()
        for (j in 1..n) s += vals[((count - j) % CAP).toInt()][NB * NC]
        s / n
    }

    /** Channels from time a to b (receiver clock seconds). */
    private fun window(a: Double, b: Double): List<BrightDecoder.Series> = synchronized(lock) {
        val n = minOf(count, CAP.toLong()).toInt()
        val first = count - n
        val idx = ArrayList<Int>()
        for (j in 0 until n) { val k = ((first + j) % CAP).toInt(); if (times[k] in a..b) idx.add(k) }
        val t = DoubleArray(idx.size) { times[idx[it]] }
        List(NCH) { ch -> BrightDecoder.Series(t, DoubleArray(idx.size) { vals[idx[it]][ch].toDouble() }) }
    }

    /** Pictures (ring indexes) from time a to b. Call inside the lock. */
    private fun indexes(a: Double, b: Double): IntArray {
        val n = minOf(count, CAP.toLong()).toInt()
        val first = count - n
        val idx = ArrayList<Int>()
        for (j in 0 until n) { val k = ((first + j) % CAP).toInt(); if (times[k] in a..b) idx.add(k) }
        return idx.toIntArray()
    }

    /** Fine squares as picture series, from time a to b. */
    private fun fineWindow(a: Double, b: Double, zones: List<Int>): List<BrightDecoder.Series> = synchronized(lock) {
        val idx = indexes(a, b)
        val t = DoubleArray(idx.size) { times[idx[it]] }
        zones.map { z -> BrightDecoder.Series(t, DoubleArray(idx.size) { fineVals[idx[it]][z] / 100.0 }) }
    }

    /**
     * Fine squares that flicker most between a and b, measured against their own brightness (picture noise
     * grows with brightness, so a bright sunny floor doesn't win just by being bright), plus the known ones.
     */
    private fun flickery(a: Double, b: Double): List<Int> {
        val score = synchronized(lock) {
            val idx = indexes(a, b)
            DoubleArray(FX * FY) { z ->
                if (idx.size < 10) 0.0 else {
                    var d = 0.0; var m = 0.0; var prev = fineVals[idx[0]][z].toDouble()
                    for (k in idx) { val v = fineVals[k][z].toDouble(); d += kotlin.math.abs(v - prev); m += v; prev = v }
                    (d / (idx.size - 1)) / kotlin.math.sqrt(m / idx.size + 400.0)
                }
            }
        }
        return ((0 until FX * FY).sortedByDescending { score[it] }.take(FINE_PICK) + knownFine).distinct()
    }

    /** The finished recording, once (seconds after ready, 96 blocks, whole picture). */
    fun takeTrace(): String? = synchronized(lock) {
        if (!traceDone || trace.isEmpty()) return null
        val out = "t_after_ready," + (0 until NB * NC).joinToString(",") { "b$it" } + ",whole\n" + trace
        trace.setLength(0); out
    }

    fun newest(): Double = synchronized(lock) { if (count == 0L) 0.0 else times[((count - 1) % CAP).toInt()] }

    private var dec = BrightDecoder(pass, exp0)

    /** Look for new sends and read the ones that are complete. Returns lines to log. */
    fun scan(): List<String> {
        val now = newest()
        if (now == 0.0 || readyAt == 0.0) return emptyList()
        if (scanFrom < readyAt - earlySec) scanFrom = readyAt - earlySec
        val syncLen = BrightFormat.syncWave().end
        val shortLen = BrightFormat.syncWave(short = true).end
        val link = inLink
        val from = maxOf(scanFrom, now - 140)
        if (now - from < (if (link != null) shortLen else syncLen) + 1) return emptyList()
        pending?.let { (pt, pe) -> if (pt >= from - 0.1 && now < pe) return emptyList() }
        scanReply(syncLen)?.let { return listOf(it) }
        val fz = flickery(from, now)
        val ws = window(from - 0.1, now) + fineWindow(from - 0.1, now, fz)
        // Find the sync on the whole picture and on the 4 liveliest blocks.
        // Where to look for a sync: the whole picture, the liveliest blocks (people and pets moving are lively too),
        // and the blocks that carried our last read send (a small far light is easy to lose among the lively ones).
        val lively = (0 until NB * NC).sortedByDescending { variance(ws[it].y) }.take(4)
        val search = (listOf(NB * NC) + lively + known).distinct() + fz.indices.map { NCH + it }
        val starts = search.flatMap { ch -> dec.findStarts(ws[ch], from, now - syncLen - 0.3, 0.8).map { Triple(it.first, it.second, false) } +
            (if (link != null) dec.findStarts(ws[ch], from, now - shortLen - 0.3, 0.8, short = true).map { Triple(it.first, it.second, true) } else emptyList()) }
        val t0 = starts.minByOrNull { it.first } ?: run {
            hearing = false
            scanFrom = maxOf(scanFrom, now - (if (link != null) shortLen else syncLen) - 2)
            // Near-miss note: a sync-like shape that was too weak to read, so a miss shows which side lost it.
            val weak = dec.findStarts(ws[NB * NC], from, now - syncLen - 0.3, 0.65).firstOrNull()
            if (weak != null && weak.first - lastWeakT > 1.0) {
                lastWeakT = weak.first
                return listOf(String.format(Locale.US, "# weak sync, not read: %.1f s after ready, match %.2f\n", weak.first - readyAt, weak.second))
            }
            return emptyList()
        }
        val short = t0.third
        val c = starts.filter { it.third == short && kotlin.math.abs(it.first - t0.first) < 0.05 }.maxOf { it.second }
        val all = window(t0.first - 0.5, now) + fineWindow(t0.first - 0.5, now, fz)
        val r = dec.decode(all, t0.first, c, if (short) link else null)
        val headerDone = t0.first + (if (short) shortLen + BrightFormat.SHORT_HDR_SYMS.toDouble() / BrightFormat.RATES.last()
                                     else syncLen + BrightFormat.HDR_SYMS.toDouble() / BrightFormat.RATES.last()) + 1.0
        hearing = true
        if (r.mode != null && r.endT > now) { pending = t0.first to r.endT; return emptyList() }   // header read, rest still coming
        if (r.mode == null && now < headerDone) return emptyList()       // wait for the header at the slow speed
        hearing = false; pending = null
        scanFrom = if (r.mode != null) r.endT - 0.2 else t0.first + 1.0
        if (r.ok) { known = r.blocks.filter { it < NB * NC }.take(6); knownFine = r.blocks.filter { it >= NCH }.map { fz[it - NCH] }.take(6) }
        val line = logLine(r)
        heard.add(r)
        val nFine = r.blocks.count { it >= NCH }
        return listOf(line) + (if (r.mode != null) listOf("# channels used: ${r.blocks.size - nFine} blocks, $nFine fine squares\n") else emptyList())
    }

    /** If a short reply is expected: look for it on the whole picture, the liveliest blocks and the known ones. */
    private fun scanReply(syncLen: Double): String? {
        val n = expect
        if (n < 0) return null
        val now = newest()
        val rate = expectRate
        val replyLen = BrightFormat.replyWave(pass, n, true, rate).end
        // Half a second before "ready" too: the reply's dark start may come while the camera settles.
        val from = maxOf(replyFrom, readyAt - 0.5, now - 30)
        if (now - from < replyLen + 0.5) return null
        val fz = flickery(from, now)
        val ws = window(from - 0.1, now) + fineWindow(from - 0.1, now, fz)
        val lively = (0 until NB * NC).sortedByDescending { variance(ws[it].y) }.take(4)
        val hits = ((listOf(NB * NC) + lively + known).distinct() + fz.indices.map { NCH + it })
            .mapNotNull { ch -> dec.findReply(ws[ch], from, now - replyLen - 0.2, n, perSec = rate)?.let { it to ch } }
        replyFrom = now - replyLen - 0.2
        val (best, bestCh) = hits.maxByOrNull { it.first.second } ?: return null
        lastReplyFine = bestCh >= NCH
        expect = -1
        replies.add(n to best.third)
        return String.format(Locale.US, "# reply %s for nonce %d: match %.2f, %.1f s after ready%s\n",
            if (best.third) "GOT IT" else "SAW BUT COULD NOT READ", n, best.second, best.first - readyAt, if (lastReplyFine) " (best on a fine square)" else "")
    }

    /**
     * Aiming score: best match of the aiming pattern over the last `win` seconds, on the whole picture, the
     * liveliest blocks and the most flickering fine squares. Returns (match 0..1, best on a fine square?).
     */
    fun aimScore(win: Double, w: BrightFormat.Wave, period: Double): Pair<Double, Boolean> {
        val now = newest(); if (now == 0.0 || readyAt == 0.0) return 0.0 to false
        val a = maxOf(now - win, readyAt)
        if (now - a < 2.0) return 0.0 to false
        val fz = flickery(a, now)
        val ws = window(a, now) + fineWindow(a, now, fz)
        val lively = (0 until NB * NC).sortedByDescending { variance(ws[it].y) }.take(8)
        var best = 0.0; var fine = false
        for (ch in (listOf(NB * NC) + lively).distinct() + fz.indices.map { NCH + it }) {
            var t0 = a - period
            while (t0 < a) {
                val c = dec.match(ws[ch], t0, w, now - t0)
                if (c > best) { best = c; fine = ch >= NCH }
                t0 += 1.0 / 60
            }
        }
        return best to fine
    }

    private fun variance(y: DoubleArray): Double { if (y.isEmpty()) return 0.0; val m = y.average(); return y.sumOf { (it - m) * (it - m) } / y.size }

    private fun logLine(r: BrightDecoder.Result): String {
        val mode = r.mode
        // Compare with the test messages (same length) to count errors before repair.
        var bitErr = -1; var symErr = -1; var nSym = 0; var expectedText = ""
        if (mode != null && r.coded != null) {
            fun bytesOf(t: String) = (if (r.packed) TextPack.pack(t) else null) ?: BrightFormat.utf8(t)
            // Two test messages can pack to the same length: match the text read first, then the length.
            val texts = expectTexts
            val exp = texts.firstOrNull { r.ok && it == r.text } ?: texts.firstOrNull { bytesOf(it).size == r.len }
            if (exp != null) {
                expectedText = exp
                val e = BrightFormat.bodyBits(bytesOf(exp), pass, r.nonce)
                    .takeIf { it.size == r.coded.size } ?: r.coded
                bitErr = e.indices.count { e[it] != r.coded[it] }
                val sym = BrightFormat.withPilots(BrightFormat.bitsToSymbols(e, mode), mode).first
                val got = r.bodySyms!!
                nSym = sym.size
                symErr = sym.indices.count { sym[it] != got[it] }
            }
        }
        val result = when {
            r.ok && (expectedText.isEmpty() || r.text == expectedText) -> "RIGHT"
            r.ok -> "READ_DIFFERENT"
            else -> "FAILED: ${r.stage}"
        }
        val from = if (r.phoneTag >= 0) BrightFormat.phoneName(r.phoneTag) else "?"
        val to = BrightFormat.KNOWN_PHONES[Build.MODEL] ?: Build.MODEL
        if (mode != null) {
            val key = "${mode.name} | $from -> $to | $room"
            val t = tally.getOrPut(key) { IntArray(2) }
            t[1]++; if (result == "RIGHT") t[0]++
        }
        lastUsed = emptySet()
        val textSec = if (mode != null) BrightFormat.Layout(mode, r.len).let { it.bodySyms * mode.symSec } else 0.0
        val sendSec = r.endT - r.t0
        lastLine = if (r.ok) "\"${r.text}\"  (${mode?.name})" else "Not read: ${r.stage}"
        return String.format(Locale.US, "%s,%s,%s,%d,%d,%d,%s,%d,%d,%d,%d,%.1f,%.1f,%d,%.1f,%d,%d,%.2f,%s,%.1f,%.1f,%d,%.1f,%s,%s,\"%s\"\n",
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), from, to, mode?.id ?: -1, mode?.levels ?: 0, mode?.perSec ?: 0, result,
            bitErr, r.coded?.size ?: 0, symErr, nSym, textSec, sendSec, r.channels, r.snr, r.levelsApart, r.pminMs, r.syncCorr,
            room, lux, background(), iso, expSec * 1000, paces.replace(",", " "), r.note, r.text.replace("\"", "'")) +
            (if (r.mode != null) "# nonce ${r.nonce}\n" else "")
    }

    fun tallyText(): String = if (tally.isEmpty()) "No sends read yet." else
        tally.entries.joinToString("\n") { "${it.key}:  ${it.value[0]} of ${it.value[1]} right" }

    // ------------------------------------------------------------ light sensor

    private var sm: SensorManager? = null
    private val sensorCb = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    fun startLux() {
        sm = ctx.getSystemService(SensorManager::class.java)
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm?.registerListener(sensorCb, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    fun stopLux() { sm?.unregisterListener(sensorCb) }

    /** Highest frame rate check: how far apart pictures really are (for the results header). */
    fun frameGapMs(): Double = synchronized(lock) {
        val n = minOf(count, 60L).toInt(); if (n < 2) return 0.0
        val a = times[((count - n) % CAP).toInt()]; val b = times[((count - 1) % CAP).toInt()]
        (b - a) / (n - 1) * 1000
    }

    /** Liveliness of each block over the last 3 s (for the camera overlay). */
    fun liveliness(): FloatArray = synchronized(lock) {
        val out = FloatArray(NB * NC)
        val n = minOf(count, 90L).toInt(); if (n < 10) return out
        for (ch in 0 until NB * NC) {
            var s = 0.0; var ss = 0.0
            for (j in 1..n) { val v = vals[((count - j) % CAP).toInt()][ch].toDouble(); s += v; ss += v * v }
            val m = s / n
            out[ch] = max(0.0, ss / n - m * m).toFloat()
        }
        out
    }
}

/** What the sender plays: a list of (mode, text), a pause between sends, an optional beacon. */
class BrightRunPlan(val sends: List<Pair<BrightFormat.Mode, String>>, val gapSec: Double, val beaconSec: Double) {
    fun describe() = "${sends.size} sends, ${gapSec.toInt()} s apart, beacon ${beaconSec} s; modes ${sends.map { it.first.id }.distinct()}"
    companion object {
        /** Each mode in turn; inside a mode, each message `repeats` times. */
        fun build(modes: List<Int>, msgs: List<Int>, repeats: Int, gap: Double, beacon: Double, text: String? = null): BrightRunPlan {
            val list = ArrayList<Pair<BrightFormat.Mode, String>>()
            for (m in modes) for (k in msgs) repeat(repeats) { list.add(BrightFormat.MODES[m] to (text ?: BrightFormat.TEST_MESSAGES[k])) }
            return BrightRunPlan(list, gap, beacon)
        }
        fun from(i: android.content.Intent): BrightRunPlan {
            fun ints(name: String, def: String) = (i.getStringExtra(name) ?: def).split(",").mapNotNull { it.trim().toIntOrNull() }
            val text = i.getStringExtra("text")
            return build(ints("modes", "0,1,2,3,4"), if (text != null) listOf(0) else ints("msgs", "0,1,2,3"),
                i.getIntExtra("repeats", 10), i.getIntExtra("gap", 3).toDouble(), i.getIntExtra("beacon", 0).toDouble(), text)
        }
    }
}
