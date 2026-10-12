package com.metavitae.lightlab

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A short text hidden in the rhythm (frequency keying):
 *   5 s beacon (5 flashes per second) so the receiver finds us without aiming,
 *   then every bit is a short stretch of blinking at a slow rhythm (0) or a
 *   fast rhythm (1): a 13-bit start mark, a length byte, the text, a check byte.
 */
object MessageFormat {
    data class Speed(val name: String, val bitSec: Double, val f0: Double, val f1: Double)

    // Each pair of rhythms fits a whole number of flashes into one bit, so they don't blur together.
    val SLOW = Speed("2 bits per second", 0.5, 4.0, 6.0)
    val FAST = Speed("4 bits per second", 0.25, 4.0, 8.0)
    val SPEEDS = listOf(SLOW, FAST)

    const val BEACON_SEC = 5.0
    const val DEFAULT_PASS = "LIGHTCOMM"

    // Beacon rhythms a passphrase can pick from (kept away from the message rhythms 4, 6 and 8).
    private val BEACON_CHOICES = doubleArrayOf(3.0, 3.5, 5.0, 5.5, 6.5, 7.0, 7.5)

    private fun sha(vararg parts: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())

    fun norm(pass: String) = pass.trim().uppercase()

    /** The beacon rhythm (flashes per second) this passphrase uses. */
    fun beaconHz(pass: String): Double = BEACON_CHOICES[(sha("beacon", norm(pass))[0].toInt() and 0xff) % BEACON_CHOICES.size]

    /** 16-bit stamp proving the sender knows the passphrase. */
    fun tag(pass: String, nonce: Int): Int { val h = sha("tag", norm(pass), nonce.toString()); return ((h[0].toInt() and 0xff) shl 8) or (h[1].toInt() and 0xff) }

    /** Bytes that scramble the text (lab-grade, not reviewed security). */
    fun keystream(pass: String, nonce: Int, n: Int): ByteArray {
        val out = ByteArray(n)
        var block = 0
        var i = 0
        while (i < n) {
            val h = sha("key", norm(pass), nonce.toString(), block.toString())
            for (b in h) { if (i < n) out[i++] = b }
            block++
        }
        return out
    }
    val START = intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 1)  // Barker 13

    fun crc8(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = c xor (b.toInt() and 0xff)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xff else (c shl 1) and 0xff }
        }
        return c
    }

    /**
     * start mark | stamp (16) | nonce (16) | length | text | check byte.
     * Length, text and check byte are scrambled with the passphrase.
     */
    fun bits(text: String, pass: String, nonce: Int): IntArray {
        val data = text.toByteArray(Charsets.US_ASCII).take(40).toByteArray()
        val ks = keystream(pass, nonce, data.size + 2)
        val out = ArrayList<Int>()
        START.forEach { out.add(it) }
        fun bitsOf(v: Int, n: Int) { for (i in n - 1 downTo 0) out.add((v shr i) and 1) }
        bitsOf(tag(pass, nonce), 16)
        bitsOf(nonce, 16)
        bitsOf(data.size xor (ks[0].toInt() and 0xff), 8)
        data.forEachIndexed { i, b -> bitsOf((b.toInt() xor ks[i + 1].toInt()) and 0xff, 8) }
        bitsOf(crc8(data) xor (ks[data.size + 1].toInt() and 0xff), 8)
        return out.toIntArray()
    }
}

class MessageSender(private val ctx: Context) {
    @Volatile var running = false

    /** Light switch times (seconds from start, on/off) for one beacon + message. */
    private fun schedule(text: String, sp: MessageFormat.Speed, pass: String, nonce: Int): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        fun square(start: Double, len: Double, hz: Double) {
            val half = 1.0 / (2 * hz)
            var t = 0.0
            var on = true
            while (t < len - 1e-9) { out.add(start + t to on); on = !on; t += half }
        }
        square(0.0, MessageFormat.BEACON_SEC, MessageFormat.beaconHz(pass))
        var t = MessageFormat.BEACON_SEC
        for (b in MessageFormat.bits(text, pass, nonce)) {
            square(t, sp.bitSec, if (b == 1) sp.f1 else sp.f0)
            t += sp.bitSec
        }
        out.add(t to false)
        return out
    }

    fun run(text: String, sp: MessageFormat.Speed, repeats: Int, pass: String, progress: (String) -> Unit): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val (id, _) = torchCamera(ctx) ?: return "This phone has no flashlight an app can use."
        val nonce = java.security.SecureRandom().nextInt(65536)
        val sched = schedule(text, sp, pass, nonce)
        val frameLen = sched.last().first + 2.0  // 2 s dark between repeats
        val sb = StringBuilder(Results.header("Message sender"))
        sb.append("Text: \"$text\"\nSpeed: ${sp.name} (0 = ${sp.f0} flashes/s, 1 = ${sp.f1} flashes/s, ${sp.bitSec} s per bit)\n")
        sb.append("Passphrase beacon rhythm: ${MessageFormat.beaconHz(pass)} flashes/s (passphrase itself not saved)\n")
        sb.append("Bits: ${MessageFormat.bits(text, pass, nonce).size}, one send takes ${"%.1f".format(sched.last().first)} s, repeats: $repeats\n")
        running = true
        val late = mutableListOf<Double>()
        var failure: String? = null
        val start = System.nanoTime() + 300_000_000
        sb.append("Start elapsedRealtime: ${SystemClock.elapsedRealtime()} ms\n")
        loop@ for (r in 0 until repeats) {
            for ((t, on) in sched) {
                if (!running) break@loop
                val target = start + ((r * frameLen + t) * 1e9).toLong()
                while (true) {
                    val left = target - System.nanoTime()
                    if (left <= 0) break
                    if (left > 2_000_000) Thread.sleep((left - 1_500_000) / 1_000_000) else Thread.onSpinWait()
                }
                late.add((System.nanoTime() - target) / 1000.0)
                try { cm.setTorchMode(id, on) } catch (e: Exception) { failure = e.message; break@loop }
            }
            progress("Sent ${r + 1} of $repeats")
        }
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        running = false
        sb.append(if (failure == null) "Finished.\n" else "STOPPED: flashlight refused: $failure\n")
        sb.append("Switch started late by (microseconds): ${Stats(late).line("")}\n")
        return sb.toString()
    }
}

