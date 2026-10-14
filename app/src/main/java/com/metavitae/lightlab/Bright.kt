package com.metavitae.lightlab

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Messages in brightness steps (no Android code here, so it can be simulated on a computer).
 *
 * The flashlight is on/off only, and every switch takes the phone several milliseconds
 * (Oct 11: POCO about 6 ms, 8T about 11 ms). A brightness step is made by how long the
 * light stays on at the start of each symbol; no pulse or gap is ever shorter than the
 * sender's own measured switch time ("shortest pulse", sent in the header).
 * The receiver takes 30 pictures per second, each exposed about 1/30 s, and works out
 * what each picture should have seen for every possible symbol (a light model).
 *
 * One send, in order:
 *   dark 0.5 s | sync: a fixed on/off pattern of 62 slots of 1/30 s (2 s), during which the
 *   sender also measures its own switch time | start mark (Barker 7) | header | mode
 *   staircase (each level held 3 symbols, low to high) | text, with a pilot mini-staircase
 *   after every 32 symbols | dark tail.
 * Start mark and header are on/off only, at the text's speed (30, 15 or 10 symbols per second).
 * Header: stamp 16 | nonce 16 | mode 4 (top bit = packed text) | phone tag 4 | shortest pulse (ms) 4 | length 8 | header check byte 8 (Hamming 7/4, interleaved).
 * Text: packed (TextPack, when shorter) or UTF-8 bytes + 16-bit check (CRC-16), scrambled with the passphrase, Hamming 7/4, interleaved.
 */
object BrightFormat {
    const val SLOT = 1.0 / 30
    const val DARK = 15
    /** 31-step pattern (maximum-length sequence) from a 5-bit shift register with feedback from bits a and b. */
    private fun mseq(a: Int, b: Int): IntArray { var reg = 0x1F; return IntArray(31) { val x = ((reg shr a) xor (reg shr b)) and 1; reg = ((reg shl 1) or x) and 0x1F; x } }
    /** Test pattern played twice: sharp to find in time. */
    val SYNC: IntArray = mseq(4, 2).let { it + it }
    val SYNC_SLOTS = DARK + SYNC.size
    /**
     * Short opening, once two phones are linked: a different 31-step pattern, played once (about 1 s).
     * It matches the full sync at most 0.42 (the receiver needs 0.8), so the two are never confused.
     */
    val SHORT_SYNC: IntArray = mseq(4, 1)
    /** Short header: mode 4 | length 8 | check byte 8 (keyed with the passphrase and the link). */
    const val SHORT_HDR_BITS = 20
    const val TAIL = 9
    val BARKER = intArrayOf(1, 1, 1, 0, 0, 1, 0)
    const val HDR_BITS = 60
    val HDR_SYMS = BARKER.size + HDR_BITS / 4 * 7
    val SHORT_HDR_SYMS = BARKER.size + SHORT_HDR_BITS / 4 * 7
    const val TRAIN_REPEAT = 3
    const val PILOT_EVERY = 32
    const val MAX_BYTES = 80

    data class Mode(val id: Int, val levels: Int, val perSec: Int, val group: Int, val bits: Int) {
        val symSec get() = 1.0 / perSec
        val name get() = "$levels levels, $perSec per second"
        val rawBitsPerSec get() = perSec.toDouble() * bits / group
        val pilot: IntArray get() = if (levels >= 4) intArrayOf(0, (levels - 1) / 3, 2 * (levels - 1) / 3, levels - 1)
                                    else intArrayOf(0, levels - 1, 0, levels - 1)
        /** Seconds the light is on at the start of a symbol of this level. */
        fun onSec(level: Int, pminSec: Double): Double {
            val ts = symSec
            return when (level) {
                0 -> 0.0
                levels - 1 -> ts
                else -> (level * ts / (levels - 1)).coerceIn(pminSec, ts - pminSec)
            }
        }
    }

    val MODES = listOf(
        Mode(0, 2, 30, 1, 1),
        Mode(1, 3, 30, 2, 3),
        Mode(2, 4, 15, 1, 2),
        Mode(3, 5, 15, 4, 9),
        Mode(4, 8, 15, 1, 3),
        // Long range (Oct 13, 5-6 m: 4 levels at 15/s beat 2 levels at 30/s): longer pulses carry more light per symbol.
        Mode(5, 2, 15, 1, 1),
        Mode(6, 2, 10, 1, 1),
        Mode(7, 4, 10, 1, 2),
    )
    /** Every text speed in use, fastest first (the receiver tries the header at each). */
    val RATES: List<Int> = MODES.map { it.perSec }.distinct().sortedDescending()

    val TEST_MESSAGES = listOf(
        "HOLA AMIGO",
        "MEET AT THE TRUCK 8PM",
        "MEET ME AT THE NORTH GATE AT 9PM. BRING WATER AND TWO LIGHTS",
        "NOS VEMOS EN LA CAMIONETA A LAS 8PM. TRAE AGUA Y LA LÁMPARA.",
    )

    /** Short tag for a phone model (sent in the header so the receiver knows who sent). */
    fun phoneTag(model: String): Int = (model.trim().uppercase().fold(7) { h, c -> h * 31 + c.code } and 0x7fffffff) % 16
    val KNOWN_PHONES = mapOf("2312FPCA6G" to "POCO M6 Pro", "KB2007" to "OnePlus 8T")
    fun phoneName(tag: Int): String = KNOWN_PHONES.entries.firstOrNull { phoneTag(it.key) == tag }?.value ?: "phone tag $tag"

    /** Shortest pulse to use, from the sender's own switch times (seconds): a bit above most of them. */
    fun pminMs(callSec: List<Double>): Int {
        if (callSec.isEmpty()) return 12
        val s = callSec.sorted()
        return (ceil(s[(s.size * 0.9).toInt().coerceAtMost(s.size - 1)] * 1000) + 1).toInt().coerceIn(4, 15)
    }

    // ------------------------------------------------------------ levels <-> bits

    /** Reflected Gray order of all level-tuples: neighbours in this list differ in one symbol by one step. */
    private fun grayTuples(l: Int, g: Int): List<IntArray> {
        if (g == 1) return (0 until l).map { intArrayOf(it) }
        val prev = grayTuples(l, g - 1)
        val out = ArrayList<IntArray>()
        for (d in 0 until l) for (p in if (d % 2 == 0) prev else prev.asReversed()) out.add(intArrayOf(d) + p)
        return out
    }

