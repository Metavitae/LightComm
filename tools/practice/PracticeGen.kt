package com.metavitae.lightlab

import java.io.File
import java.util.Locale

/**
 * Practice page patterns (Oct 30, founder: a web page as the practice partner at home).
 * Builds real LightComm sends (2 levels, 15 per second, full opening) and writes their on/off switch times as JSON.
 * Self-check: films each send at 30 pictures a second (32.5 ms exposure) and reads it back with the app's own decoder.
 * args: out.json key "TEXT|TEXT|..."
 */
fun main(args: Array<String>) {
    val pass = MessageFormat.norm(args.getOrElse(1) { MessageFormat.DEFAULT_PASS })
    val texts = args.getOrElse(2) { "HELLO FROM THE PRACTICE PAGE" }.split("|")
    val mode = BrightFormat.MODES.first { it.levels == 2 && it.perSec == 15 }
    val tag = BrightFormat.phoneTag("PRACTICE PAGE")
    val rnd = java.security.SecureRandom()
    val out = StringBuilder("[")
    var bad = 0
    for ((i, text) in texts.withIndex()) {
        val nonce = rnd.nextInt(65536)
        val s = BrightFormat.build(text, pass, nonce, mode, tag, 12)
        val sw = BrightFormat.switches(s.wave)
        // Film it: each picture averages the light over its exposure, plus a dim room and a little noise.
        val exp = 0.0325; val lead = 1.0
        val n = ((s.wave.end + lead * 2) * 30).toInt()
        val t = DoubleArray(n) { it / 30.0 }
        val y = DoubleArray(n) { k -> val a = t[k] - lead; 20 + 180 * s.wave.light(a, a + exp) / exp + (rnd.nextGaussian() * 3) }
        val dec = BrightDecoder(pass, exp)
        val ser = BrightDecoder.Series(t, y)
        val st = dec.findStarts(ser, 0.0, t.last() - BrightFormat.syncWave().end - 0.3, 0.8).firstOrNull()
        val r = st?.let { dec.decode(listOf(ser), it.first, it.second) }
        val ok = r?.ok == true && r.text == text
        if (!ok) bad++
        System.err.println(String.format(Locale.US, "%s %5.1f s  nonce %5d  read back: %s", if (ok) "OK " else "BAD", s.wave.end, s.nonce,
            r?.let { if (it.ok) "\"${it.text}\"" else it.stage } ?: "no opening found"))
        if (i > 0) out.append(",")
        out.append("\n {\"text\":").append(jsonStr(text)).append(",\"nonce\":").append(s.nonce)
            .append(String.format(Locale.US, ",\"end\":%.4f,\"sw\":[", s.wave.end))
            .append(sw.joinToString(",") { String.format(Locale.US, "[%.4f,%d]", it.first, if (it.second) 1 else 0) }).append("]}")
    }
    out.append("\n]\n")
    File(args[0]).writeText(out.toString())
    System.err.println(if (bad == 0) "all read back" else "$bad NOT read back")
}

private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
