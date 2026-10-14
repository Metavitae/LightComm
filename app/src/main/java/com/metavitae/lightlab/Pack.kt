package com.metavitae.lightlab

import java.util.PriorityQueue

/**
 * Shorter text: common letters get short codes, rare ones long codes (the idea behind Morse),
 * from a fixed table that both phones share. No Android code here, so it can be tested on a computer.
 *
 * Layout: case rule (2 bits: 0 = no capitals, 1 = all capitals, 2 = mixed with SHIFT before each capital),
 * then one code per character, then END, then zero bits up to a whole byte.
 * Letters are coded without case; anything not in the table goes as ESC + 16 bits (one UTF-16 unit).
 */
object TextPack {
    private const val SHIFT = '\u0001'
    private const val END = '\u0002'
    private const val ESC = '\u0003'

    /** Rough letter counts per 1000 characters of short English and Spanish messages (shared, never change without a version bump). */
    private val WEIGHTS: List<Pair<Char, Int>> = listOf(
        ' ' to 180, 'e' to 100, 'a' to 85, 'o' to 70, 's' to 60, 'n' to 60, 'r' to 55, 'i' to 55, 't' to 55,
        'l' to 40, 'd' to 38, 'c' to 32, 'u' to 32, 'm' to 25, 'h' to 25, 'p' to 22, 'g' to 15, 'y' to 14,
        'b' to 14, 'f' to 13, 'v' to 10, 'w' to 10, 'q' to 6, 'k' to 4, 'j' to 4, 'z' to 3, 'x' to 2,
        '0' to 4, '1' to 4, '2' to 4, '3' to 4, '4' to 4, '5' to 4, '6' to 4, '7' to 4, '8' to 4, '9' to 4,
        '.' to 10, ',' to 8, '?' to 4, '!' to 3, ':' to 2, '-' to 2, '\'' to 2, '\n' to 2, '/' to 1, '"' to 1,
        '(' to 1, ')' to 1, '@' to 1, '#' to 1, '+' to 1, '&' to 1, '*' to 1, ';' to 1, '%' to 1, '$' to 1, '=' to 1,
        'á' to 3, 'é' to 3, 'í' to 3, 'ó' to 3, 'ú' to 2, 'ñ' to 3, 'ü' to 1, '¿' to 2, '¡' to 2,
        SHIFT to 6, END to 4, ESC to 2,
    )

    /** Code for each symbol, as a string of 0/1 (built the same way on every phone). */
    private val CODES: Map<Char, String> = run {
        class Node(val w: Int, val order: Int, val sym: Char?, val a: Node?, val b: Node?)
        var order = 0
        val q = PriorityQueue<Node>(compareBy<Node>({ it.w }, { it.order }))
        for ((c, w) in WEIGHTS) q.add(Node(w, order++, c, null, null))
        while (q.size > 1) { val a = q.poll()!!; val b = q.poll()!!; q.add(Node(a.w + b.w, order++, null, a, b)) }
        val out = HashMap<Char, String>()
        fun walk(n: Node, p: String) { if (n.sym != null) out[n.sym] = p.ifEmpty { "0" } else { walk(n.a!!, p + "0"); walk(n.b!!, p + "1") } }
        walk(q.poll()!!, ""); out
    }
    private val DECODE: Map<String, Char> = CODES.entries.associate { (k, v) -> v to k }

    private fun bitsOfInt(v: Int, n: Int, sb: StringBuilder) { for (i in n - 1 downTo 0) sb.append(if ((v shr i) and 1 == 1) '1' else '0') }

    /** Packed bytes, or null if the text has nothing to gain (then it goes as plain UTF-8). */
    fun pack(text: String): ByteArray? {
        val hasLower = text.any { it.isLetter() && it.isLowerCase() }
        val hasUpper = text.any { it.isLetter() && it.isUpperCase() }
        val rule = when { !hasUpper -> 0; !hasLower -> 1; else -> 2 }
        val sb = StringBuilder()
        bitsOfInt(rule, 2, sb)
        for (ch in text) {
            val low = ch.lowercaseChar()
            val code = CODES[low]
            // A letter whose lowercase form can't turn back into it exactly (rare) goes as ESC.
            if (code != null && ch !in setOf(SHIFT, END, ESC) && (low == ch || low.uppercaseChar() == ch)) {
                if (rule == 2 && ch != low) sb.append(CODES[SHIFT])
                sb.append(code)
            } else { sb.append(CODES[ESC]); bitsOfInt(ch.code, 16, sb) }
        }
        sb.append(CODES[END])
        while (sb.length % 8 != 0) sb.append('0')
        val out = ByteArray(sb.length / 8) { i -> sb.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        // Only when it is shorter and comes back exactly (the check bytes guard the packed bytes, not the letters).
        return if (out.size < text.toByteArray(Charsets.UTF_8).size && unpack(out) == text) out else null
    }

    /** The text back, or null if the bits make no sense. */
    fun unpack(bytes: ByteArray): String? {
        val bits = StringBuilder()
        for (b in bytes) bitsOfInt(b.toInt() and 0xff, 8, bits)
        if (bits.length < 2) return null
        val rule = bits.substring(0, 2).toInt(2)
        if (rule > 2) return null
        var i = 2
        val out = StringBuilder()
        var shift = false
        val cur = StringBuilder()
        while (i < bits.length) {
            cur.append(bits[i]); i++
            val sym = DECODE[cur.toString()] ?: run { if (cur.length > 24) return null; null } ?: continue
            cur.setLength(0)
            when (sym) {
                END -> return if (bits.substring(i).all { it == '0' } && !shift) out.toString() else null
                SHIFT -> { if (rule != 2 || shift) return null; shift = true }
                ESC -> { if (i + 16 > bits.length) return null; out.append(bits.substring(i, i + 16).toInt(2).toChar()); i += 16 }
                else -> {
                    val up = (rule == 1 || shift) && sym.isLetter()
                    out.append(if (up) sym.uppercaseChar() else sym)
                    shift = false
                }
            }
        }
        return null
    }
}