    class Codebook(val mode: Mode) {
        private val tuples = grayTuples(mode.levels, mode.group).take(1 shl mode.bits)
        private val keyToLabel = HashMap<Int, Int>()
        private fun key(t: IntArray) = t.fold(0) { k, d -> k * mode.levels + d }
        private val nearest = IntArray(Math.pow(mode.levels.toDouble(), mode.group.toDouble()).toInt())
        init {
            tuples.forEachIndexed { i, t -> keyToLabel[key(t)] = i xor (i shr 1) }
            // Any tuple not in use reads as the closest one in use.
            for (k in nearest.indices) {
                val digits = IntArray(mode.group).also { var x = k; for (j in mode.group - 1 downTo 0) { it[j] = x % mode.levels; x /= mode.levels } }
                var best = 0; var bestD = Int.MAX_VALUE
                tuples.forEachIndexed { i, t -> val d = t.indices.sumOf { abs(t[it] - digits[it]) }; if (d < bestD) { bestD = d; best = i } }
                nearest[k] = best xor (best shr 1)
            }
        }
        /** label (Gray-coded bits) -> tuple */
        fun encode(label: Int): IntArray { var i = label; var s = label shr 1; while (s != 0) { i = i xor s; s = s shr 1 }; return tuples[i] }
        fun decode(t: IntArray): Int = keyToLabel[key(t)] ?: nearest[key(t)]
    }

    // ------------------------------------------------------------ bits

