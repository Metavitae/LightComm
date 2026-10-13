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

    /** One send. beaconSec > 0 plays the passphrase rhythm first (for "Listen for us"). */
    fun send(text: String, mode: BrightFormat.Mode, pass: String, beaconSec: Double, progress: (String) -> Unit): Sent {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = torchCamera(ctx)?.first ?: return Sent(text, mode, 0, 0, 0, 0.0, Stats(emptyList()), Stats(emptyList()), 0.0, "no flashlight")
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val nonce = java.security.SecureRandom().nextInt(65536)
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
        progress("Sync pattern (and timing my own flashlight)...")
        val sync = BrightFormat.syncWave()
        play(BrightFormat.switches(sync), offset, true)
        val pmin = BrightFormat.pminMs(calls)
        val tag = BrightFormat.phoneTag(Build.MODEL)
        val s = BrightFormat.build(text, pass, nonce, mode, tag, pmin)
        progress("Sending \"${text.take(20)}\" in ${mode.name} (shortest pulse $pmin ms)...")
        play(BrightFormat.switches(s.wave, sync.n, s.wave.n, startState = state), offset, true)
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        val ok = running
        running = false
        return Sent(text, mode, nonce, pmin, startMs, offset + s.wave.end, Stats(late), Stats(calls.map { it * 1e6 }),
            calls.firstOrNull()?.times(1e6) ?: 0.0, failure ?: if (!ok) "stopped" else null)
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
class BrightListener(private val ctx: Context, private val pass: String, val expSec: Double) {
    companion object {
        const val NB = 12
        const val NC = 8
        const val NCH = NB * NC + 1
        const val CAP = 30 * 150
        const val HEAD = "time,from_phone,to_phone,mode,levels,per_sec,result,bit_errors_before_repair,coded_bits,symbol_errors,symbols," +
            "text_seconds,send_seconds,channels,signal_to_noise,levels_apart,shortest_pulse_ms,sync_match,room,lux,background,iso,exposure_ms,paces,note,text\n"
    }

    private val times = DoubleArray(CAP)
    private val vals = Array(CAP) { FloatArray(NCH) }
    private var count = 0L     // frames ever added
    private val lock = Any()
    @Volatile var scanFrom = 0.0
    /** Receiver clock time from which pictures count (set once the sensitivity has settled); 0 = not ready yet. */
    @Volatile var readyAt = 0.0
    private var lastWeakT = -1e9
    @Volatile var lux = Float.NaN
    @Volatile var room = "dim"
    @Volatile var paces = "hand-width"
    @Volatile var iso = 0
    var lastUsed: Set<Int> = emptySet()
        private set
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
            count++
        }
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

    fun newest(): Double = synchronized(lock) { if (count == 0L) 0.0 else times[((count - 1) % CAP).toInt()] }

    private val dec = BrightDecoder(pass, expSec)

    /** Look for new sends and read the ones that are complete. Returns lines to log. */
    fun scan(): List<String> {
        val now = newest()
        if (now == 0.0 || readyAt == 0.0) return emptyList()
        if (scanFrom < readyAt) scanFrom = readyAt
        val syncLen = BrightFormat.syncWave().end
        val from = maxOf(scanFrom, now - 140)
        if (now - from < syncLen + 1) return emptyList()
        val ws = window(from - 0.1, now)
        // Find the sync on the whole picture and on the 4 liveliest blocks.
        val lively = (0 until NB * NC).sortedByDescending { variance(ws[it].y) }.take(4)
        val starts = (listOf(NB * NC) + lively).flatMap { dec.findStarts(ws[it], from, now - syncLen - 0.3, 0.8) }
        val t0 = starts.minByOrNull { it.first } ?: run {
            scanFrom = maxOf(scanFrom, now - syncLen - 2)
            // Near-miss note: a sync-like shape that was too weak to read, so a miss shows which side lost it.
            val weak = dec.findStarts(ws[NB * NC], from, now - syncLen - 0.3, 0.5).firstOrNull()
            if (weak != null && weak.first - lastWeakT > 1.0) {
                lastWeakT = weak.first
                return listOf(String.format(Locale.US, "# weak sync, not read: %.1f s after ready, match %.2f\n", weak.first - readyAt, weak.second))
            }
            return emptyList()
        }
        val c = starts.filter { kotlin.math.abs(it.first - t0.first) < 0.05 }.maxOf { it.second }
        val all = window(t0.first - 0.5, now)
        val r = dec.decode(all, t0.first, c)
        val headerDone = t0.first + syncLen + BrightFormat.HDR_SYMS / 15.0 + 1.0
        if (r.mode != null && r.endT > now) return emptyList()         // header read, rest still coming
        if (r.mode == null && now < headerDone) return emptyList()       // wait for the header at the slow speed
        scanFrom = if (r.mode != null) r.endT - 0.2 else t0.first + 1.0
        return listOf(logLine(r))
    }

    private fun variance(y: DoubleArray): Double { if (y.isEmpty()) return 0.0; val m = y.average(); return y.sumOf { (it - m) * (it - m) } / y.size }

    private fun logLine(r: BrightDecoder.Result): String {
        val mode = r.mode
        // Compare with the test messages (same length) to count errors before repair.
        var bitErr = -1; var symErr = -1; var nSym = 0; var expectedText = ""
        if (mode != null && r.coded != null) {
            val exp = BrightFormat.TEST_MESSAGES.firstOrNull { BrightFormat.utf8(it).size == r.len }
            if (exp != null) {
                expectedText = exp
                val e = BrightFormat.bodyBits(BrightFormat.utf8(exp), pass, r.nonce)
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
            room, lux, background(), iso, expSec * 1000, paces.replace(",", " "), r.note, r.text.replace("\"", "'"))
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
