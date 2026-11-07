package com.metavitae.lightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Nov 7 (Faro's Deep review v3: "measure the wrong-text risk before trusting 'never wrong'").
 * Big run done on the computer (CC Log Nov 7): when almost every try fails, the 16-bit check let a wrong text through
 * in about 1 of 3,500 messages, mostly from ordinary single tries (joining tries made it rarer, not more common).
 * The second guard (BrightFormat.textIfPlausible) stopped most of them. These checks: real texts are never refused,
 * garbage mostly is.
 */
class WrongTextTest {
    private val real = listOf("A1 HELLO FROM THE POCO", "Just a personal test holding the phone...", "~f1/7 E1 LONG MESSAGE. WE ARE AT THE ",
        "(COPY) A1 HELLO FROM THE 8T", "Need help, urgent", "hola ¿cómo estás? 😀", "👍🏽", "👨‍👩‍👧", "line one\nline two", "tab\there",
        "(WHERE?) 20.71044,-105.28169,100", "(HERE) 20.71022,-105.28218,10", "(SEEN BEACON)", "ok", "Ñandú ü ¡ya!", "x",
        "\u001e" + 0x21.toChar(), "\u001e" + (0x21 + 47).toChar())

    @Test fun real_texts_are_never_refused() {
        for (t in real) {
            val p = TextPack.pack(t)?.takeIf { it.size <= BrightFormat.MAX_BYTES }
            val bytes = p ?: BrightFormat.utf8(t)
            assertEquals("refused a real text: $t", t, BrightFormat.textIfPlausible(bytes, p != null))
        }
        // random typed-looking texts, both ways
        val r = Random(11); val letters = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,?!áéíóúñ¿¡:-'()@#"
        repeat(20000) {
            val t = String(CharArray(1 + r.nextInt(70)) { letters[r.nextInt(letters.length)] })
            val p = TextPack.pack(t)?.takeIf { it.size <= BrightFormat.MAX_BYTES }
            val sent = p ?: BrightFormat.utf8(t)   // over 80 bytes: the sender cuts it, so expect the cut text
            assertEquals("refused: $t", if (p != null) t else String(sent, Charsets.UTF_8), BrightFormat.textIfPlausible(sent, p != null))
        }
    }

    @Test fun most_garbage_is_refused() {
        val r = Random(5)
        for (len in listOf(12, 40)) for (packed in listOf(true, false)) {
            var pass = 0; val n = 50000
            repeat(n) { val b = ByteArray(len).also { r.nextBytes(it) }; if (BrightFormat.textIfPlausible(b, packed) != null) pass++ }
            println("len $len packed $packed: garbage let through $pass of $n")
            assertTrue("guard too weak at len $len packed $packed: $pass of $n", pass * 200 < n)
        }
    }
}