    fun bitsOf(v: Int, n: Int, out: MutableList<Int>) { for (i in n - 1 downTo 0) out.add((v shr i) and 1) }
    fun hamming(bits: List<Int>): IntArray {
        val out = ArrayList<Int>()
        for (i in bits.indices step 4) out.addAll(MessageFormat.hammingEncode(bits[i], bits[i + 1], bits[i + 2], bits[i + 3]).toList())
        return out.toIntArray()
    }
    fun unhamming(coded: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until coded.size / 7) out.addAll(MessageFormat.hammingDecode(coded.copyOfRange(i * 7, i * 7 + 7)).toList())
        return out.toIntArray()
    }
    /** Block interleaver: codewords as rows, sent column by column. */
    fun interleave(coded: IntArray): IntArray { val n = coded.size / 7; return IntArray(coded.size) { k -> coded[(k % n) * 7 + k / n] } }
    fun deinterleave(sent: IntArray): IntArray { val n = sent.size / 7; val out = IntArray(sent.size); for (k in sent.indices) out[(k % n) * 7 + k / n] = sent[k]; return out }

    fun headerBits(pass: String, nonce: Int, mode: Mode, tag: Int, pminMs: Int, len: Int, packed: Boolean = false): IntArray {
        val b = ArrayList<Int>()
        bitsOf(MessageFormat.tag(pass, nonce), 16, b)
        bitsOf(nonce, 16, b)
        bitsOf(mode.id or (if (packed) 8 else 0), 4, b)
        bitsOf(tag, 4, b)
        bitsOf(pminMs, 4, b)
        bitsOf(len xor (MessageFormat.keystream(pass, nonce, 1)[0].toInt() and 0xff), 8, b)
        bitsOf(headerCheck(b), 8, b)
        return interleave(hamming(b))
    }

    /** Check byte over the first 52 header bits. */
    fun headerCheck(bits: List<Int>): Int {
        val bytes = ByteArray(7) { k -> var v = 0; for (i in 0 until 8) v = (v shl 1) or (bits.getOrElse(k * 8 + i) { 0 }); v.toByte() }
        return MessageFormat.crc8(bytes)
    }

    /**
     * A link: after one full send was answered "got it", follow-up sends use the short opening.
     * id = nonce of that first message; each follow-up has a number (seq 1, 2, ...) and its own
     * nonce made from the passphrase, the link and that number, so it never travels in the light.
     * The receiver knows the sender's shortest pulse and phone tag from the first message.
     */
    data class Link(val id: Int, val tag: Int, val pminMs: Int, val lastSeq: Int)
    fun seqNonce(pass: String, linkId: Int, seq: Int): Int {
        val k = MessageFormat.keystream("$pass/link/$seq", linkId, 2)
        return ((k[0].toInt() and 0xff) shl 8) or (k[1].toInt() and 0xff)
    }
    /** Short header's check byte: over nonce, mode and length, plus the passphrase stamp (a stranger can't make it). */
    fun shortCheck(pass: String, nonce: Int, modeNibble: Int, len: Int): Int {
        val st = MessageFormat.tag(pass, nonce)
        return MessageFormat.crc8(byteArrayOf((nonce shr 8).toByte(), nonce.toByte(), modeNibble.toByte(), len.toByte(), (st shr 8).toByte(), st.toByte()))
    }
    fun shortHeaderBits(pass: String, nonce: Int, mode: Mode, len: Int, packed: Boolean): IntArray {
        val b = ArrayList<Int>()
        val m = mode.id or (if (packed) 8 else 0)
        bitsOf(m, 4, b); bitsOf(len, 8, b); bitsOf(shortCheck(pass, nonce, m, len), 8, b)
        return interleave(hamming(b))
    }

    /** CRC-16 (CCITT): one wrong message in ~65,000 slips through, instead of one in 256 with 8 bits. */
    fun crc16(data: ByteArray): Int {
        var c = 0xFFFF
        for (b in data) {
            c = c xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** Coded (protected, interleaved) text bits, before they become symbols. */
    fun bodyBits(data: ByteArray, pass: String, nonce: Int): IntArray {
        val ks = MessageFormat.keystream(pass, nonce, data.size + 3)
        val b = ArrayList<Int>()
        data.forEachIndexed { i, x -> bitsOf((x.toInt() xor ks[i + 1].toInt()) and 0xff, 8, b) }
        val crc = crc16(data)
        bitsOf(((crc shr 8) xor ks[data.size + 1].toInt()) and 0xff, 8, b)
        bitsOf((crc xor ks[data.size + 2].toInt()) and 0xff, 8, b)
        return interleave(hamming(b))
    }

    fun codedBodyBitCount(len: Int) = (len + 2) * 14
    fun dataSymbolCount(len: Int, mode: Mode) = ceil(codedBodyBitCount(len).toDouble() / mode.bits).toInt() * mode.group

    /** Data symbols with pilots inserted; returns (symbols, isPilot). */
    fun withPilots(data: IntArray, mode: Mode): Pair<IntArray, BooleanArray> {
        val s = ArrayList<Int>(); val p = ArrayList<Boolean>()
        var i = 0
        while (i < data.size) {
            val end = min(data.size, i + PILOT_EVERY)
            for (k in i until end) { s.add(data[k]); p.add(false) }
            for (v in mode.pilot) { s.add(v); p.add(true) }
            i = end
        }
        return s.toIntArray() to p.toBooleanArray()
    }

    fun bitsToSymbols(bits: IntArray, mode: Mode): IntArray {
        val cb = Codebook(mode)
        val out = ArrayList<Int>()
        var i = 0
        while (i < bits.size) {
            var label = 0
            for (k in 0 until mode.bits) label = (label shl 1) or (if (i + k < bits.size) bits[i + k] else 0)
            out.addAll(cb.encode(label).toList())
            i += mode.bits
        }
        return out.toIntArray()
    }
    fun symbolsToBits(syms: IntArray, mode: Mode, nBits: Int): IntArray {
        val cb = Codebook(mode)
        val out = ArrayList<Int>()
        for (i in 0 until syms.size / mode.group) bitsOf(cb.decode(syms.copyOfRange(i * mode.group, (i + 1) * mode.group)), mode.bits, out)
        return out.take(nBits).toIntArray()
    }

    /**
     * Soft reading: per data symbol, how unlikely each level is (0 = best). Gives, per coded bit,
     * "cost of 1 minus cost of 0" (positive = probably 0).
     */
    fun softBits(soft: List<DoubleArray>, mode: Mode, nBits: Int): DoubleArray {
        val cb = Codebook(mode)
        val out = ArrayList<Double>()
        val labels = 1 shl mode.bits
        val tuples = Array(labels) { cb.encode(it) }
        for (g in 0 until soft.size / mode.group) {
            val cost = DoubleArray(labels) { l -> var c = 0.0; for (d in 0 until mode.group) c += soft[g * mode.group + d][tuples[l][d]]; c }
            for (k in mode.bits - 1 downTo 0) {
                var c0 = Double.MAX_VALUE; var c1 = Double.MAX_VALUE
                for (l in 0 until labels) if ((l shr k) and 1 == 0) c0 = min(c0, cost[l]) else c1 = min(c1, cost[l])
                out.add(c1 - c0)
            }
        }
        return out.take(nBits).toDoubleArray()
    }

    /** Best Hamming codeword for 7 soft bits (tries all 16), returns its 4 data bits. */
    fun softHamming(llr: DoubleArray, at: Int): IntArray {
        var best = 0; var bestC = Double.MAX_VALUE
        for (v in 0 until 16) {
            val cw = MessageFormat.hammingEncode((v shr 3) and 1, (v shr 2) and 1, (v shr 1) and 1, v and 1)
            var c = 0.0
            for (k in 0 until 7) { val l = llr[at + k]; c += if (cw[k] == 0) max(0.0, -l) else max(0.0, l) }
            if (c < bestC) { bestC = c; best = v }
        }
        return intArrayOf((best shr 3) and 1, (best shr 2) and 1, (best shr 1) and 1, best and 1)
    }

    fun softDecode(llrSent: DoubleArray): IntArray {
        val n = llrSent.size / 7
        val llr = DoubleArray(llrSent.size); for (k in llrSent.indices) llr[(k % n) * 7 + k / n] = llrSent[k]
        val out = ArrayList<Int>()
        for (i in 0 until n) out.addAll(softHamming(llr, i * 7).toList())
        return out.toIntArray()
    }

    fun trainSymbols(mode: Mode) = IntArray(mode.levels * TRAIN_REPEAT) { it / TRAIN_REPEAT }

    // ------------------------------------------------------------ the light over time

    /**
     * The flashlight over time as back-to-back symbols: symbol i starts at start[i] seconds,
     * lasts dur[i], and the light is on for its first on[i] seconds (NaN = not known yet).
     */
    class Wave {
        var start = DoubleArray(256); var dur = DoubleArray(256); var on = DoubleArray(256)
        var n = 0
            private set
        val end get() = if (n == 0) 0.0 else start[n - 1] + dur[n - 1]
        fun add(d: Double, onSec: Double): Int {
            if (n == start.size) { start = start.copyOf(n * 2); dur = dur.copyOf(n * 2); on = on.copyOf(n * 2) }
            start[n] = end; dur[n] = d; on[n] = onSec
            return n++
        }
        /** Index of the symbol playing at time t (clamped). */
        fun at(t: Double): Int { var lo = 0; var hi = n - 1; while (lo < hi) { val m = (lo + hi + 1) / 2; if (start[m] <= t) lo = m else hi = m - 1 }; return lo }
        /** Seconds of light in [a, b]; unknown symbols count as dark. */
        fun light(a: Double, b: Double): Double {
            if (n == 0 || b <= 0) return 0.0
            var i = at(a); var sum = 0.0
            while (i < n && start[i] < b) {
                val o = on[i]
                if (!o.isNaN() && o > 0) sum += max(0.0, min(b, start[i] + o) - max(a, start[i]))
                i++
            }
            return sum
        }
    }

    /** Where the parts of a send are (symbol indexes in the Wave). */
    class Layout(val mode: Mode, val len: Int, val short: Boolean = false) {
        val syncFirst = 0
        val hdrFirst = DARK + (if (short) SHORT_SYNC.size else SYNC.size)
        val trainFirst = hdrFirst + (if (short) SHORT_HDR_SYMS else HDR_SYMS)
        val bodyFirst = trainFirst + mode.levels * TRAIN_REPEAT
        val nData = dataSymbolCount(len, mode)
        val bodySyms = nData + ceil(nData.toDouble() / PILOT_EVERY).toInt() * 4
        val tailFirst = bodyFirst + bodySyms
    }

    // ------------------------------------------------------------ short reply

    /**
     * Short reply to one message: "got it" or "saw it but could not read it". Dark, then a 63-slot
     * on/off pattern at 30 per second (about 2.6 s in all). Each pattern comes from the passphrase, the
     * message's nonce and the kind of reply, so the first phone knows exactly which two to expect, and a
     * reply to another message (or from a stranger) doesn't match. Of the first 16 candidates, the first one
     * that is balanced and has no long runs is used (the same choice on both phones).
     */
    const val REPLY_SLOTS = 63
    /**
     * The reply goes back at the speed of the message it answers (Oct 14: at 6 m with daylight behind the
     * replying phone, half the 30 per second replies were missed while its 10 per second messages were read).
     * At 15 and 10 per second the pattern is 31 slots (2.1 s / 3.1 s).
     */
    fun replySlots(perSec: Int) = if (perSec >= 30) REPLY_SLOTS else 31
    fun replyPattern(pass: String, nonce: Int, ok: Boolean, perSec: Int = 30): IntArray {
        val n = replySlots(perSec)
        var fallback: IntArray? = null
        for (k in 0 until 16) {
            val ks = MessageFormat.keystream("$pass/reply/$k", nonce * 2 + (if (ok) 1 else 0), 8)
            val p = IntArray(n) { (ks[it / 8].toInt() shr (7 - it % 8)) and 1 }
            if (fallback == null) fallback = p
            val ones = p.sum()
            var run = 1; var longest = 1
            for (i in 1 until p.size) { run = if (p[i] == p[i - 1]) run + 1 else 1; longest = maxOf(longest, run) }
            if ((if (n == REPLY_SLOTS) ones in 28..35 && longest <= 5 else ones in 13..18 && longest <= 4) && p[0] == 1) return p
        }
        return fallback!!
    }
    fun replyWave(pass: String, nonce: Int, ok: Boolean, perSec: Int = 30): Wave {
        val w = Wave(); repeat(DARK) { w.add(SLOT, 0.0) }
        val ts = 1.0 / perSec
        for (b in replyPattern(pass, nonce, ok, perSec)) w.add(ts, if (b == 1) ts else 0.0)
        repeat(TAIL) { w.add(SLOT, 0.0) }
        return w
    }

    /**
     * Aiming pattern (Oct 14): the short opening's 31-step pattern played over and over at `perSec`.
     * The phone being placed listens for it and beeps when its camera sees it (the founder holds that phone).
     */
    fun aimWave(perSec: Int, seconds: Double): Wave {
        val w = Wave(); val ts = 1.0 / perSec
        while (w.end < seconds) for (b in SHORT_SYNC) w.add(ts, if (b == 1) ts else 0.0)
        return w
    }

    /** Dark + sync: the same for every send (short = the short opening's sync). */
    fun syncWave(short: Boolean = false): Wave { val w = Wave(); repeat(DARK) { w.add(SLOT, 0.0) }; for (b in if (short) SHORT_SYNC else SYNC) w.add(SLOT, if (b == 1) SLOT else 0.0); return w }

    class Send(val wave: Wave, val layout: Layout, val bodySyms: IntArray, val bodyCoded: IntArray, val pminMs: Int, val nonce: Int)

    fun utf8(text: String): ByteArray {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size <= MAX_BYTES) return b
        var n = MAX_BYTES
        while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--   // don't cut a letter in half
        return b.copyOf(n)
    }

    /**
     * The whole send. pminMs = the sender's shortest pulse, measured while the sync played.
     * With a link and seq >= 1: short opening (short sync and short header), nonce made from the link.
     */
    fun build(text: String, pass: String, nonce: Int, mode: Mode, tag: Int, pminMs: Int, link: Link? = null, seq: Int = 0): Send {
        val packed = TextPack.pack(text)?.takeIf { it.size <= MAX_BYTES }
        val data = packed ?: utf8(text)
        val short = link != null && seq >= 1
        val n = if (short) seqNonce(pass, link!!.id, seq) else nonce
        val lay = Layout(mode, data.size, short)
        val w = syncWave(short)
        val ts = mode.symSec
        val pmin = pminMs / 1000.0
        val hdr = if (short) shortHeaderBits(pass, n, mode, data.size, packed != null) else headerBits(pass, n, mode, tag, pminMs, data.size, packed != null)
        for (b in BARKER + hdr) w.add(ts, if (b == 1) ts else 0.0)
        for (l in trainSymbols(mode)) w.add(ts, mode.onSec(l, pmin))
        val coded = bodyBits(data, pass, n)
        val (body, _) = withPilots(bitsToSymbols(coded, mode), mode)
        check(body.size == lay.bodySyms)
        for (l in body) w.add(ts, mode.onSec(l, pmin))
        repeat(TAIL) { w.add(SLOT, 0.0) }
        return Send(w, lay, body, coded, pminMs, n)
    }

    /** Flashlight switch times (seconds from the start, on/off) for symbols from..until, skipping switches that change nothing. */
    fun switches(w: Wave, from: Int = 0, until: Int = w.n, startState: Boolean = false): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        var state = startState
        fun set(t: Double, on: Boolean) { if (on != state) { out.add(t to on); state = on } }
        for (i in from until until) {
            val o = w.on[i]
            if (o <= 0) set(w.start[i], false)
            else { set(w.start[i], true); if (o < w.dur[i]) set(w.start[i] + o, false) }
        }
        if (until == w.n) set(w.end, false)
        return out
    }
}

