package com.metavitae.lightlab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two-way talk at the same time. Each phone blinks its own steady rhythm
 * through its own camera, one camera picture at a time, and listens for the
 * other phone's rhythm only.
 *
 * Rhythms are chosen so that, at 30 pictures per second and 12 pictures per
 * bit, neither phone's light (or its echoes in the picture) lands on the
 * rhythm the other one is listening for:
 *   Caller   (A): 7.5 flashes/s (2 pictures on, 2 off)
 *   Answerer (B): 5 flashes/s   (3 pictures on, 3 off)
 * A bit is 12 pictures (0.4 s). Bit 1 = the rhythm skips half a beat
 * (phase flip), bit 0 = it carries on. So each phone keeps one steady rhythm.
 */
object DuplexFormat {
    data class Role(val name: String, val halfFrames: Int) {
        val hz get() = 15.0 / halfFrames
    }

    // Adjustable for testing (adb extras): how many pictures the light stays on/off, and pictures per bit.
    var CALLER = Role("Caller", 2)
    var ANSWERER = Role("Answerer", 3)
    var BIT_FRAMES = 12
    val BIT_SEC get() = BIT_FRAMES / 30.0

    fun other(r: Role) = if (r == CALLER) ANSWERER else CALLER

    /** Flashlight state for picture j of a bit, given the phase. */
    fun state(r: Role, j: Int, phase: Int) = ((j / r.halfFrames) % 2 == 0) xor (phase == 1)

    /** One bit period's flashlight states. */
    fun bitFrames(r: Role, phase: Int) = BooleanArray(BIT_FRAMES) { state(r, it, phase) }

    /** Steady rhythm (no message), as a repeating pattern. */
    fun carrier(r: Role) = BooleanArray(2 * r.halfFrames) { it < r.halfFrames }

    /**
     * Read the part after the start mark: Hamming-coded stamp, nonce,
     * length, text, check byte. Same layout as MessageFormat.bits.
     */
    fun parse(raw: IntArray, from: Int, pass: String): MessageDecoder.Decoded? {
        var pos = from
        val pending = ArrayDeque<Int>()
        fun readBits(n: Int): Int? {
            var b = 0
            for (i in 0 until n) {
                if (pending.isEmpty()) {
                    if (pos + 7 > raw.size) return null
                    MessageFormat.hammingDecode(raw.copyOfRange(pos, pos + 7)).forEach { pending.addLast(it) }
                    pos += 7
                }
                b = (b shl 1) or pending.removeFirst()
            }
            return b
        }
        val stamp = readBits(16) ?: return null
        val nonce = readBits(16) ?: return null
        if (stamp != MessageFormat.tag(pass, nonce)) return MessageDecoder.Decoded("", 0.0, 0.0, forUs = false)
        val ks0 = MessageFormat.keystream(pass, nonce, 1)[0].toInt() and 0xff
        val len = (readBits(8) ?: return null) xor ks0
        if (len !in 1..40) return null
        val ks = MessageFormat.keystream(pass, nonce, len + 2)
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = ((readBits(8) ?: return null) xor (ks[i + 1].toInt() and 0xff)).toByte()
        val crc = (readBits(8) ?: return null) xor (ks[len + 1].toInt() and 0xff)
        if (crc != MessageFormat.crc8(data)) return null
        return MessageDecoder.Decoded(String(data, Charsets.US_ASCII), 1.0, 0.0)
    }
}

/** Sends a message as phase flips on this phone's rhythm, keeping a few bits queued ahead in the camera. */
class DuplexSender(private val cam: CamSession, private val role: DuplexFormat.Role) {
    @Volatile var running = false
    @Volatile var progress = ""

