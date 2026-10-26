package com.metavitae.lightlab

import java.io.File

/**
 * Replays a missed short opening (miss-trace file) on the computer: which follow-up number did it carry?
 *   args: trace file, passphrase, link id (nonce of the full message that started the link), highest number to try
 * Oct 26: the phone tried lastSeq .. lastSeq + 2 (now + 5); this tries every number from 1 up.
 *   5th arg (optional): the receiver's last-read number: decode once, exactly as the phone would now.
 */
object ShortReplay {
    @JvmStatic fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().filter { it.isNotBlank() && it[0].isDigit() }
        val pass = MessageFormat.norm(args[1])
        val linkId = args[2].toInt()
        val maxSeq = args.getOrNull(3)?.toInt() ?: 30
        val rows = lines.map { l -> l.split(",").map { it.toDouble() } }
        val t = DoubleArray(rows.size) { rows[it][0] }
        val series = List(rows[0].size - 1) { ch -> BrightDecoder.Series(t, DoubleArray(rows.size) { rows[it][ch + 1] }) }
        val dec = BrightDecoder(pass, 0.0325)
        val starts = series.flatMap { dec.findStarts(it, 0.0, t.last() - 2.0, 0.8, short = true) }
        val t0 = starts.minByOrNull { it.first }
        if (t0 == null) { println("  no short opening found"); return }
        val c = starts.filter { kotlin.math.abs(it.first - t0.first) < 0.05 }.maxOf { it.second }
        print("  short opening at %.2f s (match %.2f): ".format(t0.first, c))
        args.getOrNull(4)?.toInt()?.let { last ->
            val r = dec.decode(series, t0.first, c, BrightFormat.Link(linkId, 0, 6, last))
            println("receiver at #$last: ${r.stage} ${r.note} \"${r.text}\""); return
        }
        for (last in 0..maxSeq step 3) {
            val r = dec.decode(series, t0.first, c, BrightFormat.Link(linkId, 0, 6, last))
            if (r.stage != "short header not read") { println("reads with numbers ${maxOf(1, last)}..${last + 5}: ${r.stage} ${r.note} \"${r.text}\""); return }
        }
        println("no number 1..$maxSeq reads it (light problem, or another link)")
    }
}