/**
 * Reads brightness messages from recorded pictures. Channels are small areas of the
 * picture (bands of rows x column blocks); every channel has its own timing, because the
 * camera reads the rows one after another (rolling shutter).
 */
class BrightDecoder(private val pass: String, private val expSec: Double) {

    /** One picture series per channel: times (s, receiver clock, start of exposure) and brightness. */
    class Series(val t: DoubleArray, val y: DoubleArray)

    data class Result(
        val ok: Boolean, val stage: String, val text: String = "", val mode: BrightFormat.Mode? = null,
        val phoneTag: Int = -1, val nonce: Int = -1, val len: Int = -1, val pminMs: Int = -1, val t0: Double = 0.0,
        val coded: IntArray? = null, val bodySyms: IntArray? = null, val channels: Int = 0,
        val syncCorr: Double = 0.0, val note: String = "", val levelsApart: Int = 0, val snr: Double = 0.0, val endT: Double = 0.0,
        val symbolsChecked: Int = 0, val pilotErrors: Int = 0,
        /** Which of the given series carried the send (for the receiver to keep watching those spots). */
        val blocks: List<Int> = emptyList(),
        /** Text was sent packed (TextPack). */
        val packed: Boolean = false,
        /** Short opening (linked): which follow-up number it was; 0 = full opening. */
        val seq: Int = 0,
    )

