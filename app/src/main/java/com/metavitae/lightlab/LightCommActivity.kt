package com.metavitae.lightlab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LightComm, first approximation of the real app (Oct 14, for the founder's test at work).
 * The screens follow Chat's "Night Watch" designs and live in assets/lc (HTML, offline: fonts and
 * three.js are inside the app). Underneath is everything measured this week: Talk (replies, resends,
 * speed steps, short openings, fine squares, glare setting, Find). The page talks to it through `LC`.
 */
class LightCommActivity : Activity() {
    private lateinit var web: WebView
    /**
     * The live camera picture behind the Chat and Listen screens (founder, Oct 14: aim while chatting). It also fixes
     * the POCO, which gave black pictures with no preview at all (Light Lab, which shows one, saw fine).
     */
    private lateinit var preview: android.view.TextureView
    private var talk: Talk? = null
    private var talkKey = ""
    private val events = ConcurrentLinkedQueue<JSONObject>()
    private var tone: ToneGenerator? = null
    private var file: LiveFile? = null
    @Volatile private var findScore = 0.0
    @Volatile private var findAt = 0L
    @Volatile private var phase = "idle"
    /** Sounds for this phone's own events: "on", "vibrate" or "off" (stealth, battery). */
    @Volatile private var sounds = "on"
    /** One cue for this phone's own event: a tone, a short buzz, or nothing. */
    private fun cue(toneType: Int, ms: Int) {
        when (sounds) {
            "on" -> tone?.startTone(toneType, ms)
            "vibrate" -> try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong().coerceAtMost(200), 180)) } catch (_: Exception) {}
        }
    }
    private var findLogged = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.statusBarColor = android.graphics.Color.BLACK; window.navigationBarColor = android.graphics.Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "LC")
        // Keep page changes (opening -> app) inside this view, and page messages in the phone log.
        web.webViewClient = android.webkit.WebViewClient()
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i("LightCommPage", "${m.message()} (${m.sourceId()}:${m.lineNumber()})"); return true
            }
        }
        preview = android.view.TextureView(this)
        val root = android.widget.FrameLayout(this)
        // True shape (the camera gives 4:3 pictures; held upright that is 3 wide by 4 high), full width, at the top.
        val wPx = resources.displayMetrics.widthPixels
        root.addView(preview, android.widget.FrameLayout.LayoutParams(wPx, wPx * 4 / 3))
        preview.alpha = 0.7f
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        root.addView(web, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }
        // The opening plays on a fresh open only (not when coming back from the background).
        web.loadUrl(if (b == null) "file:///android_asset/lc/intro.html" else "file:///android_asset/lc/app.html")
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onDestroy() { stopTalk(); tone?.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack ? goBack() : false") { r -> if (r != "true") runOnUiThread { @Suppress("DEPRECATION") super.onBackPressed() } }
    }

    private fun push(type: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        for ((k, v) in kv) o.put(k, v)
        events.add(o)
    }

    private fun stopTalk() { talk?.stop(); talk = null; talkKey = ""; phase = "idle"; file?.append("\nSTOPPED.\n"); widget("LightComm", "Not listening · tap to open") }
    // Widget: changes only when something happens (never polls, never wakes the camera).
    private var chatName = ""
    private var unseen = 0
    @Volatile private var visible = false
    private fun widget(title: String, caption: String) = try { LightCommWidget.show(this, title, caption) } catch (_: Exception) {}
    override fun onResume() { super.onResume(); visible = true; unseen = 0; if (talk != null) widget("Listening", chatName) }
    override fun onPause() { visible = false; super.onPause() }

    private fun startTalk(key: String) {
        if (talk != null && talkKey == key) return
        stopTalk()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { push("problem", "text" to "Camera permission is needed to listen."); return }
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { push("problem", "text" to "No back camera."); return }
        val f = LiveFile(this, "lightcomm"); file = f
        val opts = Talk.Opts(emptyList(), 5, true, true, 2.0, 12.0, 4, 2.0, 0.0, 0, emptyList(), "lightcomm", "lightcomm")
        val t = Talk(this, preview, cams[0].first, MessageFormat.norm(key), opts, f) { }
        t.watchFind = true
        t.events = object : Talk.Events {
            override fun sending(k: Int, text: String, attempt: Int, mode: BrightFormat.Mode, short: Boolean) {
                widget("Sending as light", chatName); phase = "sending"; push("sending", "k" to k, "attempt" to attempt, "perSec" to mode.perSec, "short" to short)
            }
            override fun sent(k: Int, text: String, ok: Boolean, tries: Int, seconds: Double) {
                widget(if (ok) "Delivered" else "Not delivered", chatName); phase = "listening"; push("sent", "k" to k, "ok" to ok, "tries" to tries, "seconds" to seconds)
                cue(if (ok) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK, 300)
            }
            override fun heard(r: BrightDecoder.Result, dup: Boolean) {
                val zones = r.blocks.count { it < BrightListener.NB * BrightListener.NC }
                val fine = r.blocks.count { it >= BrightListener.NCH }
                push("heard", "ok" to r.ok, "dup" to dup, "text" to r.text, "from" to BrightFormat.phoneName(r.phoneTag),
                    "snr" to r.snr, "zones" to zones, "fine" to fine, "used" to JSONArray(r.blocks.filter { it < BrightListener.NB * BrightListener.NC }))
                if (r.ok && !dup) { cue(ToneGenerator.TONE_PROP_ACK, 400)
                    if (!visible) { unseen++; widget(BrightFormat.phoneName(r.phoneTag), "$unseen new · tap to open") } else widget("Listening", chatName) }
            }
            override fun problem(text: String) { push("problem", "text" to text) }
            override fun aim(score: Double) {
                findScore = score; findAt = System.currentTimeMillis()
                if (score >= 0.55 && findAt - findLogged > 3000) { findLogged = findAt; file?.append(String.format(java.util.Locale.US, "# find seen: match %.2f\n", score)) }
                when {
                    score >= 0.75 -> cue(ToneGenerator.TONE_DTMF_D, 120)
                    score >= 0.55 && (findAt / 700) % 2 == 0L -> cue(ToneGenerator.TONE_DTMF_1, 250)
                }
            }
        }
        talk = t; talkKey = key; phase = "starting"
        t.start()
    }

    /** What the page can call. Strings in, JSON strings out. */
    inner class Bridge {
        @JavascriptInterface fun phone(): String = BrightFormat.KNOWN_PHONES[Build.MODEL] ?: Build.MODEL
        @JavascriptInterface fun open(key: String) = runOnUiThread { startTalk(key) }
        @JavascriptInterface fun chatName(name: String) { chatName = name; widget("Listening", name) }
        @JavascriptInterface fun close() = runOnUiThread { stopTalk() }
        @JavascriptInterface fun send(text: String) { talk?.enqueue(text.take(80)) }
        @JavascriptInterface fun find(sec: Int) { talk?.findSec = sec.coerceIn(5, 120) }
        @JavascriptInterface fun log(s: String) { file?.append("# screen: $s\n") }
        @JavascriptInterface fun setSounds(s: String) { sounds = s }
        /** One short vibration (the opening's wordmark when Sounds = Vibrate). */
        @JavascriptInterface fun buzz(ms: Int) {
            try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong().coerceIn(10, 300), 180)) } catch (_: Exception) {}
        }
        /** Camera picture behind the chat: shown, or dimmed to nothing (the camera keeps listening either way). */
        @JavascriptInterface fun cameraBehind(on: Boolean) = runOnUiThread { preview.alpha = if (on) 0.7f else 0.01f }
        @JavascriptInterface fun brightness(level: Float) = runOnUiThread {
            window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.02f, 1f) }
        }
        @JavascriptInterface fun maxVolume() {
            val am = getSystemService(AudioManager::class.java)
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) } catch (_: Exception) {}
        }
        /** New events since the last call, and the live state for the screens. */
        @JavascriptInterface fun poll(): String {
            val ev = JSONArray(); while (true) ev.put(events.poll() ?: break)
            val t = talk
            val o = JSONObject().put("events", ev).put("phase", if (t == null) "idle" else phase)
            if (t != null) {
                val rx = t.rx
                if (phase == "starting" && rx.readyAt > 0) phase = "listening"
                o.put("hearing", rx.hearing).put("progress", rx.progress()).put("waiting", t.waiting())
                o.put("find", if (System.currentTimeMillis() - findAt < 2000) findScore else 0.0)
                o.put("live", JSONArray(rx.liveliness().map { it.toDouble() }))
                o.put("orient", t.sensorOrientation)
                if (rx.readyAt > 0) o.put("bg", rx.background()).put("top", rx.brightTop())
            }
            return o.toString()
        }
    }
}
