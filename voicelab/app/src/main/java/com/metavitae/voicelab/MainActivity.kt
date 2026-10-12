package com.metavitae.voicelab

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voice Lab: what Android's ON-DEVICE speech recognizer really offers on this phone
 * (LightComm voice input, "Way 2"). Saves only text, never audio. This app has no
 * internet permission.
 *
 * adb: am start -n com.metavitae.voicelab/.MainActivity --es mode facts
 *      am start -n com.metavitae.voicelab/.MainActivity --es mode card
 */
class MainActivity : Activity() {
    private var pending: (() -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (intent.getStringExtra("mode")) {
            "facts" -> showFacts()
            "card" -> withMic { showCard() }
            else -> showMenu()
        }
    }

    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }

    private fun withMic(f: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { pending = f; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val f = pending; pending = null
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) f?.invoke() else showMenu()
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private fun LinearLayout.text(s: String, size: Float = 16f, bold: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4))
    }.also { addView(it) }
    private fun LinearLayout.button(s: String, onClick: () -> Unit) = Button(context).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }.also { addView(it) }
    private fun page(build: LinearLayout.() -> Unit) { val c = col(); c.build(); setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(c) }) }

    private fun showMenu() = page {
        text("Voice Lab", 28f, bold = true)
        text("Tests Android's speech recognizer that works on the phone itself. Saves text only, never sound, in Download/VoiceLab. This app has no internet permission.", 14f, color = Color.LTGRAY)
        button("1. Phone facts (automatic, a few seconds)") { showFacts() }
        button("2. Reading card test (40 phrases)") { withMic { showCard() } }
    }

    // ------------------------------------------------------------------ results file

    private class LiveFile(val act: Activity, kind: String) {
        val name: String
        private var uri: Uri? = null
        init {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            name = "VoiceLab_${kind}_${Build.MODEL}_$stamp.txt"
            try {
                uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceLab")
                })
            } catch (_: Exception) {}
        }
        val path get() = if (uri != null) "Download/VoiceLab/$name" else "NOT SAVED"
        @Synchronized fun append(s: String) { val u = uri ?: return; try { act.contentResolver.openOutputStream(u, "wa")?.use { it.write(s.toByteArray()) } } catch (_: Exception) {} }
    }

    private fun header(title: String) = "Voice Lab - $title\nPhone: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n" +
        "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\nAirplane mode: ${airplane()}\n\n"

    private fun airplane() = if (Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) "ON" else "off"

    // ------------------------------------------------------------------ facts

    private val LANGS = listOf("en-US", "es-MX", "es-ES", "es-US")

    private fun showFacts() {
        val sb = StringBuilder(header("phone facts"))
        var out: TextView? = null
        page { text("Phone facts", 24f, bold = true); out = text("Checking...", 13f) }
        fun line(s: String) { sb.append(s).append('\n'); out?.text = sb.toString() }

        // 1. Speech engines installed, and the phone's default.
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        line("== Speech engines ==")
        if (services.isEmpty()) line("No speech recognition service visible to apps.")
        for (s in services) {
            val p = s.serviceInfo.packageName
            val ver = try { packageManager.getPackageInfo(p, 0).versionName } catch (_: Exception) { "?" }
            line("Service: $p/${s.serviceInfo.name} (app version $ver)")
        }
        val def = try { Settings.Secure.getString(contentResolver, "voice_recognition_service") } catch (e: Exception) { "not readable (${e.javaClass.simpleName})" }
        line("Default recognizer setting: ${def ?: "not set / not readable"}")
        line("Any recognizer available: ${SpeechRecognizer.isRecognitionAvailable(this)}")
        val onDev = if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.isOnDeviceRecognitionAvailable(this) else false
        line("On-device recognizer available (Android check, API 31+): $onDev")

        // 2. Create the on-device recognizer and ask which languages it has.
        if (Build.VERSION.SDK_INT >= 33 && onDev) {
            val r = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) } catch (e: Exception) { line("Creating the on-device recognizer FAILED: ${e.message}"); null }
            if (r != null) {
                line("Created the on-device recognizer: OK")
                var left = LANGS.size
                line("\n== Languages (on-device recognizer) ==")
                for (lang in LANGS) {
                    val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    r.checkRecognitionSupport(it, mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            line("$lang: installed=${s.installedOnDeviceLanguages.contains(lang)} supported=${s.supportedOnDeviceLanguages.contains(lang)} " +
                                "downloading=${s.pendingOnDeviceLanguages.contains(lang)} online=${s.onlineLanguages.contains(lang)}")
                            if (lang == LANGS.first()) {
                                line("   all installed on-device: ${s.installedOnDeviceLanguages}")
                                line("   all supported on-device: ${s.supportedOnDeviceLanguages}")
                                line("   downloading: ${s.pendingOnDeviceLanguages}")
                            }
                            if (--left == 0) done(sb, r)
                        }
                        override fun onError(error: Int) { line("$lang: check failed, error $error (${errName(error)})"); if (--left == 0) done(sb, r) }
                    })
                }
                return
            }
        } else line("Language check needs Android 13+ and an on-device recognizer; skipped.")
        done(sb, null)
    }

    private fun done(sb: StringBuilder, r: SpeechRecognizer?) {
        r?.destroy()
        val f = LiveFile(this, "facts")
        f.append(sb.toString())
        page {
            text("Phone facts", 24f, bold = true)
            text(sb.toString(), 13f)
            text("Saved: ${f.path}", 14f, color = Color.rgb(255, 200, 0))
            if (Build.VERSION.SDK_INT >= 33) for (lang in listOf("es-MX", "en-US")) button("Ask the phone to download the $lang offline pack") {
                try {
                    val rr = SpeechRecognizer.createOnDeviceSpeechRecognizer(this@MainActivity)
                    rr.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang))
                    f.append("Asked for a download of $lang\n")
                } catch (e: Exception) { f.append("Download request for $lang failed: ${e.message}\n") }
            }
            button("Back") { showMenu() }
        }
    }

    private fun errName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
        10 -> "too many requests"
        11 -> "server disconnected"
        12 -> "language not supported"
        13 -> "language not available (pack missing?)"
        14 -> "cannot check support"
        15 -> "cannot listen to download events"
        else -> "code $e"
    }

    // ------------------------------------------------------------------ reading card

    class Phrase(val id: String, val text: String, val lang: String)

    private val CARD: List<Phrase> = run {
        val a = listOf("Where are you right now", "I am at the truck", "Meet me at the north gate at eight", "We need more water", "Everyone is safe",
            "Wait for my signal", "The road is blocked", "Bring the first aid kit", "I can see your light", "Come back to camp before dark")
        val b = listOf("¿Dónde estás?", "Estoy en la camioneta", "Nos vemos a las ocho", "Necesitamos agua", "Todos estamos bien",
            "Espera mi señal", "El camino está cerrado", "Trae el botiquín", "Veo tu luz", "Regresa al campamento antes de que oscurezca")
        val c = listOf("Ok, nos vemos at the truck", "Where are you, ¿estás bien?", "Llego en five minutes", "Necesito water, please", "Meet me en la entrada norte",
            "Todo ok, see you at eight", "Bring la lámpara", "¿Dónde está the gate?", "I'm fine, gracias", "Ayuda, I need help aquí")
        val d = listOf("OK", "Ayuda", "Agua", "Where are you", "At the truck, 8PM", "Copy that, Chancla", "Metavitae here", "LightComm check, one two three",
            "Kithe, Orbgroove, Sonder", "Meet Mike at the gate")
        a.mapIndexed { i, s -> Phrase("A${i + 1}", s, "en-US") } + b.mapIndexed { i, s -> Phrase("B${i + 1}", s, "es-MX") } +
            c.mapIndexed { i, s -> Phrase("C${i + 1}", s, "es-MX") } + d.mapIndexed { i, s -> Phrase("D${i + 1}", s, "en-US") }
    }
    private val EXPECTED_WORDS = arrayListOf("OK", "ayuda", "agua", "truck", "camioneta", "8PM", "Chancla", "Metavitae", "LightComm", "Kithe", "Orbgroove", "Sonder", "Mike")

    private fun showCard() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            page { text("This phone has no on-device speech recognizer an app can use.", 18f); button("Back") { showMenu() } }; return
        }
        var idx = 0
        var noise = false
        var bias = false
        var switching = false
        val file = LiveFile(this, "card")
        file.append(header("reading card") + "Recognizer: on-device (createOnDeviceSpeechRecognizer). No sound saved.\n\n" +
            "time,phone,android,list,id,language,room_sound,expected_words_hint,language_switch,airplane,expected,n_candidates,candidates_with_scores," +
            "first_word_error_pct,best_word_error_pct,right_rank,end_of_speech_to_text_ms,speech_ms,partials,error,detected_language\n")

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val info = col()
        val count = info.text("", 14f, color = Color.LTGRAY)
        val phrase = info.text("", 30f, bold = true)
        val settings = info.text("", 13f, color = Color.LTGRAY)
        val status = info.text("", 18f, bold = true, color = Color.rgb(255, 200, 0))
        val cands = info.text("", 16f)
        root.addView(ScrollView(this).apply { addView(info) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val mic = Button(this).apply { text = "TAP, THEN READ THE PHRASE"; textSize = 20f; isAllCaps = false }
        root.addView(mic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bar)
        fun barBtn(s: String, f: () -> Unit) = Button(this).apply { text = s; textSize = 12f; isAllCaps = false; setOnClickListener { f() } }
            .also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        setContentView(root)

        fun show() {
            val p = CARD[idx]
            count.text = "Phrase ${idx + 1} of ${CARD.size}  (${p.id}, list ${p.id[0]})"
            phrase.text = p.text
            settings.text = "Language: ${p.lang}${if (switching) " + switching to ${if (p.lang == "en-US") "es-MX" else "en-US"}" else ""}   Room: ${if (noise) "NOISE (fan/TV)" else "quiet"}   " +
                "Expected-words hint: ${if (bias) "ON" else "off"}   Airplane: ${airplane()}\nSaved live in ${file.path}"
        }
        barBtn("< Back") { if (idx > 0) { idx--; cands.text = ""; status.text = ""; show() } }
        barBtn("Next >") { if (idx < CARD.size - 1) { idx++; cands.text = ""; status.text = ""; show() } }
        barBtn("Quiet / Noise") { noise = !noise; show() }
        barBtn("Hint on/off") { bias = !bias; show() }
        if (Build.VERSION.SDK_INT >= 34) barBtn("Switch on/off") { switching = !switching; show() }

        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer = r
        var tBegin = 0L; var tEnd = 0L; var partials = 0; var detected = ""
        var listening = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening... read now" }
            override fun onBeginningOfSpeech() { tBegin = SystemClock.elapsedRealtime() }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { tEnd = SystemClock.elapsedRealtime(); status.text = "Working..." }
            override fun onPartialResults(b: Bundle?) { partials++; b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { status.text = "… $it" } }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onLanguageDetection(b: Bundle) { detected = b.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: "" }
            override fun onError(e: Int) {
                listening = false
                status.text = "Problem: ${errName(e)} (tap to try again)"
                record(file, CARD[idx], noise, bias, switching, emptyList(), null, tBegin, tEnd, partials, errName(e), detected)
            }
            override fun onResults(b: Bundle?) {
                listening = false
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val scores = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val p = CARD[idx]
                val wers = list.map { wer(p.text, it) }
                val right = wers.indexOfFirst { it == 0.0 }
                cands.text = list.mapIndexed { i, s -> "${i + 1}. $s   (score ${scores?.getOrNull(i)?.let { "%.2f".format(it) } ?: "-"}, word errors ${"%.0f".format(wers[i])}%)" }
                    .joinToString("\n").ifEmpty { "(nothing)" }
                status.text = when {
                    right == 0 -> "Right first time. Tap Next >"
                    right > 0 -> "Right one was number ${right + 1} in the list. Tap Next >"
                    else -> "Not right in any of ${list.size}. Tap Next > (or try again)"
                }
                record(file, p, noise, bias, switching, list, scores, tBegin, tEnd, partials, "", detected)
            }
        })
        mic.setOnClickListener {
            if (listening) { r.stopListening(); return@setOnClickListener }
            val p = CARD[idx]
            val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, p.lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (bias && Build.VERSION.SDK_INT >= 33) it.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, EXPECTED_WORDS)
            if (switching && Build.VERSION.SDK_INT >= 34) {
                it.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                it.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("en-US", "es-MX"))
                it.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                it.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-US", "es-MX"))
            }
            tBegin = 0; tEnd = 0; partials = 0; detected = ""
            cands.text = ""
            listening = true
            try { r.startListening(it) } catch (e: Exception) { listening = false; status.text = "Could not start: ${e.message}" }
        }
        show()
    }

    private fun record(f: LiveFile, p: Phrase, noise: Boolean, bias: Boolean, switching: Boolean, list: List<String>, scores: FloatArray?,
                       tBegin: Long, tEnd: Long, partials: Int, err: String, detected: String) {
        val now = SystemClock.elapsedRealtime()
        val wers = list.map { wer(p.text, it) }
        val right = wers.indexOfFirst { it == 0.0 }
        val cs = list.mapIndexed { i, s -> "${s.replace("\"", "'").replace("|", "/")} [${scores?.getOrNull(i)?.let { "%.2f".format(Locale.US, it) } ?: "-"}]" }.joinToString(" | ")
        f.append(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\"%s\",%d,\"%s\",%.0f,%.0f,%d,%d,%d,%d,%s,%s\n",
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), Build.MODEL, Build.VERSION.RELEASE, p.id[0], p.id, p.lang,
            if (noise) "noise" else "quiet", if (bias) "on" else "off", if (switching) "on" else "off", airplane(), p.text,
            list.size, cs, wers.firstOrNull() ?: 100.0, wers.minOrNull() ?: 100.0, if (right >= 0) right + 1 else 0,
            if (tEnd > 0) now - tEnd else -1, if (tEnd > 0 && tBegin > 0) tEnd - tBegin else -1, partials, err, detected))
    }

    // ------------------------------------------------------------------ word errors

    private val NUM = mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "cero" to "0", "uno" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5",
        "seis" to "6", "siete" to "7", "ocho" to "8", "nueve" to "9", "diez" to "10", "okay" to "ok")

    /** Words, lower case, without accents or punctuation; number words as digits; "8PM" = "8 pm". */
    private fun words(s: String): List<String> {
        val plain = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace("p.m.", "pm").replace("a.m.", "am").replace(Regex("(\\d)(am|pm)"), "$1 $2").replace("i'm", "i am")
        return plain.split(Regex("[^a-z0-9ñ]+")).filter { it.isNotBlank() }.map { NUM[it] ?: it }
    }

    /** Word error rate in percent (edits needed / words expected). */
    private fun wer(expected: String, got: String): Double {
        val a = words(expected); val b = words(got)
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 100.0
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 100.0 * d[a.size][b.size] / a.size
    }
}