    /** Per-channel timing and brightness curve. */
    private class Chan(val s: Series, val t0: Double, val corr: Double) {
        var f = DoubleArray(9)     // brightness at on-share 0, 1/8 .. 1
        var sigma = 1.0
        var w = 1.0
        /** Brightness for an on-share 0..1 (straight lines between the 9 points). */
        fun F(x: Double): Double { val p = (x.coerceIn(0.0, 1.0) * 8); val i = min(7, floor(p).toInt()); return f[i] + (f[i + 1] - f[i]) * (p - i) }
        val swing get() = f[8] - f[0]
    }

    // ------------------------------------------------------------ finding the send

    /** How well a picture series matches the wave started at t0 (correlation, pictures up to `until` s after t0). */
    fun match(s: Series, t0: Double, w: BrightFormat.Wave, until: Double) = corr(s, t0, w, until)

    private fun corr(s: Series, t0: Double, w: BrightFormat.Wave, until: Double): Double {
        var i = lowerBound(s.t, t0)
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        while (i < s.t.size) {
            val a = s.t[i] - t0
            if (a + expSec > until) break
            if (a >= 0) {
                val x = w.light(a, a + expSec) / expSec
                val y = s.y[i]
                n++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            }
            i++
        }
        if (n < 40) return 0.0
        val cov = sxy - sx * sy / n; val vx = sxx - sx * sx / n; val vy = syy - sy * sy / n
        return if (vx <= 0 || vy <= 0) 0.0 else cov / sqrt(vx * vy)
    }

    private val sync = BrightFormat.syncWave()
    private val shortSync = BrightFormat.syncWave(short = true)

    /** Find sends (their sync pattern; short = the short opening's) in a channel between times from..to. Returns (t0, correlation), best first. */
    fun findStarts(s: Series, from: Double, to: Double, minCorr: Double = 0.8, short: Boolean = false): List<Pair<Double, Double>> {
        val step = 1.0 / 120
        val sw = if (short) shortSync else sync
        val c = ArrayList<Pair<Double, Double>>()
        var t0 = from
        while (t0 <= to) { c.add(t0 to corr(s, t0, sw, sw.end)); t0 += step }
        val peaks = ArrayList<Pair<Double, Double>>()
        for (i in c.indices) {
            if (c[i].second < minCorr) continue
            val lo = max(0, i - 60); val hi = min(c.size - 1, i + 60)
            if ((lo..hi).all { c[it].second <= c[i].second }) peaks.add(c[i])
        }
        return peaks.sortedByDescending { it.second }
    }

    /**
     * Look for the short reply to message `nonce` in one picture series between `from` and `to`.
     * Returns (start time, match, got-it?) for the best match at or above minCorr, or null.
     */
    fun findReply(s: Series, from: Double, to: Double, nonce: Int, minCorr: Double = 0.8, perSec: Int = 30): Triple<Double, Double, Boolean>? {
        var best: Triple<Double, Double, Boolean>? = null
        for (ok in listOf(true, false)) {
            val w = BrightFormat.replyWave(pass, nonce, ok, perSec)
            var t0 = from
            while (t0 <= to) {
                val c = corr(s, t0, w, w.end)
                if (c >= minCorr && (best == null || c > best.second)) best = Triple(t0, c, ok)
                t0 += 1.0 / 120
            }
        }
        return best
    }

    private fun timing(s: Series, t0Guess: Double, sw: BrightFormat.Wave): Chan? {
        var best = t0Guess; var bestC = -1.0
        var t = t0Guess - 0.025
        while (t <= t0Guess + 0.025) { val c = corr(s, t, sw, sw.end); if (c > bestC) { bestC = c; best = t }; t += 0.0005 }
        return if (bestC < 0.5) null else Chan(s, best, bestC)
    }

    /**
     * Fit each channel's brightness curve (9 points, kept smooth) to every picture whose
     * light is fully known up to symbol `knownUntil`.
     */
    private fun fit(c: Chan, w: BrightFormat.Wave, knownUntil: Double, drift: Drift? = null) {
        val A = Array(9) { DoubleArray(9) }; val B = DoubleArray(9)
        val s = c.s
        var i = lowerBound(s.t, c.t0)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        while (i < s.t.size) {
            val a = s.t[i] - c.t0
            if (a + expSec > knownUntil) break
            if (a >= 0) {
                val x = w.light(a, a + expSec) / expSec
                val y = drift?.fix(i, s.y[i]) ?: s.y[i]
                val p = x.coerceIn(0.0, 1.0) * 8; val k = min(7, floor(p).toInt()); val u = p - k
                A[k][k] += (1 - u) * (1 - u); A[k][k + 1] += (1 - u) * u; A[k + 1][k] += (1 - u) * u; A[k + 1][k + 1] += u * u
                B[k] += (1 - u) * y; B[k + 1] += u * y
                xs.add(x); ys.add(y)
            }
            i++
        }
        // smoothness: penalise bends between neighbouring points (keeps unseen points sensible)
        val lam = 0.05 * max(1.0, xs.size / 50.0)
        for (k in 1 until 8) { val d = doubleArrayOf(1.0, -2.0, 1.0); for (p in 0..2) for (q in 0..2) A[k - 1 + p][k - 1 + q] += lam * d[p] * d[q] }
        c.f = solve(A, B) ?: return
        var ss = 0.0
        for (j in xs.indices) { val e = ys[j] - c.F(xs[j]); ss += e * e }
        c.sigma = sqrt(ss / max(1, xs.size - 9)) + 0.02   // floor: brightness comes in whole steps
        c.w = 1.0 / (c.sigma * c.sigma)
    }

    // ------------------------------------------------------------ Viterbi over symbols

