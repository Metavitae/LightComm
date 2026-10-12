package com.metavitae.lightlab

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    /** Called when leaving the current screen: stops whatever it was doing. */
    private var stopCurrent: (() -> Unit)? = null
    private var onMenu = false
    private var pendingCamera: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // adb shortcut for CC: am start ... --es mode facts  (skips the safety screen)
        when (intent.getStringExtra("mode")) {
            "facts" -> withCamera { showFacts() }
            // adb: --es mode listen --ei rate 10     (rate = changes per second)
            "listen" -> withCamera { showListen(intent.getIntExtra("rate", 10).toDouble()) }
            // adb: --es mode send --ei pattern 0 --ei rate 10 --ei seconds 30
            // adb: --es mode message --es text HELLO --ei speed 0 --ei repeats 2   (speed 0 = 2 bits/s, 1 = 4 bits/s)
            "message" -> showTorch(autoMessage = Triple(intent.getStringExtra("text") ?: "HELLO",
                intent.getIntExtra("speed", 0), intent.getIntExtra("repeats", 2)))
            "send" -> showTorch(Triple(intent.getIntExtra("pattern", 0), intent.getIntExtra("rate", 10).toDouble(),
                intent.getIntExtra("seconds", 30).toDouble()))
            null -> showSafety()
            else -> showMenu()
        }
    }

    private fun scrollPage(build: LinearLayout.() -> Unit) {
        val col = column()
        col.build()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        setContentView(sv)
    }

    private fun leave() {
        stopCurrent?.invoke()
        stopCurrent = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onMenu) super.onBackPressed() else { leave(); showMenu() }
    }

    override fun onPause() {
        super.onPause()
        if (!onMenu && pendingCamera == null) { leave(); showMenu() }
    }

    private fun withCamera(f: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) f()
        else { pendingCamera = f; requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val f = pendingCamera
        pendingCamera = null
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) f?.invoke()
        else showMenu()
    }

    // ---------------------------------------------------------------- safety

    private fun showSafety() {
        onMenu = false
        scrollPage {
            text("Light Lab", 28f, bold = true)
            text("SAFETY FIRST", 20f, bold = true, color = Color.rgb(255, 200, 0))
            text("This app makes fast flashing light. Flashing at about 3 to 30 times per second can trigger seizures in people with photosensitive epilepsy.")
            text("• Only test where nobody can see the flashing without being warned first.")
            text("• Never aim the flashlight at anyone's eyes, including your own.")
            text("• Stop right away if anyone feels unwell.")
            text("• The flashlight gets hot during long tests.")
            button("I understand. Continue") { showMenu() }
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        onMenu = true
        scrollPage {
            text("Light Lab", 28f, bold = true)
            text("Results are saved in Download/LightLab (open Files by Google).", 14f, color = Color.LTGRAY)
            button("1. Phone facts (automatic, about 1 minute)") { onMenu = false; withCamera { showFacts() } }
            button("2. Send with the flashlight") { onMenu = false; showTorch() }
            button("3. Send with the screen") { onMenu = false; showScreenSetup() }
            button("4. Send with infrared") { onMenu = false; showIr() }
            button("5. Receive with the camera") { onMenu = false; withCamera { showReceiver() } }
            button("6. Listen for us (no aiming needed)") { onMenu = false; withCamera { showListen(10.0) } }
        }
    }

    // ----------------------------------------------------------------- facts

    private fun showFacts() {
        onMenu = false
        var status: TextView? = null
        var out: TextView? = null
        scrollPage {
            text("Phone facts", 24f, bold = true)
            text("Lay the phone face up on a table and don't touch it. The flashlight will blink.", 15f)
            status = text("Starting...", 16f, color = Color.rgb(255, 200, 0))
            out = text("", 12f)
        }
        Thread {
            val report = Facts(this) { s -> runOnUiThread { status?.text = s } }.run()
            val path = Results.save(this, "facts", report)
            runOnUiThread {
                status?.text = "Done. Saved: $path"
                out?.text = report
            }
        }.start()
    }

    // ----------------------------------------------------------------- torch

    private val speeds = listOf(2.0, 4.0, 10.0, 20.0, 30.0, 40.0, 60.0)
    private val speedNames = speeds.map { "${it.toInt()} changes per second" }

    /** auto = (pattern, changes per second, seconds): start right away (adb "send" mode). */
    private fun showTorch(auto: Triple<Int, Double, Double>? = null, autoMessage: Triple<String, Int, Int>? = null) {
        onMenu = false
        val tc = torchCamera(this)
        val sender = TorchSender(this)
        val msgSender = MessageSender(this)
        stopCurrent = { sender.running = false; msgSender.running = false }
        var status: TextView? = null
        lateinit var pattern: () -> Int
        lateinit var speed: () -> Int
        lateinit var length: () -> Int
        val lengths = listOf(10.0, 30.0, 120.0, 300.0, 600.0)
        fun go(pat: Int, rate: Double, secs: Double) {
            Thread {
                for (i in 3 downTo 1) { runOnUiThread { status?.text = "Starting in $i..." }; Thread.sleep(1000) }
                val r = sender.run(pat, rate, secs) { s -> runOnUiThread { status?.text = s } }
                val path = Results.save(this@MainActivity, "flashlight", r)
                runOnUiThread { status?.text = "Done. Saved: $path" }
            }.start()
        }
        scrollPage {
            text("Send with the flashlight", 24f, bold = true)
            text(if (tc == null) "This phone has no flashlight an app can use."
                 else "Flashlight brightness steps on this phone: ${tc.second}" + if (tc.second <= 1) " (on/off only)" else "", 14f, color = Color.LTGRAY)
            pattern = choice("Pattern", TorchSender.PATTERN_NAMES)
            speed = choice("Speed", speedNames, 2)
            length = choice("How long", listOf("10 seconds", "30 seconds", "2 minutes", "5 minutes (heat test)", "10 minutes (beacon)"))
            button("Start (3 second countdown)") { go(pattern(), speeds[speed()], lengths[length()]) }
            button("Stop") { sender.running = false; msgSender.running = false }
            text("Send a message", 20f, bold = true)
            val msgField = EditText(context).apply {
                setText("HELLO"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Short text, plain letters"
            }
            addView(msgField)
            for ((i, sp) in MessageFormat.SPEEDS.withIndex()) button("Send message at ${sp.name}") {
                sendMessage(msgSender, msgField.text.toString(), i, 2) { st -> status?.text = st }
            }
            if (tc != null && tc.second > 1) button("Step through brightness levels") {
                Thread {
                    val r = sender.brightnessSteps { s -> runOnUiThread { status?.text = s } }
                    val path = Results.save(this@MainActivity, "flashlight-levels", r)
                    runOnUiThread { status?.text = "Done. Saved: $path" }
                }.start()
            }
            status = text("", 16f, color = Color.rgb(255, 200, 0))
        }
        if (auto != null) go(auto.first, auto.second, auto.third)
        autoMessage?.let { (txt, sp, rep) -> sendMessage(msgSender, txt, sp, rep) { st -> status?.text = st } }
    }

    private fun sendMessage(ms: MessageSender, text: String, speed: Int, repeats: Int, show: (String) -> Unit) {
        Thread {
            for (i in 3 downTo 1) { runOnUiThread { show("Message starts in $i...") }; Thread.sleep(1000) }
            runOnUiThread { show("Sending beacon, then the message...") }
            val r = ms.run(text.uppercase(), MessageFormat.SPEEDS[speed], repeats) { st -> runOnUiThread { show(st) } }
            val path = Results.save(this, "message-sent", r)
            runOnUiThread { show("Done. Saved: $path") }
        }.start()
    }

    // -------------------------------------------------------------------- IR

    private fun showIr() {
        val sender = IrSender(this)
        stopCurrent = { sender.running = false }
        var status: TextView? = null
        lateinit var speed: () -> Int
        val rates = listOf(1.0, 2.0, 5.0, 10.0, 20.0)
        scrollPage {
            text("Send with infrared", 24f, bold = true)
            text(sender.describe(), 14f, color = Color.LTGRAY)
            text("Infrared is invisible to your eyes. The other phone's camera may or may not see it.", 14f)
            speed = choice("Flashes per second", rates.map { "${it.toInt()} per second" }, 1)
            button("Start 20 seconds") {
                Thread {
                    val r = sender.run(rates[speed()], 20.0) { s -> runOnUiThread { status?.text = s } }
                    val path = Results.save(this@MainActivity, "infrared", r)
                    runOnUiThread { status?.text = "Done. Saved: $path" }
                }.start()
            }
            button("Stop") { sender.running = false }
            status = text("", 16f, color = Color.rgb(255, 200, 0))
        }
    }

    // ---------------------------------------------------------------- screen

    private fun showScreenSetup() {
        lateinit var pattern: () -> Int
        lateinit var speed: () -> Int
        lateinit var levels: () -> Int
        val levelCounts = listOf(2, 4, 8)
        scrollPage {
            text("Send with the screen", 24f, bold = true)
            text("The whole screen flashes for 20 seconds at full brightness. Tap the screen to stop early.", 14f)
            pattern = choice("Pattern", listOf("Steady blink", "Test pattern (known random)", "Staircase through brightness levels", "Random brightness levels"))
            levels = choice("Brightness levels", levelCounts.map { "$it levels" })
            speed = choice("Speed", speedNames, 2)
            button("Start") { showScreenFlash(pattern(), levelCounts[levels()], speeds[speed()]) }
        }
    }

    private fun showScreenFlash(kind: Int, levels: Int, rate: Double) {
        val v = View(this)
        v.setBackgroundColor(Color.BLACK)
        setContentView(v)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val frameGaps = mutableListOf<Double>()
        val changes = StringBuilder("k,level,frame_time_ms\n")
        var t0 = 0L
        var last = 0L
        var lastK = -1
        var running = true
        val finish = {
            if (running) {
                running = false
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                window.insetsController?.show(WindowInsets.Type.systemBars())
                val sb = StringBuilder(Results.header("Screen sender"))
                sb.append("Pattern kind $kind, $levels levels, $rate changes per second\n")
                sb.append("Screen refresh: ${display?.refreshRate} Hz\n")
                sb.append("Gap between screen frames (ms): ${Stats(frameGaps).line("")}\n")
                val expected = 1000.0 / (display?.refreshRate ?: 60f)
                sb.append("Late frames (gap > 1.5x normal): ${frameGaps.count { it > expected * 1.5 }} of ${frameGaps.size}\n")
                sb.append("Start (elapsedRealtime ms): ${t0 / 1_000_000}\n\nCHANGES\n").append(changes)
                val path = Results.save(this, "screen", sb.toString())
                showMenu()
                android.widget.Toast.makeText(this, "Saved: $path", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        stopCurrent = { running = false }
        v.setOnClickListener { finish() }
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameNs: Long) {
                if (!running) return
                if (t0 == 0L) t0 = frameNs
                if (last != 0L) frameGaps.add((frameNs - last) / 1e6)
                last = frameNs
                val k = ((frameNs - t0) / 1e9 * rate).toInt()
                if (k != lastK) {
                    lastK = k
                    val lvl = Patterns.level(kind, k, levels)
                    val g = (255 * lvl / (levels - 1))
                    v.setBackgroundColor(Color.rgb(g, g, g))
                    changes.append("$k,$lvl,${String.format(Locale.US, "%.2f", (frameNs - t0) / 1e6)}\n")
                }
                if (frameNs - t0 > 20_000_000_000L) finish() else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        // short black pause so the receiver can settle
        v.postDelayed({ Choreographer.getInstance().postFrameCallback(cb) }, 1500)
    }

    // -------------------------------------------------------------- receiver

    private fun showReceiver() {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        var camIdx = 0
        var manual = true
        val exposures = listOf(250_000L, 1_000_000L, 4_000_000L, 16_000_000L)
        var expIdx = 1
        var myTorch = false
        val log = Receiver()

        val col = column()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        val tv = TextureView(this)
        col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        val graph = Graph(this, log)
        col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        val live = col.text("", 18f, bold = true, color = Color.rgb(255, 200, 0))
        val settings = col.text("", 13f, color = Color.LTGRAY)
        val note = EditText(this).apply {
            hint = "Optional note, e.g. room, 5 m, wall"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.addView(note)
        setContentView(sv)

        var cam: CamSession? = null
        fun describe() {
            val c = cams[camIdx]
            settings.text = "${c.second}\nExposure: " + (if (manual) "fixed ${exposures[expIdx] / 1000} microseconds, ISO 400" else "automatic, then locked") +
                "\nOwn flashlight: ${if (myTorch) "ON" else "off"}"
        }
        fun start() {
            cam?.close()
            log.reset()
            val c = CamSession(this, cams[camIdx].first, tv) { f -> log.add(f) }
            c.manual = manual; c.expNs = exposures[expIdx]; c.iso = 400; c.fps = 30; c.torch = myTorch
            c.onError = { e -> runOnUiThread { live.text = "Camera problem: $e" } }
            cam = c
            describe()
            if (tv.isAvailable) c.open {} else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { c.open {} }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
        fun save(): String {
            val c = cam ?: return ""
            val conditions = "Camera: ${cams[camIdx].second}\nPicture size analysed: ${c.size.width}x${c.size.height}\n" +
                "Exposure: " + (if (manual) "fixed ${exposures[expIdx] / 1000} us, ISO 400" else "automatic then locked") +
                "\nAsked frame rate: 30 fps\nOwn flashlight on: $myTorch\nNote: ${note.text}\n"
            return Results.save(this, "receive", Results.header("Camera receiver") + conditions + log.report())
        }

        col.button("Save results (and keep going)") {
            val p = save(); live.text = "Saved: $p"; log.reset()
        }
        col.button("Switch camera") { camIdx = (camIdx + 1) % cams.size; start() }
        col.button("Exposure: fixed / automatic") { manual = !manual; start() }
        col.button("Shorter or longer fixed exposure") { expIdx = (expIdx + 1) % exposures.size; manual = true; start() }
        col.button("Own flashlight on/off (two-way check)") { myTorch = !myTorch; cam?.torch = myTorch; cam?.apply(); describe() }

        var alive = true
        stopCurrent = {
            alive = false
            if (log.count() > 60) save()
            cam?.close(); cam = null
        }
        val ticker = object : Runnable {
            override fun run() {
                if (!alive) return
                live.text = log.liveLine()
                graph.invalidate()
                live.postDelayed(this, 200)
            }
        }
        live.post(ticker)
        start()
    }


    // ------------------------------------------------------------ listen for us

    private fun showListen(startRate: Double) {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        val rates = listOf(4.0, 10.0, 20.0)
        var rateIdx = rates.indexOf(startRate).let { if (it < 0) 1 else it }
        val exposures = listOf<Long?>(null, 16_000_000L, 4_000_000L, 1_000_000L)  // null = automatic, then locked
        var expIdx = 0
        val tone = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }

        val col = column()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        col.text("Listen for us", 24f, bold = true)
        val status = col.text("Starting camera...", 22f, bold = true, color = Color.rgb(255, 200, 0))
        val box = android.widget.FrameLayout(this)
        val tv = TextureView(this)
        val heat = Heat(this)
        box.addView(tv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(heat, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.text("Camera view. No need to aim exactly: just the general direction.", 13f, color = Color.LTGRAY)
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        val detail = col.text("", 13f, color = Color.LTGRAY)
        val settings = col.text("", 13f, color = Color.LTGRAY)
        setContentView(sv)

        var cam: CamSession? = null
        var listener = Listener(rates[rateIdx] / 2)
        val log = StringBuilder("t_s,best_spot,spot_x,spot_y,best_big_zone,whole_picture,found\n")
        var startedAt = 0L
        var lastBeep = 0L
        var alive = true
        val message = col.text("", 24f, bold = true, color = Color.rgb(120, 200, 255))
        // Once we hear us, follow that spot/zone and try to read a message from it.
        var lock: Pair<Int, Int>? = null   // (kind, index)
        val decoders = MessageFormat.SPEEDS.map { MessageDecoder(it) }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var decoding = false
        var lastText = ""

        fun describe() {
            settings.text = "${cams[0].second}\nListening for: ${rates[rateIdx].toInt()} changes per second\n" +
                "Exposure: " + (exposures[expIdx]?.let { "fixed ${it / 1000} microseconds" } ?: "automatic, then locked")
        }
        fun start() {
            cam?.close()
            listener = Listener(rates[rateIdx] / 2)
            val l = listener
            var frames = 0
            val c = CamSession(this, cams[0].first, tv) { }
            val e = exposures[expIdx]
            c.manual = e != null; c.expNs = e ?: 1_000_000L; c.iso = 400; c.fps = 30
            c.onError = { err -> runOnUiThread { status.text = "Camera problem: $err" } }
            c.zoneSink = { ts, fine ->
                l.add(ts, fine)
                frames++
                lock?.let { (kind, idx) ->
                    val value = Listener.channel(fine, kind, idx)
                    decoders.forEach { it.add(ts / 1e9, value) }
                    if (frames % 60 == 0 && !decoding) {
                        decoding = true
                        val snaps = decoders.map { it.copy() }
                        worker.execute {
                            val got = snaps.mapIndexedNotNull { i, d -> d.tryDecode()?.let { MessageFormat.SPEEDS[i] to it } }
                                .maxByOrNull { it.second.atSec }
                            decoding = false
                            if (got != null) runOnUiThread {
                                if (!alive) return@runOnUiThread
                                val (sp, d) = got
                                message.text = "MESSAGE: \"${d.text}\"\n(${sp.name}, sureness ${(d.confidence * 100).toInt()}%)"
                                if (d.text + d.atSec != lastText) {
                                    lastText = d.text + d.atSec
                                    log.append(String.format(Locale.US, "MESSAGE at %.1f s: \"%s\" speed=%s sureness=%.2f\n",
                                        (SystemClock.elapsedRealtime() - startedAt) / 1000.0, d.text, sp.name, d.confidence))
                                    tone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 400)
                                }
                            }
                        }
                    }
                }
                if (frames % 15 == 0) l.analyze()?.let { r ->
                    val found = r.best >= Listener.FOUND_SPOT || r.coarse >= Listener.FOUND_ZONE || r.whole >= Listener.FOUND_WHOLE
                    if (found && lock == null) {
                        val scores = listOf(r.best / Listener.FOUND_SPOT, r.coarse / Listener.FOUND_ZONE, r.whole / Listener.FOUND_WHOLE)
                        val kind = scores.indices.maxByOrNull { scores[it] }!!
                        lock = kind to when (kind) { 0 -> r.bestY * CamSession.ZX + r.bestX; 1 -> r.coarseIdx; else -> 0 }
                        log.append("LOCKED on ${listOf("small spot", "big zone", "whole picture")[kind]} ${lock!!.second}\n")
                        runOnUiThread { message.text = "Locked on. Reading any message..." }
                    }
                    log.append(String.format(Locale.US, "%.1f,%.1f,%d,%d,%.1f,%.1f,%d\n",
                        (SystemClock.elapsedRealtime() - startedAt) / 1000.0, r.best, r.bestX, r.bestY, r.coarse, r.whole, if (found) 1 else 0))
                    runOnUiThread {
                        if (!alive) return@runOnUiThread
                        heat.show(r, c.sensorOrientation, found)
                        status.text = if (found) "WE HEAR US!  strength ${maxOf(r.best, r.coarse, r.whole).toInt()}"
                                      else "Listening...  not heard yet"
                        status.setTextColor(if (found) Color.rgb(80, 255, 120) else Color.rgb(255, 200, 0))
                        detail.text = String.format(Locale.US, "Best small spot: %.0f (need 500)   Best big zone: %.0f (need 150)   Whole picture: %.0f (need 100)", r.best, r.coarse, r.whole)
                        val now = SystemClock.elapsedRealtime()
                        if (found && now - lastBeep > 450) { lastBeep = now; tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150) }
                    }
                }
            }
            cam = c
            describe()
            runOnUiThread { status.text = "Listening... (needs 4 seconds to start)" }
            if (tv.isAvailable) c.open {} else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { c.open {} }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
        fun save(): String {
            val head = Results.header("Listen for us") + settings.text + "\nFound when small spot >= ${Listener.FOUND_SPOT}, big zone >= ${Listener.FOUND_ZONE} or whole picture >= ${Listener.FOUND_WHOLE}\n\nEVERY HALF SECOND\n"
            return Results.save(this, "listen", head + log)
        }

        col.button("Save results (and keep going)") { val p = save(); status.text = "Saved: $p"; log.setLength(0); log.append("t_s,best_spot,spot_x,spot_y,best_big_zone,whole_picture,found\n") }
        col.button("Listen again (forget where we heard us)") {
            lock = null; decoders.forEach { it.reset() }; message.text = ""; lastText = ""
        }
        col.button("Change rhythm to listen for") { rateIdx = (rateIdx + 1) % rates.size; start() }
        col.button("Change exposure") { expIdx = (expIdx + 1) % exposures.size; start() }

        stopCurrent = {
            alive = false
            if (log.count { it == '\n' } > 3) save()
            cam?.close(); cam = null
            tone?.release()
            worker.shutdown()
        }
        startedAt = SystemClock.elapsedRealtime()
        start()
    }

    /** Draws, over the camera view, the zones that carry our rhythm. */
    class Heat(ctx: android.content.Context) : View(ctx) {
        private var map: FloatArray? = null
        private var orient = 90
        private var bx = 0
        private var by = 0
        private var found = false
        private val cell = Paint()
        private val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(80, 255, 120) }

        fun show(r: Listener.Result, orientation: Int, f: Boolean) {
            map = r.map; orient = orientation; bx = r.bestX; by = r.bestY; found = f
            invalidate()
        }

        /** Sensor-grid zone to normalized screen position, for a phone held upright. */
        private fun toScreen(x: Float, y: Float): Pair<Float, Float> {
            val nx = x / CamSession.ZX
            val ny = y / CamSession.ZY
            return when (orient) {
                90 -> (1 - ny) to nx
                270 -> ny to (1 - nx)
                180 -> (1 - nx) to (1 - ny)
                else -> nx to ny
            }
        }

        override fun onDraw(c: Canvas) {
            val m = map ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val swap = orient == 90 || orient == 270
            val cw = w / (if (swap) CamSession.ZY else CamSession.ZX)
            val ch = h / (if (swap) CamSession.ZX else CamSession.ZY)
            for (y in 0 until CamSession.ZY) for (x in 0 until CamSession.ZX) {
                val v = m[y * CamSession.ZX + x]
                if (v < 100f) continue
                val a = ((v / Listener.FOUND.toFloat()).coerceAtMost(1f) * 160).toInt()
                cell.color = Color.argb(a, 80, 255, 120)
                val (sx, sy) = toScreen(x + 0.5f, y + 0.5f)
                c.drawRect(sx * w - cw / 2, sy * h - ch / 2, sx * w + cw / 2, sy * h + ch / 2, cell)
            }
            if (found) {
                val (sx, sy) = toScreen(bx + 0.5f, by + 0.5f)
                c.drawCircle(sx * w, sy * h, 40f, ring)
            }
        }
    }

    /** Small live chart of the brightest-spot value. */
    class Graph(ctx: android.content.Context, private val r: Receiver) : View(ctx) {
        private val line = Paint().apply { color = Color.rgb(255, 200, 0); strokeWidth = 3f; isAntiAlias = true }
        private val mid = Paint().apply { color = Color.GRAY; strokeWidth = 1f }
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(20, 20, 20))
            val v = r.recent()
            if (v.size < 2) return
            val w = width.toFloat(); val h = height.toFloat()
            val step = w / (v.size - 1)
            for (i in 1 until v.size) {
                c.drawLine((i - 1) * step, h - v[i - 1] / 255f * h, i * step, h - v[i] / 255f * h, line)
            }
            val t = r.threshold()
            c.drawLine(0f, h - t / 255f * h, w, h - t / 255f * h, mid)
        }
    }
}
