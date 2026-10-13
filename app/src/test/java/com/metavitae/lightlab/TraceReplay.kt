package com.metavitae.lightlab

import java.io.File

/**
 * Replays a receiver brightness recording (bright-trace file) on the computer, to see why a send was missed.
 *   args: trace file, passphrase, how many blocks to search for the sync (0 = as on the phone: whole picture + 4 liveliest + blocks of the last read send)
 */
object TraceReplay {
    @JvmStatic fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().filter { it.isNotBlank() && it[0].isDigit() }
        val pass = args.getOrNull(1) ?: "TACO"
        val searchBlocks = args.getOrNull(2)?.toInt() ?: 0
        val rows = lines.map { l -> l.split(",").map { it.toDouble() } }
        val t = DoubleArray(rows.size) { rows[it][0] }
        val nch = rows[0].size - 1
        val series = List(nch) { ch -> BrightDecoder.Series(t, DoubleArray(rows.size) { rows[it][ch + 1] }) }
        val dec = BrightDecoder(pass, 0.0325)
        val whole = nch - 1
        fun variance(y: DoubleArray): Double { val m = y.average(); return y.sumOf { (it - m) * (it - m) } / y.size }
        val syncLen = BrightFormat.syncWave().end
        println("${rows.size} pictures, ${"%.1f".format(t.last())} s, $nch channels, search: ${if (searchBlocks == 0) "phone (whole + 4 liveliest per window)" else "$searchBlocks steadiest-pattern blocks"}")
        var known: List<Int> = emptyList()
        var from = 0.0
        val step = 10.0
        while (from + syncLen + 1 < t.last()) {
            val to = minOf(from + 140, t.last())
            val win = series.map { s -> val idx = s.t.indices.filter { s.t[it] in from..to }; BrightDecoder.Series(DoubleArray(idx.size) { s.t[idx[it]] }, DoubleArray(idx.size) { s.y[idx[it]] }) }
            val cand = if (searchBlocks == 0) (listOf(whole) + (0 until whole).sortedByDescending { variance(win[it].y) }.take(4) + known).distinct()
                       else listOf(whole) + (0 until whole).sortedByDescending { variance(win[it].y) }.take(searchBlocks)
            val starts = cand.flatMap { dec.findStarts(win[it], from, minOf(from + step, to - syncLen - 0.3), 0.8) }
            val t0 = starts.minByOrNull { it.first }
            if (t0 == null) {
                val best = cand.maxOf { c -> dec.findStarts(win[c], from, minOf(from + step, to - syncLen - 0.3), 0.0).firstOrNull()?.second ?: 0.0 }
                println("  %5.1f-%5.1f s: no sync (best match %.2f)".format(from, from + step, best))
                from += step; continue
            }
            val c = starts.filter { kotlin.math.abs(it.first - t0.first) < 0.05 }.maxOf { it.second }
            val r = dec.decode(series, t0.first, c)
            println("  sync at %5.1f s (match %.2f): %s %s ch=%d snr=%.1f %s".format(t0.first, c, r.stage, r.text, r.channels, r.snr, r.note))
            from = if (r.mode != null) r.endT - 0.2 else t0.first + 1.0
            if (r.ok) known = r.blocks.filter { it < whole }.take(6)
        }
    }
}