    /**
     * Decode symbols first..first+n-1 of the wave (all of duration ts, `levels` possible
     * on-times levelOn). forced[j] >= 0 fixes symbol j. Symbols outside the run use the wave.
     */
    private fun viterbi(chans: List<Chan>, w: BrightFormat.Wave, first: Int, n: Int, levelOn: DoubleArray, forced: IntArray,
                        drift: Array<Drift>? = null, tailKnown: Boolean = true): IntArray {
        val levels = levelOn.size
        val runStart = w.start[first]; val runEnd = w.start[first + n - 1] + w.dur[first + n - 1]
        class Obs(val ch: Int, val y: Double, val base: Double, val j: Int,
                  val pu0: Double, val pu1: Double, val cu0: Double, val cu1: Double)
        val perSym = Array(n) { ArrayList<Obs>() }
        chans.forEachIndexed { ci, c ->
            val s = c.s
            var i = lowerBound(s.t, c.t0 + runStart - expSec)
            while (i < s.t.size) {
                val a = s.t[i] - c.t0; val b = a + expSec
                if (a >= runEnd) break
                if (b > runStart && (tailKnown || b <= runEnd)) {
                    val j = (w.at(b - 1e-9) - first).coerceIn(0, n - 1)
                    val cs = w.start[first + j]
                    val cu0 = max(a, cs) - cs; val cu1 = min(b, cs + w.dur[first + j]) - cs
                    var pu0 = 0.0; var pu1 = 0.0
                    if (j > 0 && a < cs) { val ps = w.start[first + j - 1]; pu0 = max(a, ps) - ps; pu1 = cs - ps }
                    // light from symbols outside the run (known)
                    var base = 0.0
                    if (a < runStart) base += w.light(a, min(b, runStart))
                    if (b > runEnd) base += w.light(runEnd, b)
                    if (j == 0 && a < cs && a >= runStart) { /* nothing: covered */ }
                    val y = drift?.get(ci)?.fix(i, s.y[i]) ?: s.y[i]
                    perSym[j].add(Obs(ci, y, base, j, pu0, pu1, cu0, cu1))
                }
                i++
            }
        }
        // Branch costs: for symbol j, previous level a, this level b.
        val M = Array(n) { Array(levels) { DoubleArray(levels) } }
        for (j in 0 until n) for (b in 0 until levels) {
            val ob = levelOn[b]
            for (a in 0 until levels) {
                if (j == 0 && a != 0) continue
                val oa = levelOn[a]
                var m = 0.0
                for (o in perSym[j]) {
                    var on = o.base + max(0.0, min(o.cu1, ob) - o.cu0)
                    if (j > 0 && o.pu1 > o.pu0) on += max(0.0, min(o.pu1, oa) - o.pu0)
                    val c = chans[o.ch]
                    val e = o.y - c.F(on / expSec)
                    m += e * e * c.w
                }
                M[j][a][b] = m
            }
        }
        // Forward and backward best costs (min-sum), so every symbol gets a cost for every level.
        val INF = Double.MAX_VALUE / 4
        val allowed = { j: Int, b: Int -> forced[j] < 0 || forced[j] == b }
        val fw = Array(n) { DoubleArray(levels) { INF } }
        val back = Array(n) { IntArray(levels) }
        for (j in 0 until n) for (b in 0 until levels) {
            if (!allowed(j, b)) continue
            if (j == 0) { fw[0][b] = M[0][0][b]; continue }
            for (a in 0 until levels) { val c = fw[j - 1][a] + M[j][a][b]; if (c < fw[j][b]) { fw[j][b] = c; back[j][b] = a } }
        }
        val bw = Array(n) { DoubleArray(levels) { 0.0 } }
        for (j in n - 2 downTo 0) for (a in 0 until levels) {
            var best = INF
            for (b in 0 until levels) if (allowed(j + 1, b) && fw[j][a] < INF) best = min(best, M[j + 1][a][b] + bw[j + 1][b])
            bw[j][a] = best
        }
        val out = IntArray(n)
        var s = (0 until levels).minByOrNull { fw[n - 1][it] } ?: 0
        for (j in n - 1 downTo 0) { out[j] = s; s = back[j][s] }
        lastSoft = Array(n) { j -> val c = DoubleArray(levels) { b -> if (fw[j][b] >= INF || bw[j][b] >= INF) INF else fw[j][b] + bw[j][b] }
            val m0 = c.minOrNull() ?: 0.0; DoubleArray(levels) { (c[it] - m0).coerceAtMost(1e6) } }
        return out
    }

    /** Per symbol, per level: how much worse than the best guess (0 = best). Set by the last viterbi call. */
    private var lastSoft: Array<DoubleArray> = emptyArray()

    /** Slow change per channel picture: picture = off + gain x expected. 1.5 s windows. */
    private class Drift(val off: DoubleArray, val gain: DoubleArray) {
        fun fix(i: Int, y: Double) = (y - off[i]) / gain[i]
    }

    /**
     * Leftover slow change (a person walking past, a lamp, the room light), from a first
     * decode: in every 1.5 s window, how the pictures compare with what was expected,
     * as an offset and a gain (a shadow lowers the gain).
     */
    private fun driftFrom(chans: List<Chan>, w: BrightFormat.Wave, from: Double, to: Double): Array<Drift> =
        Array(chans.size) { ci ->
            val c = chans[ci]; val s = c.s
            val p = DoubleArray(s.t.size) { Double.NaN }
            for (i in s.t.indices) {
                val a = s.t[i] - c.t0
                if (a < from || a + expSec > to) continue
                p[i] = c.F(w.light(a, a + expSec) / expSec)
            }
            val off = DoubleArray(s.t.size); val gain = DoubleArray(s.t.size) { 1.0 }
            val half = ENV_HALF
            val f0 = c.f[0]
            for (i in s.t.indices) {
                var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (k in max(0, i - half)..min(s.t.size - 1, i + half)) if (!p[k].isNaN()) {
                    val x = p[k] - f0; val y = s.y[k] - f0
                    n++; sx += x; sy += y; sxx += x * x; sxy += x * y
                }
                if (n < 10) continue
                val vx = sxx - sx * sx / n
                if (vx > 4 * c.sigma * c.sigma * n) {
                    val g = ((sxy - sx * sy / n) / vx).coerceIn(0.25, 2.5)
                    gain[i] = g
                    off[i] = f0 + (sy - g * sx) / n - g * f0
                } else off[i] = (sy - sx) / n
            }
            Drift(off, gain)
        }

    /**
     * Guess-free version (like a radio's automatic volume): in a 2.4 s window, the darkest and
     * brightest pictures are compared with those during the sync, and scaled back to match.
     */
    private fun envelopeDrift(chans: List<Chan>, syncEnd: Double): Array<Drift> = Array(chans.size) { ci ->
        val c = chans[ci]; val s = c.s
        val syncA = c.t0 + BrightFormat.DARK * BrightFormat.SLOT; val syncB = c.t0 + syncEnd
        val ref = s.y.indices.filter { s.t[it] in syncA..syncB }.map { s.y[it] }.sorted()
        val off = DoubleArray(s.t.size); val gain = DoubleArray(s.t.size) { 1.0 }
        if (ref.size < 20) return@Array Drift(off, gain)
        val lo0 = ref[(ref.size * 0.05).toInt()]; val hi0 = ref[(ref.size * 0.95).toInt()]
        if (hi0 - lo0 < 4 * c.sigma) return@Array Drift(off, gain)
        val half = ENV_HALF
        for (i in s.t.indices) {
            val win = (max(0, i - half)..min(s.t.size - 1, i + half)).map { s.y[it] }.sorted()
            val lo = win[(win.size * 0.05).toInt()]; val hi = win[(win.size * 0.95).toInt()]
            val g = ((hi - lo) / (hi0 - lo0)).coerceIn(0.25, 2.5)
            gain[i] = g; off[i] = lo - g * lo0
        }
        Drift(off, gain)
    }