/** Reads a message from one brightness channel (the spot or zone where we were heard). */
class MessageDecoder(private val sp: MessageFormat.Speed, private val pass: String) {
    private val t = ArrayList<Double>()
    private val v = ArrayList<Float>()

    fun add(tSec: Double, value: Float) {
        t.add(tSec); v.add(value)
        if (t.size > 30 * 120) { t.subList(0, 30 * 20).clear(); v.subList(0, 30 * 20).clear() }  // keep the last ~100 s
    }

    /** A frozen copy, so decoding can run on another thread while the camera keeps adding. */
    fun copy(): MessageDecoder { val d = MessageDecoder(sp, pass); d.t.addAll(t); d.v.addAll(v); return d }
    fun reset() { t.clear(); v.clear() }

    /** forUs = false: a well-formed message with someone else's stamp (wrong passphrase); text is then empty. */
    data class Decoded(val text: String, val confidence: Double, val atSec: Double, val forUs: Boolean = true)

    /** +1 = clearly the fast rhythm (1), -1 = clearly the slow one (0). */
    private fun metric(from: Int, start: Double): Pair<Double, Int> {
        var i = from
        while (i < t.size && t[i] < start) i++
        val first = i
        var mean = 0.0
        var n = 0
        while (i < t.size && t[i] < start + sp.bitSec) { mean += v[i]; n++; i++ }
        if (n < 4) return 0.0 to first
        mean /= n
        var c0 = 0.0; var s0 = 0.0; var c1 = 0.0; var s1 = 0.0
        for (k in first until first + n) {
            val x = v[k] - mean
            val a = t[k] - start
            c0 += x * cos(2 * PI * sp.f0 * a); s0 += x * sin(2 * PI * sp.f0 * a)
            c1 += x * cos(2 * PI * sp.f1 * a); s1 += x * sin(2 * PI * sp.f1 * a)
        }
        val p0 = c0 * c0 + s0 * s0
        val p1 = c1 * c1 + s1 * s1
        return (p1 - p0) / (p1 + p0 + 1e-9) to first
    }

    /** Try every possible start; return the newest message whose check byte is right. */
    fun tryDecode(): Decoded? {
        if (t.size < 30) return null
        val start = MessageFormat.START
        val step = 1.0 / 60
        val cands = ArrayList<Pair<Double, Double>>()  // (score, time)
        var tau = t.first()
        val last = t.last()
        var from = 0
        while (tau + start.size * sp.bitSec <= last) {
            var score = 0.0
            var f = from
            for (k in start.indices) {
                val (m, idx) = metric(f, tau + k * sp.bitSec)
                if (k == 0) from = idx
                f = idx
                score += if (start[k] == 1) m else -m
            }
            if (score > 0.55 * start.size) cands.add(score to tau)
            tau += step
        }
        cands.sortByDescending { it.first }
        val tried = ArrayList<Double>()
        var bestFound: Decoded? = null
        for ((_, c) in cands.take(40)) {
            if (tried.any { abs(it - c) < sp.bitSec / 2 }) continue
            tried.add(c)
            val d = decodeAt(c) ?: continue
            // A message for us always wins over a foreign one.
            if (bestFound == null || (d.forUs && !bestFound.forUs) || (d.forUs == bestFound.forUs && d.atSec > bestFound.atSec)) bestFound = d
        }
        return bestFound
    }

    private fun decodeAt(t0: Double): Decoded? {
        var pos = t0 + MessageFormat.START.size * sp.bitSec
        val conf = ArrayList<Double>()
        fun readBits(n: Int): Int? {
            var b = 0
            for (i in 0 until n) {
                if (pos + sp.bitSec > t.last()) return null
                val (m, _) = metric(0, pos)
                conf.add(abs(m))
                b = (b shl 1) or (if (m > 0) 1 else 0)
                pos += sp.bitSec
            }
            return b
        }
        val stamp = readBits(16) ?: return null
        val nonce = readBits(16) ?: return null
        if (stamp != MessageFormat.tag(pass, nonce)) {
            // Clean, confident bits but not our stamp: someone signalling with another passphrase.
            return if (conf.average() > 0.6) Decoded("", conf.average(), t0, forUs = false) else null
        }
        val ks0 = MessageFormat.keystream(pass, nonce, 1)[0].toInt() and 0xff
        val len = (readBits(8) ?: return null) xor ks0
        if (len !in 1..40) return null
        val ks = MessageFormat.keystream(pass, nonce, len + 2)
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = ((readBits(8) ?: return null) xor (ks[i + 1].toInt() and 0xff)).toByte()
        val crc = (readBits(8) ?: return null) xor (ks[len + 1].toInt() and 0xff)
        if (crc != MessageFormat.crc8(data)) return null
        val text = String(data, Charsets.US_ASCII)
        return Decoded(text, conf.average(), t0)
    }
}
