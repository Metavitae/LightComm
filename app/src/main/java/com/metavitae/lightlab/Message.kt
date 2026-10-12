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
    const val BEACON_HZ = 5.0
    val START = intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 1)  // Barker 13

    fun crc8(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = c xor (b.toInt() and 0xff)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xff else (c shl 1) and 0xff }
        }
        return c
    }

    fun bits(text: String): IntArray {
        val data = text.toByteArray(Charsets.US_ASCII).take(40).toByteArray()
        val out = ArrayList<Int>()
        START.forEach { out.add(it) }
        fun byte(v: Int) { for (i in 7 downTo 0) out.add((v shr i) and 1) }
        byte(data.size)
        data.forEach { byte(it.toInt() and 0xff) }
        byte(crc8(data))
        return out.toIntArray()
    }
}

class MessageSender(private val ctx: Context) {
    @Volatile var running = false

    /** Light switch times (seconds from start, on/off) for one beacon + message. */
    private fun schedule(text: String, sp: MessageFormat.Speed): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        fun square(start: Double, len: Double, hz: Double) {
            val half = 1.0 / (2 * hz)
            var t = 0.0
            var on = true
            while (t < len - 1e-9) { out.add(start + t to on); on = !on; t += half }
        }
        square(0.0, MessageFormat.BEACON_SEC, MessageFormat.BEACON_HZ)
        var t = MessageFormat.BEACON_SEC
        for (b in MessageFormat.bits(text)) {
            square(t, sp.bitSec, if (b == 1) sp.f1 else sp.f0)
            t += sp.bitSec
        }
        out.add(t to false)
        return out
    }

    fun run(text: String, sp: MessageFormat.Speed, repeats: Int, progress: (String) -> Unit): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val (id, _) = torchCamera(ctx) ?: return "This phone has no flashlight an app can use."
        val sched = schedule(text, sp)
        val frameLen = sched.last().first + 2.0  // 2 s dark between repeats
        val sb = StringBuilder(Results.header("Message sender"))
        sb.append("Text: \"$text\"\nSpeed: ${sp.name} (0 = ${sp.f0} flashes/s, 1 = ${sp.f1} flashes/s, ${sp.bitSec} s per bit)\n")
        sb.append("Bits: ${MessageFormat.bits(text).size}, one send takes ${"%.1f".format(sched.last().first)} s, repeats: $repeats\n")
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
class MessageDecoder(private val sp: MessageFormat.Speed) {
    private val t = ArrayList<Double>()
    private val v = ArrayList<Float>()

    fun add(tSec: Double, value: Float) {
        t.add(tSec); v.add(value)
        if (t.size > 30 * 120) { t.subList(0, 30 * 20).clear(); v.subList(0, 30 * 20).clear() }  // keep the last ~100 s
    }

    /** A frozen copy, so decoding can run on another thread while the camera keeps adding. */
    fun copy(): MessageDecoder { val d = MessageDecoder(sp); d.t.addAll(t); d.v.addAll(v); return d }
    fun reset() { t.clear(); v.clear() }

    data class Decoded(val text: String, val confidence: Double, val atSec: Double)

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
            if (bestFound == null || d.atSec > bestFound.atSec) bestFound = d
        }
        return bestFound
    }

    private fun decodeAt(t0: Double): Decoded? {
        var pos = t0 + MessageFormat.START.size * sp.bitSec
        val conf = ArrayList<Double>()
        fun readByte(): Int? {
            var b = 0
            for (i in 0 until 8) {
                if (pos + sp.bitSec > t.last()) return null
                val (m, _) = metric(0, pos)
                conf.add(abs(m))
                b = (b shl 1) or (if (m > 0) 1 else 0)
                pos += sp.bitSec
            }
            return b
        }
        val len = readByte() ?: return null
        if (len !in 1..40) return null
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = (readByte() ?: return null).toByte()
        val crc = readByte() ?: return null
        if (crc != MessageFormat.crc8(data)) return null
        val text = String(data, Charsets.US_ASCII)
        return Decoded(text, conf.average(), t0)
    }
}