    // ------------------------------------------------------------ whole message

    /**
     * Try to read one send whose sync starts near t0 (receiver clock).
     * link != null: a short opening from that link (follow-up numbers lastSeq .. lastSeq + 8 are tried).
     */
    fun decode(series: List<Series>, t0: Double, syncCorr: Double, link: BrightFormat.Link? = null): Result {
        val short = link != null
        val syncW = BrightFormat.syncWave(short)
        var chans = series.mapNotNull { timing(it, t0, syncW) }
        val w = BrightFormat.syncWave(short)
        chans.forEach { fit(it, w, w.end) }
        // Clear channels first; if none (far away), try the faint ones: the stamp and check bytes still guard the text.
        val clear = chans.filter { it.swing > 4 * it.sigma }
        val faint = clear.isEmpty()
        chans = (if (faint) chans.filter { it.swing > WEAK_SWING * it.sigma } else clear).sortedByDescending { it.swing * it.swing * it.w }.take(24)
        if (chans.isEmpty()) return Result(false, "sync seen but too weak to read", t0 = t0, syncCorr = syncCorr)

        // Header: on/off at each text speed in use; the one whose stamp is right wins.
        val hs = if (short) BrightFormat.SHORT_HDR_SYMS else BrightFormat.HDR_SYMS
        val syncSlots = syncW.n
        var hdr: IntArray? = null; var rate = 0
        var headerNote = ""
        var seq = 0
        // A header must pass its check AND make sense (known mode at the speed tried, length 1-80);
        // a garbled one that passes the check by chance doesn't stop the other speeds from being tried.
        fun sane(modeId: Int, l: Int, tryRate: Int) = BrightFormat.MODES.getOrNull(modeId)?.perSec == tryRate && l in 1..BrightFormat.MAX_BYTES
        fun headerOk(bits: IntArray, tryRate: Int): Boolean {
            if (link != null) {
                val m = bitsInt(bits, 0, 4); val l = bitsInt(bits, 4, 8); val chk = bitsInt(bits, 12, 8)
                if (!sane(m and 7, l, tryRate)) return false
                for (k in max(1, link.lastSeq)..link.lastSeq + 8)
                    if (chk == BrightFormat.shortCheck(pass, BrightFormat.seqNonce(pass, link.id, k), m, l)) { seq = k; return true }
                return false
            }
            val stamp = bitsInt(bits, 0, 16); val nonce = bitsInt(bits, 16, 16)
            val l = bitsInt(bits, 44, 8) xor (MessageFormat.keystream(pass, nonce, 1)[0].toInt() and 0xff)
            return stamp == MessageFormat.tag(pass, nonce) && bitsInt(bits, 52, 8) == BrightFormat.headerCheck(bits.take(52)) && sane(bitsInt(bits, 33, 3), l, tryRate)
        }
        rates@ for (tryRate in BrightFormat.RATES) {
            val ts = 1.0 / tryRate
            val trial = BrightFormat.syncWave(short)
            repeat(hs) { trial.add(ts, Double.NaN) }
            val forced = IntArray(hs) { if (it < BrightFormat.BARKER.size) BrightFormat.BARKER[it] else -1 }
            chans.forEach { fit(it, w, w.end) }
            var drift: Array<Drift>? = null
            for (attempt in 0..2) {
                val syms = viterbi(chans, trial, syncSlots, hs, doubleArrayOf(0.0, ts), forced, drift, tailKnown = false)
                debugHdr?.let { e -> if (e.size == hs && tryRate == debugRate) println("    header rate $tryRate attempt $attempt: wrong symbols ${e.indices.count { e[it] != syms[it] }} of $hs; wrong at " +
                    e.indices.filter { e[it] != syms[it] }.joinToString(",")) }
                val bits = BrightFormat.unhamming(BrightFormat.deinterleave(syms.copyOfRange(BrightFormat.BARKER.size, hs)))
                if (headerOk(bits, tryRate)) {
                    hdr = bits; rate = tryRate; headerNote = if (attempt == 0) "" else "header on pass ${attempt + 1}; "
                    for (k in 0 until hs) w.add(ts, if (syms[k] == 1) ts else 0.0)
                    break@rates
                }
                // Next tries: follow slow changes (a shadow, a lamp): first guess-free, then from these guesses.
                for (k in 0 until hs) trial.on[syncSlots + k] = if (syms[k] == 1) ts else 0.0
                val d = if (attempt == 0) envelopeDrift(chans, syncW.end) else driftFrom(chans, trial, 0.0, trial.end)
                drift = d
                chans.forEachIndexed { ci, c -> fit(c, w, w.end, d[ci]) }
            }
        }
        if (hdr != null) chans.forEach { fit(it, w, syncW.end) }
        val best0 = chans.first()
        if (hdr == null) return Result(false, if (short) "short header not read" else "header not read (or another passphrase)", t0 = t0, channels = chans.size, syncCorr = syncCorr,
            snr = best0.swing / best0.sigma)
        val nonce: Int; val modeId: Int; val packed: Boolean; val tag: Int; val pminMs: Int; val len: Int
        if (link != null) {
            nonce = BrightFormat.seqNonce(pass, link.id, seq)
            modeId = bitsInt(hdr, 1, 3); packed = bitsInt(hdr, 0, 1) == 1; tag = link.tag; pminMs = link.pminMs; len = bitsInt(hdr, 4, 8)
            headerNote = "short opening #$seq; " + headerNote
        } else {
            nonce = bitsInt(hdr, 16, 16)
            modeId = bitsInt(hdr, 33, 3)
            packed = bitsInt(hdr, 32, 1) == 1
            tag = bitsInt(hdr, 36, 4)
            pminMs = bitsInt(hdr, 40, 4)
            len = bitsInt(hdr, 44, 8) xor (MessageFormat.keystream(pass, nonce, 1)[0].toInt() and 0xff)
        }
        val mode = BrightFormat.MODES.getOrNull(modeId)
        if (mode == null || mode.perSec != rate || len !in 1..BrightFormat.MAX_BYTES)
            return Result(false, "header read but makes no sense (mode $modeId, length $len)", t0 = t0, channels = chans.size, syncCorr = syncCorr)
        val lay = BrightFormat.Layout(mode, len, short)
        val pmin = pminMs / 1000.0
        val levelOn = DoubleArray(mode.levels) { mode.onSec(it, pmin) }
        // Mode staircase is known: learn the brightness curve again with it.
        for (l in BrightFormat.trainSymbols(mode)) w.add(mode.symSec, levelOn[l])
        chans.forEach { fit(it, w, w.end) }
        val best = chans.maxByOrNull { it.swing * it.swing * it.w }!!
        // Body: pilots known, the rest unknown for now.
        val nBody = lay.bodySyms
        val (_, isPilot) = BrightFormat.withPilots(IntArray(lay.nData), mode)
        val forced = IntArray(nBody) { -1 }
        run { var pi = 0; for (j in isPilot.indices) if (isPilot[j]) { forced[j] = mode.pilot[pi % 4]; pi++ } }
        for (j in 0 until nBody) w.add(mode.symSec, if (forced[j] >= 0) levelOn[forced[j]] else Double.NaN)
        repeat(BrightFormat.TAIL) { w.add(BrightFormat.SLOT, 0.0) }

        fun read(body: IntArray): Pair<IntArray, String?> {
            val data = body.filterIndexed { j, _ -> !isPilot[j] }.toIntArray()
            val nb = BrightFormat.codedBodyBitCount(len)
            val coded = BrightFormat.symbolsToBits(data, mode, nb)
            val soft = lastSoft.filterIndexed { j, _ -> !isPilot[j] }
            val llr = BrightFormat.softBits(soft, mode, nb)
            val ks = MessageFormat.keystream(pass, nonce, len + 3)
            // Soft repair (capped, so one badly timed pulse can't shout down its neighbours), then plain repair.
            for (plain in listOf(BrightFormat.softDecode(DoubleArray(llr.size) { llr[it].coerceIn(-SOFT_CAP, SOFT_CAP) }),
                                 BrightFormat.unhamming(BrightFormat.deinterleave(coded)))) {
                val bytes = ByteArray(len) { (bitsInt(plain, it * 8, 8) xor (ks[it + 1].toInt() and 0xff)).toByte() }
                val crc = ((bitsInt(plain, len * 8, 8) xor (ks[len + 1].toInt() and 0xff)) shl 8) or (bitsInt(plain, len * 8 + 8, 8) xor (ks[len + 2].toInt() and 0xff))
                if (crc == BrightFormat.crc16(bytes)) {
                    val t = if (packed) TextPack.unpack(bytes) else String(bytes, Charsets.UTF_8)
                    if (t != null) return coded to t
                }
            }
            return coded to null
        }
        var body = viterbi(chans, w, lay.bodyFirst, nBody, levelOn, forced)
        var (coded, text) = read(body)
        if (faint) headerNote = "faint; " + headerNote
        var note = headerNote + "first pass"
        // More passes if needed: follow slow changes (shadows, lamps), guess-free first, then from the latest guesses.
        for (pass in 2..3) {
            if (text != null) break
            for (j in 0 until nBody) w.on[lay.bodyFirst + j] = levelOn[body[j]]
            val drift = if (pass == 2) envelopeDrift(chans, syncW.end) else driftFrom(chans, w, 0.0, w.end)
            chans.forEachIndexed { ci, c -> fit(c, w, w.end, drift[ci]) }
            body = viterbi(chans, w, lay.bodyFirst, nBody, levelOn, forced, drift)
            val r = read(body); coded = r.first; text = r.second
            note = headerNote + if (pass == 2) "pass 2 (brightness followed, guess-free)" else "pass 3 (brightness followed from guesses)"
        }
        // How many of the mode's levels stand clearly apart on the best channel (gap > 3 x noise).
        val lv = levelOn.map { best.F(it / mode.symSec) }
        var apart = 1; var last = lv[0]
        for (k in 1 until lv.size) if (lv[k] - last > 3 * best.sigma) { apart++; last = lv[k] }
        val endT = t0 + w.end
        return Result(text != null, if (text != null) "read" else "check byte wrong", text ?: "", mode, tag, nonce, len, pminMs, t0,
            coded, body, chans.size, syncCorr, note, apart, best.swing / best.sigma, endT,
            blocks = chans.map { c -> series.indexOfFirst { it === c.s } }.filter { it >= 0 }, packed = packed, seq = seq)
    }