    fun send(text: String, pass: String) {
        val nonce = java.security.SecureRandom().nextInt(65536)
        val bits = MessageFormat.bits(text.uppercase(), pass, nonce)
        running = true
        // A short run of plain rhythm first, so the start mark has a reference beat.
        val all = IntArray(4) + bits
        var phase = 0
        for ((k, b) in all.withIndex()) {
            if (!running) break
            while (cam.queuedBursts >= 3 && running) Thread.sleep(20)
            if (b == 1) phase = phase xor 1
            cam.queueTorch(DuplexFormat.bitFrames(role, phase))
            progress = "Sending: bit ${k + 1} of ${all.size}"
        }
        while (cam.queuedBursts > 0 && running) Thread.sleep(50)
        // Back to the plain rhythm, in the phase we ended on.
        cam.torchPattern = BooleanArray(2 * role.halfFrames) { DuplexFormat.state(role, it, phase) }
        cam.apply()
        progress = if (running) "Sent: \"$text\" (${bits.size} bits)" else "Stopped"
        running = false
    }
}

/** Reads the other phone's phase flips from one brightness channel. */
class DuplexDecoder(private val hz: Double, private val pass: String) {
    private val t = ArrayList<Double>()
    private val v = ArrayList<Float>()

    fun add(tSec: Double, value: Float) {
        t.add(tSec); v.add(value)
        if (t.size > 30 * 200) { t.subList(0, 30 * 30).clear(); v.subList(0, 30 * 30).clear() }
    }
    fun reset() { t.clear(); v.clear() }
    fun copy(): DuplexDecoder { val d = DuplexDecoder(hz, pass); d.t.addAll(t); d.v.addAll(v); return d }

    /** Complex strength of the rhythm in each bit window starting at tau. */
    private fun windows(tau: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var start = tau
        while (start + DuplexFormat.BIT_SEC <= t.last()) {
            while (i < t.size && t[i] < start) i++
            var j = i
            var mean = 0.0
            while (j < t.size && t[j] < start + DuplexFormat.BIT_SEC) { mean += v[j]; j++ }
            val n = j - i
            if (n < 6) { out.add(0.0 to 0.0); start += DuplexFormat.BIT_SEC; continue }
            mean /= n
            var c = 0.0
            var s = 0.0
            for (k in i until j) {
                val x = v[k] - mean
                // Phase measured against an absolute clock, so a flip shows as a jump between windows.
                c += x * cos(2 * PI * hz * t[k]); s += x * sin(2 * PI * hz * t[k])
            }
            out.add(c to s)
            start += DuplexFormat.BIT_SEC
        }
        return out
    }

    fun tryDecode(): MessageDecoder.Decoded? {
        if (t.size < 60) return null
        var best: Pair<Double, IntArray>? = null
        var bestConf = 0.0
        val steps = 12
        for (st in 0 until steps) {
            val w = windows(t.first() + st * DuplexFormat.BIT_SEC / steps)
            if (w.size < 20) return null
            val bits = IntArray(w.size)
            var conf = 0.0
            for (k in 1 until w.size) {
                val (c1, s1) = w[k - 1]
                val (c2, s2) = w[k]
                val dot = c1 * c2 + s1 * s2
                val mag = sqrt((c1 * c1 + s1 * s1) * (c2 * c2 + s2 * s2)) + 1e-9
                bits[k] = if (dot < 0) 1 else 0
                conf += abs(dot) / mag
            }
            conf /= (w.size - 1)
            if (conf > bestConf) { bestConf = conf; best = conf to bits }
        }
        val bits = best?.second ?: return null
        // Find the start mark (allow one wrong bit) and read the newest message after it.
        val start = MessageFormat.START
        var found: MessageDecoder.Decoded? = null
        for (k in 0..bits.size - start.size) {
            var wrong = 0
            for (j in start.indices) if (bits[k + j] != start[j]) wrong++
            if (wrong > 1) continue
            val d = DuplexFormat.parse(bits, k + start.size, pass) ?: continue
            if (!d.forUs && wrong > 0) continue
            if (found == null || d.forUs || !found.forUs) found = d.copy(confidence = bestConf, atSec = t.first() + k * DuplexFormat.BIT_SEC)
        }
        return found
    }

    fun dump(): String { val sb = StringBuilder(); for (i in t.indices) sb.append(String.format(java.util.Locale.US, "%.4f,%.2f\n", t[i], v[i])); return sb.toString() }
}