    companion object {
        /** For the simulator only: the true header symbols, to print how many were misread. */
        @JvmField var debugHdr: IntArray? = null
        @JvmField var debugRate = 0
        /** Half-width (pictures) of the windows used to follow brightness changes. */
        @JvmField var ENV_HALF = 12
        /** Largest say one coded bit gets in soft repair (in units of squared noise). */
        @JvmField var SOFT_CAP = 20.0
        /** Faintest channel still tried when no channel is clear (swing over noise). */
        @JvmField var WEAK_SWING = 2.0
        fun lowerBound(a: DoubleArray, v: Double): Int { var lo = 0; var hi = a.size; while (lo < hi) { val m = (lo + hi) / 2; if (a[m] < v) lo = m + 1 else hi = m }; return lo }
        fun bitsInt(b: IntArray, from: Int, n: Int): Int { var v = 0; for (i in 0 until n) v = (v shl 1) or (if (from + i < b.size) b[from + i] else 0); return v }

        /** Small linear solve (Gaussian elimination with pivoting). */
        fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val n = b0.size
            val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
                if (abs(a[p][c]) < 1e-12) return null
                val tr = a[c]; a[c] = a[p]; a[p] = tr; val tb = b[c]; b[c] = b[p]; b[p] = tb
                for (r in c + 1 until n) { val f = a[r][c] / a[c][c]; for (k in c until n) a[r][k] -= f * a[c][k]; b[r] -= f * b[c] }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) { var s = b[r]; for (k in r + 1 until n) s -= a[r][k] * x[k]; x[r] = s / a[r][r] }
            return x
        }
    }
}
