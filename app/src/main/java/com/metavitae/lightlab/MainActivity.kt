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
            // adb: --es mode listen [--es pass WORD]
            "listen" -> { intent.getStringExtra("pass")?.let { savePass(it) }; withCamera { showListen(10.0) } }
            // adb: --es mode send --ei pattern 0 --ei rate 10 --ei seconds 30
            // adb: --es mode message --es text HELLO --ei speed 0 --ei repeats 2   (speed 0 = 2 bits/s, 1 = 4 bits/s)
            "message" -> { intent.getStringExtra("pass")?.let { savePass(it) }; showTorch(autoMessage = Triple(intent.getStringExtra("text") ?: "HELLO",
                intent.getIntExtra("speed", 0), intent.getIntExtra("repeats", 2))) }
            // adb: --es mode duplex --es role caller|answerer [--es pass W] [--es text HI --ei after 8]
            "duplex" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                // test overrides: --ei halfA 4 --ei halfB 6 --ei bit 24
                DuplexFormat.CALLER = DuplexFormat.Role("Caller", intent.getIntExtra("halfA", DuplexFormat.CALLER.halfFrames))
                DuplexFormat.ANSWERER = DuplexFormat.Role("Answerer", intent.getIntExtra("halfB", DuplexFormat.ANSWERER.halfFrames))
                DuplexFormat.BIT_FRAMES = intent.getIntExtra("bit", DuplexFormat.BIT_FRAMES)
                withCamera { showDuplex(if (intent.getStringExtra("role") == "answerer") DuplexFormat.ANSWERER else DuplexFormat.CALLER,
                    intent.getStringExtra("text"), intent.getIntExtra("after", 8)) } }
            // adb: --es mode calib-send|calib-recv [--es pass W] [--ei iso 100] [--ei secs 40]
            "calib-send" -> { intent.getStringExtra("pass")?.let { savePass(it) }; showCalibSend(true) }
            "calib-recv" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                withCamera { showCalibRecv(intent.getIntExtra("iso", 100), intent.getIntExtra("secs", 40)) } }
            // adb: --es mode bright-send [--es modes 0,1,2,3,4] [--es msgs 0,1,2,3] [--ei repeats 10] [--ei gap 3] [--ei beacon 0] [--es text "..."] [--es pass W]
            "bright-send" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                showBrightSend(BrightRunPlan.from(intent)) }
            // adb: --es mode bright-recv [--es room dim|lit] [--es paces 5] [--ei iso 0 (0 = automatic)] [--ei trace 90 (seconds of raw brightness saved after ready)] [--es pass W]
            "bright-recv" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                withCamera { showBrightRecv(intent.getStringExtra("room") ?: "dim", intent.getStringExtra("paces") ?: "hand-width", intent.getIntExtra("iso", 0), intent.getIntExtra("trace", 0),
                    intent.getIntExtra("expect", -1)) } }
            // adb: --es mode bright-reply --ei nonce N [--ez ok true]   (short reply to message N: got it / saw it but could not read it)
            "bright-reply" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                showBrightReply(intent.getIntExtra("nonce", 0), intent.getBooleanExtra("ok", true)) }
            // adb: --es mode talk [--es send "A|B|C" (texts to send; none = only listen and reply)] [--ei speed 5] [--ez auto true] [--ez short true]
            //      [--ei after 3000 (ms from a message's end to the reply)] [--ei wait 12 (s for a reply)] [--ei tries 4] [--ei gap 2] [--ei start 0]
            //      [--es expect "A|B|C" (texts the other phone sends, to count errors)] [--ei iso 0] [--es room lit] [--es paces 6m] [--es pass W]
            "talk" -> { intent.getStringExtra("pass")?.let { savePass(it) }
                fun list(n: String) = intent.getStringExtra(n)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val opts = Talk.Opts(list("send"), intent.getIntExtra("speed", 5), intent.getBooleanExtra("auto", true), intent.getBooleanExtra("short", true),
                    intent.getIntExtra("after", 3000) / 1000.0, intent.getIntExtra("wait", 12).toDouble(), intent.getIntExtra("tries", 4),
                    intent.getIntExtra("gap", 2).toDouble(), intent.getIntExtra("start", 0).toDouble(), intent.getIntExtra("iso", 0),
                    list("expect"), intent.getStringExtra("room") ?: "lit", intent.getStringExtra("paces") ?: "hand-width")
                withCamera { showTalk(opts) } }
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

    private fun savedPass(): String =
        getSharedPreferences("lightlab", MODE_PRIVATE).getString("pass", MessageFormat.DEFAULT_PASS) ?: MessageFormat.DEFAULT_PASS

    private fun savePass(p: String) = getSharedPreferences("lightlab", MODE_PRIVATE).edit().putString("pass", MessageFormat.norm(p)).apply()

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
            button("8. Calibrate: send brightness steps") { onMenu = false; showCalibSend(false) }
            button("9. Calibrate: measure brightness steps") { onMenu = false; withCamera { showCalibRecv(100, 40) } }
            button("7. Two-way: talk and listen at once") { onMenu = false; withCamera { showDuplex(DuplexFormat.CALLER, null, 0) } }
            button("6. Listen for us (no aiming needed)") { onMenu = false; withCamera { showListen(10.0) } }
            text("Messages in brightness steps (Oct 12)", 18f, bold = true)
            button("10. Brightness messages: SEND") { onMenu = false; showBrightSend(null) }
            button("11. Brightness messages: RECEIVE") { onMenu = false; withCamera { showBrightRecv("dim", "hand-width", 0) } }
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
            text("Passphrase (both phones must use the same one)", 14f, color = Color.LTGRAY)
            val passField = EditText(context).apply { setText(savedPass()); setTextColor(Color.WHITE) }
            addView(passField)
            text("Message", 14f, color = Color.LTGRAY)
            val msgField = EditText(context).apply {
                setText("HELLO"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Short text, plain letters"
            }
            addView(msgField)
            for ((i, sp) in MessageFormat.SPEEDS.withIndex()) button("Send message at ${sp.name}") {
                savePass(passField.text.toString())
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
            val r = ms.run(text.uppercase(), MessageFormat.SPEEDS[speed], repeats, savedPass()) { st -> runOnUiThread { show(st) } }
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
        var pass = savedPass()
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
        var listener = Listener(MessageFormat.beaconHz(pass))
        val log = StringBuilder("t_s,best_spot,spot_x,spot_y,best_big_zone,whole_picture,found\n")
        var startedAt = 0L
        var lastBeep = 0L
        var alive = true
        val message = col.text("", 24f, bold = true, color = Color.rgb(120, 200, 255))
        // Once we hear us, follow that spot/zone and try to read a message from it.
        // Once we hear us, read from three places at once: best small spot, best big zone, whole picture.
        var locked = false
        val lockIdx = IntArray(3)
        fun makeDecoders() = (0 until 3).flatMap { MessageFormat.SPEEDS.map { sp -> MessageDecoder(sp, pass) } }
        var decoders = makeDecoders()   // index = channel * 2 + speed
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var decoding = false
        var lastText = ""

        fun describe() {
            settings.text = "${cams[0].second}\nPassphrase rhythm: ${MessageFormat.beaconHz(pass)} flashes per second\n" +
                "Exposure: " + (exposures[expIdx]?.let { "fixed ${it / 1000} microseconds" } ?: "automatic, then locked")
        }
        fun start() {
            cam?.close()
            listener = Listener(MessageFormat.beaconHz(pass))
            val l = listener
            var frames = 0
            val c = CamSession(this, cams[0].first, tv) { }
            val e = exposures[expIdx]
            c.manual = e != null; c.expNs = e ?: 1_000_000L; c.iso = 400; c.fps = 30
            c.onError = { err -> runOnUiThread { status.text = "Camera problem: $err" } }
            c.zoneSink = { ts, fine ->
                l.add(ts, fine)
                frames++
                if (locked) {
                    for (ch in 0 until 3) {
                        val value = Listener.channel(fine, ch, lockIdx[ch])
                        decoders[ch * 2].add(ts / 1e9, value)
                        decoders[ch * 2 + 1].add(ts / 1e9, value)
                    }
                    if (frames % 60 == 0 && !decoding) {
                        decoding = true
                        val snaps = decoders.map { it.copy() }
                        worker.execute {
                            val all = snaps.mapIndexedNotNull { i, d -> d.tryDecode()?.let { MessageFormat.SPEEDS[i % 2] to it } }
                            val got = all.filter { it.second.forUs }.maxByOrNull { it.second.atSec } ?: all.maxByOrNull { it.second.atSec }
                            decoding = false
                            if (got != null) runOnUiThread {
                                if (!alive) return@runOnUiThread
                                val (sp, d) = got
                                message.text = if (d.forUs) "MESSAGE: \"${d.text}\"\n(${sp.name}, sureness ${(d.confidence * 100).toInt()}%)"
                                               else "Someone is signalling, but not for us\n(different passphrase, can't read it)"
                                if (d.text + d.atSec != lastText) {
                                    lastText = d.text + d.atSec
                                    log.append(String.format(Locale.US, "MESSAGE at %.1f s: \"%s\" speed=%s sureness=%.2f %s\n",
                                        (SystemClock.elapsedRealtime() - startedAt) / 1000.0, if (d.forUs) d.text else "<someone else's passphrase>", sp.name, d.confidence, d.debug))
                                    tone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 400)
                                }
                            }
                        }
                    }
                }
                if (frames % 15 == 0) l.analyze()?.let { r ->
                    val found = r.best >= Listener.FOUND_SPOT || r.coarse >= Listener.FOUND_ZONE || r.whole >= Listener.FOUND_WHOLE
                    if (found) {
                        // Follow the strongest spot and zone while the beacon lasts.
                        val spot = r.bestY * CamSession.ZX + r.bestX
                        if (!locked || spot != lockIdx[0]) { lockIdx[0] = spot; decoders[0].reset(); decoders[1].reset() }
                        if (!locked || r.coarseIdx != lockIdx[1]) { lockIdx[1] = r.coarseIdx; decoders[2].reset(); decoders[3].reset() }
                        if (!locked) {
                            locked = true
                            log.append("LOCKED on spot ${lockIdx[0]}, zone ${lockIdx[1]} and the whole picture\n")
                            runOnUiThread { message.text = "Locked on. Reading any message..." }
                        }
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
            return Results.save(this, "listen", head + log + "\nRAW CHANNEL (big zone, fast decoder)\n" + decoders[3].dump())
        }

        col.button("Save results (and keep going)") { val p = save(); status.text = "Saved: $p"; log.setLength(0); log.append("t_s,best_spot,spot_x,spot_y,best_big_zone,whole_picture,found\n") }
        col.button("Listen again (forget where we heard us)") {
            locked = false; decoders.forEach { it.reset() }; message.text = ""; lastText = ""
        }
        col.text("Passphrase (same as the sender)", 14f, color = Color.LTGRAY)
        val passField = EditText(this).apply { setText(pass); setTextColor(Color.WHITE) }
        col.addView(passField)
        col.button("Use this passphrase") {
            savePass(passField.text.toString()); pass = savedPass()
            locked = false; decoders = makeDecoders(); message.text = ""; lastText = ""
            start()
        }
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


    // ---------------------------------------------------------------- two-way

    private fun showDuplex(role: DuplexFormat.Role, autoText: String?, autoAfter: Int) {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        val pass = savedPass()
        val other = DuplexFormat.other(role)
        val tone = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }
        val col = column()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        col.text("Two-way: ${role.name}", 24f, bold = true)
        col.text("This phone blinks ${role.hz} flashes/s and listens for ${other.hz} flashes/s. The other phone must be the ${other.name}. Passphrase: same on both.", 13f, color = Color.LTGRAY)
        val heard = col.text("Starting camera...", 20f, bold = true, color = Color.rgb(255, 200, 0))
        val sending = col.text("", 16f, color = Color.rgb(255, 200, 0))
        val got = col.text("", 22f, bold = true, color = Color.rgb(120, 200, 255))
        val tv = TextureView(this)
        col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        val msgField = EditText(this).apply { setText(autoText ?: "HI"); setTextColor(Color.WHITE) }
        col.addView(msgField)
        setContentView(sv)

        val log = StringBuilder("t_s,best_spot,best_big_zone,whole_picture,found\n")
        val startedAt = SystemClock.elapsedRealtime()
        fun now() = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        var alive = true
        val listener = Listener(other.hz)
        var locked = false
        val lockIdx = IntArray(3)
        val decoders = List(3) { DuplexDecoder(other.hz, pass) }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var decoding = false
        var lastGot = ""
        var frames = 0

        // Own pictures: flashlight state the camera reports, and whole-picture brightness (last ~20 s).
        val own = ArrayDeque<String>()
        val c = CamSession(this, cams[0].first, tv) { f ->
            synchronized(own) { own.addLast(String.format(Locale.US, "%.4f,%d,%d,%d", f.tsNs / 1e9, f.flashState, f.mean, f.peak)); if (own.size > 600) own.removeFirst() }
        }
        c.manual = false; c.fps = 30
        c.torchPattern = DuplexFormat.carrier(role)
        c.onError = { e -> runOnUiThread { heard.text = "Camera problem: $e" } }
        val sender = DuplexSender(c, role)
        c.zoneSink = { ts, fine ->
            listener.add(ts, fine)
            frames++
            if (locked) {
                for (ch in 0 until 3) decoders[ch].add(ts / 1e9, Listener.channel(fine, ch, lockIdx[ch]))
                if (frames % 60 == 0 && !decoding) {
                    decoding = true
                    val snaps = decoders.map { it.copy() }
                    worker.execute {
                        val res = snaps.mapNotNull { it.tryDecode() }
                        val d = res.filter { it.forUs }.maxByOrNull { it.atSec } ?: res.firstOrNull()
                        decoding = false
                        if (d != null) runOnUiThread {
                            if (!alive) return@runOnUiThread
                            val key = d.text + d.forUs
                            got.text = if (d.forUs) "RECEIVED: \"${d.text}\"" else "Someone is signalling, but not for us"
                            if (key != lastGot) {
                                lastGot = key
                                log.append(String.format(Locale.US, "RECEIVED at %.1f s: \"%s\" forUs=%s sureness=%.2f\n", now(), d.text, d.forUs, d.confidence))
                                tone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 400)
                            }
                        }
                    }
                }
            }
            if (frames % 15 == 0) listener.analyze()?.let { r ->
                val found = r.best >= Listener.FOUND_SPOT || r.coarse >= Listener.FOUND_ZONE || r.whole >= Listener.FOUND_WHOLE
                if (found) {
                    val spot = r.bestY * CamSession.ZX + r.bestX
                    if (!locked || spot != lockIdx[0]) { lockIdx[0] = spot; decoders[0].reset() }
                    if (!locked || r.coarseIdx != lockIdx[1]) { lockIdx[1] = r.coarseIdx; decoders[1].reset() }
                    if (!locked) { locked = true; log.append(String.format(Locale.US, "HEARD the %s at %.1f s\n", other.name, now())) }
                }
                log.append(String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f,%d\n", now(), r.best, r.coarse, r.whole, if (found) 1 else 0))
                runOnUiThread {
                    if (!alive) return@runOnUiThread
                    heard.text = if (found) "Hearing the ${other.name} (strength ${maxOf(r.best, r.coarse, r.whole).toInt()})"
                                 else if (locked) "Lost the ${other.name}'s rhythm (still reading)" else "Listening for the ${other.name}..."
                    heard.setTextColor(if (found) Color.rgb(80, 255, 120) else Color.rgb(255, 200, 0))
                    sending.text = sender.progress.ifEmpty { "Blinking my rhythm (${role.hz} flashes/s)" }
                }
            }
        }
        fun send(text: String) {
            if (sender.running) return
            log.append(String.format(Locale.US, "SENDING at %.1f s: \"%s\"\n", now(), text))
            Thread { sender.send(text, pass); log.append(String.format(Locale.US, "SENT done at %.1f s\n", now())) }.start()
        }
        col.button("Send message") { send(msgField.text.toString()) }
        fun save(): String {
            val head = Results.header("Two-way, ${role.name}") + "Blinking ${role.hz}/s, listening for ${other.hz}/s\n\n"
            return Results.save(this, "twoway-${role.name.lowercase()}", head + log + "\nRAW CHANNEL (big zone)\n" + decoders[1].dump() +
                "\nOWN PICTURES (time, flash state 3=on 2=ready, whole mean, peak)\n" + synchronized(own) { own.joinToString("\n") })
        }
        col.button("Save results (and keep going)") { heard.text = "Saved: ${save()}" }
        stopCurrent = {
            alive = false
            sender.running = false
            save()
            c.close()
            tone?.release()
            worker.shutdown()
        }
        if (tv.isAvailable) c.open {} else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { c.open {} }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (autoText != null) tv.postDelayed({ if (alive) send(autoText) }, autoAfter * 1000L)
    }


    // -------------------------------------------------------------- calibration

    private fun showCalibSend(auto: Boolean) {
        onMenu = false
        val sender = CalibSender(this)
        stopCurrent = { sender.running = false }
        var status: TextView? = null
        fun go() = Thread {
            for (i in 3 downTo 1) { runOnUiThread { status?.text = "Starting in $i..." }; Thread.sleep(1000) }
            val r = sender.run(savedPass()) { st -> runOnUiThread { status?.text = st } }
            val path = Results.save(this, "calib-send", r)
            runOnUiThread { status?.text = "Done. Saved: $path" }
        }.start()
        scrollPage {
            text("Calibrate: send brightness steps", 22f, bold = true)
            text("Beacon, then 5 brightness steps (off, 1/4, 1/2, 3/4, full) made by keeping the flashlight on for part of each picture. About ${CalibFormat.totalSec.toInt()} seconds. Passphrase: ${savedPass()}", 14f)
            button("Start") { go() }
            button("Stop") { sender.running = false }
            status = text("", 16f, color = Color.rgb(255, 200, 0))
        }
        if (auto) go()
    }

    private fun showCalibRecv(iso: Int, secs: Int) {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        val rx = CalibReceiver(savedPass())
        val col = column()
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(col)
        col.text("Calibrate: measure", 22f, bold = true)
        val status = col.text("Recording the room first...", 18f, bold = true, color = Color.rgb(255, 200, 0))
        val tv = TextureView(this)
        col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        val out = col.text("", 13f)
        setContentView(sv)
        val c = CamSession(this, cams[0].first, tv) { }
        // One picture = one on/off period of the sender (1/15 s), exposed the whole time.
        c.manual = true; c.fps = 15; c.expNs = 66_000_000L; c.iso = iso
        c.onError = { e -> runOnUiThread { status.text = "Camera problem: $e" } }
        c.zoneSink = { ts, fine -> rx.add(ts, fine) }
        var alive = true
        var done = false
        fun finish() {
            if (done) return
            done = true
            status.text = "Working it out..."
            Thread {
                val r = rx.analyze()
                val path = Results.save(this, "calib-recv", Results.header("Calibration receiver") + "Picture: 1/15 s, fully exposed, ISO $iso\n\n" + r)
                runOnUiThread { if (alive) { status.text = r.lineSequence().first(); out.text = r + "\nSaved: $path" } }
            }.start()
        }
        col.button("Work it out now") { finish() }
        stopCurrent = { alive = false; c.close() }
        if (tv.isAvailable) c.open {} else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { c.open {} }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        tv.postDelayed({ if (alive) { status.text = "Recording... (start the sender now if not yet)" } }, 3000)
        tv.postDelayed({ if (alive) { c.close(); finish() } }, secs * 1000L)
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

    // ------------------------------------------------------- brightness messages

    /** A camera screen that never needs scrolling: camera view on top, text in the middle, buttons fixed at the bottom. */
    private class CameraPage(val root: LinearLayout, val camBox: android.widget.FrameLayout, val info: LinearLayout, val bar: LinearLayout)

    private fun cameraPage(title: String): CameraPage {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(TextView(this).apply { text = title; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val camBox = android.widget.FrameLayout(this)
        root.addView(camBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f))
        val info = column()
        val sv = ScrollView(this).apply { addView(info) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        return CameraPage(root, camBox, info, bar)
    }

    private fun LinearLayout.barButton(label: String, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(context).apply { text = label; textSize = 13f; isAllCaps = false; setOnClickListener { onClick() } }
            .also { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }

    private fun showBrightSend(plan: BrightRunPlan?) {
        onMenu = false
        val sender = BrightSender(this)
        stopCurrent = { sender.running = false }
        var status: TextView? = null
        var log: TextView? = null
        val modeNames = BrightFormat.MODES.map { "${it.name}  (about ${it.rawBitsPerSec.toInt()} bits/s raw)" }
        lateinit var modeChoice: () -> Int
        lateinit var msgChoice: () -> Int
        var custom: EditText? = null
        fun run(p: BrightRunPlan) {
            Thread {
                val file = LiveFile(this, "bright-send")
                file.append(Results.header("Brightness messages, sender") + "Plan: ${p.describe()}\nPassphrase rhythm: ${MessageFormat.beaconHz(savedPass())} /s (passphrase itself not saved)\n\n" + BrightSender.HEAD)
                for (i in 3 downTo 1) { runOnUiThread { status?.text = "Starting in $i..." }; Thread.sleep(1000) }
                runOnUiThread { status?.text = "Waking the flashlight..." }
                file.append(String.format(Locale.US, "# warm-up blink: first switch took %.0f us\n", sender.warmUp()))
                var k = 0
                val total = p.sends.size
                for ((mode, text) in p.sends) {
                    if (stopCurrent == null) break
                    k++
                    val r = sender.send(text, mode, savedPass(), p.beaconSec) { st -> runOnUiThread { status?.text = "Send $k of $total: $st" } }
                    file.append(sender.line(k, r) + "# nonce ${r.nonce}\n")
                    runOnUiThread { log?.text = "Last: send $k of $total, ${r.mode.name}, shortest pulse ${r.pminMs} ms, ${"%.1f".format(r.seconds)} s, ${r.failure ?: "ok"}\nSaved in ${file.path}" }
                    if (r.failure == "stopped") break
                    Thread.sleep((p.gapSec * 1000).toLong())
                }
                runOnUiThread { status?.text = "Done. Saved: ${file.path}" }
            }.start()
        }
        scrollPage {
            text("Brightness messages: send", 24f, bold = true)
            text("Put this phone on the table, flashlight up, as for calibration. The other phone runs RECEIVE. Never aim the light at anyone's eyes.", 14f)
            text("Passphrase: ${savedPass()} (same on both phones)", 14f, color = Color.LTGRAY)
            modeChoice = choice("Mode", modeNames, 2)
            msgChoice = choice("Message", BrightFormat.TEST_MESSAGES + "My own text (below)")
            custom = EditText(context).apply { hint = "My own text (letters, accents ok, up to 80 bytes)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
            addView(custom)
            button("Send once (3 second countdown)") {
                val m = BrightFormat.MODES[modeChoice()]
                val t = if (msgChoice() < BrightFormat.TEST_MESSAGES.size) BrightFormat.TEST_MESSAGES[msgChoice()] else custom?.text.toString().ifBlank { "HOLA" }
                run(BrightRunPlan(listOf(m to t), 3.0, 0.0))
            }
            button("Run the whole test list for this mode (4 messages x 10)") {
                run(BrightRunPlan.build(listOf(modeChoice()), listOf(0, 1, 2, 3), 10, 3.0, 0.0))
            }
            button("Stop") { sender.running = false; stopCurrent = null }
            status = text("", 16f, color = Color.rgb(255, 200, 0))
            log = text("", 13f, color = Color.LTGRAY)
        }
        if (plan != null) run(plan)
    }

    private fun showBrightReply(nonce: Int, ok: Boolean) {
        onMenu = false
        val sender = BrightSender(this)
        stopCurrent = { sender.running = false }
        val file = LiveFile(this, "bright-reply")
        var status: TextView? = null
        scrollPage {
            text("Short reply", 24f, bold = true)
            status = text("Replying \"${if (ok) "got it" else "saw it but could not read it"}\" to message $nonce...", 16f, color = Color.rgb(255, 200, 0))
        }
        Thread {
            val (sec, lateUs, failure) = sender.reply(savedPass(), nonce, ok)
            file.append(Results.header("Short reply, sender") + String.format(Locale.US, "reply,%s,nonce %d,%.1f s,late %.0f us,%s\n",
                if (ok) "GOT IT" else "COULD NOT READ", nonce, sec, lateUs, failure ?: "ok"))
            runOnUiThread { status?.text = "Done (${failure ?: "ok"}). Saved: ${file.path}" }
        }.start()
    }

    private fun showBrightRecv(room0: String, paces0: String, isoFixed: Int, traceSec: Int = 0, expect: Int = -1) {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        val pg = cameraPage("Brightness messages: receive")
        val tv = TextureView(this)
        val overlay = BlockView(this)
        pg.camBox.addView(tv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pg.camBox.addView(overlay, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val where = pg.info.text("Point the camera so the other phone's light (or its glow on the ceiling) is in the picture. Yellow squares = where light is changing.", 13f, color = Color.LTGRAY)
        val status = pg.info.text("Starting camera...", 18f, bold = true, color = Color.rgb(255, 200, 0))
        val message = pg.info.text("", 20f, bold = true, color = Color.rgb(120, 200, 255))
        val conditions = pg.info.text("", 13f, color = Color.LTGRAY)
        val tallyView = pg.info.text("", 13f)
        val exp = 0.0325
        val rx = BrightListener(this, savedPass(), exp)
        rx.room = room0; rx.paces = paces0; rx.traceSec = traceSec.toDouble(); rx.expect = expect
        val file = LiveFile(this, "bright-recv")
        val c = CamSession(this, cams[0].first, tv) { }
        c.manual = true; c.fps = 30; c.expNs = (exp * 1e9).toLong(); c.iso = if (isoFixed > 0) isoFixed else 400
        rx.iso = c.iso
        c.onError = { e -> runOnUiThread { status.text = "Camera problem: $e" } }
        c.zoneSink = { ts, fine -> rx.add(ts, fine) }
        var alive = true
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val tone = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }
        fun describe() {
            conditions.text = "Room: ${rx.room.uppercase()}   Distance: ${rx.paces}   Light sensor: ${if (rx.lux.isNaN()) "-" else "%.0f lux".format(rx.lux)}\n" +
                "Camera: ${cams[0].second}, exposure ${"%.1f".format(exp * 1000)} ms locked, ISO ${rx.iso}, picture every ${"%.1f".format(rx.frameGapMs())} ms, background ${"%.0f".format(rx.background())}\nSaved live in ${file.path}"
        }
        file.append(Results.header("Brightness messages, receiver") + "Passphrase rhythm: ${MessageFormat.beaconHz(savedPass())} /s (passphrase itself not saved)\n" +
            "Camera: ${cams[0].second}, exposure ${exp * 1000} ms fixed (locked), 30 pictures per second asked, ISO ${if (isoFixed > 0) "$isoFixed fixed" else "automatic once, then fixed"}\n" +
            "Blocks: ${BrightListener.NB} row bands x ${BrightListener.NC} column blocks + whole picture\n\n" + BrightListener.HEAD)
        pg.bar.barButton("Dim / Lit") { rx.room = if (rx.room == "dim") "lit" else "dim"; describe() }
        pg.bar.barButton("Paces -") { rx.paces = (rx.paces.toIntOrNull()?.minus(1) ?: 0).coerceAtLeast(0).let { if (it == 0) "hand-width" else "$it" }; describe() }
        pg.bar.barButton("Paces +") { rx.paces = ((rx.paces.toIntOrNull() ?: 0) + 1).toString(); describe() }
        pg.bar.barButton("Stop") { leave(); showMenu() }
        stopCurrent = {
            alive = false
            c.close(); rx.stopLux(); worker.shutdown(); tone?.release()
            file.append("\nSTOPPED. Tally:\n" + rx.tallyText() + "\n")
        }
        rx.startLux()
        // Automatic sensitivity once, after 2 s: aim for a background around 40 of 255.
        // Then 1.5 s more for the camera to settle; only pictures after that count ("# ready" line).
        tv.postDelayed({
            if (!alive) return@postDelayed
            val bg = rx.background()
            if (isoFixed <= 0 && bg > 0.5) { c.iso = (c.iso * 40.0 / bg).toInt().coerceIn(100, 1600); rx.iso = c.iso; c.apply()
                file.append("# automatic ISO: background was ${"%.1f".format(bg)} at ISO 400, now ISO ${c.iso}\n") }
            tv.postDelayed({
                if (!alive) return@postDelayed
                val now = rx.newest()
                if (now == 0.0) { file.append("# ready: NO PICTURES from the camera\n"); return@postDelayed }
                rx.readyAt = now
                file.append("# ready: listening, background ${"%.1f".format(rx.background())}\n")
            }, 1500)
        }, 2000)
        val ticker = object : Runnable {
            override fun run() {
                if (!alive) return
                describe()
                overlay.show(rx.liveliness(), c.sensorOrientation)
                tv.postDelayed(this, 500)
            }
        }
        val scanner = object : Runnable {
            override fun run() {
                if (!alive) return
                worker.execute {
                    val lines = try { rx.scan() } catch (e: Exception) { listOf("# scan problem: ${e.message}\n") }
                    rx.takeTrace()?.let { t -> LiveFile(this@MainActivity, "bright-trace").also { it.append(t); file.append("# brightness recording saved: ${it.path}\n") } }
                    for (l in lines) file.append(l)
                    if (lines.isNotEmpty()) runOnUiThread {
                        if (!alive) return@runOnUiThread
                        message.text = rx.lastLine
                        tallyView.text = rx.tallyText()
                        tone?.startTone(if (rx.lastLine.startsWith("\"")) android.media.ToneGenerator.TONE_PROP_ACK else android.media.ToneGenerator.TONE_PROP_NACK, 300)
                    }
                }
                tv.postDelayed(this, 2000)
            }
        }
        if (tv.isAvailable) c.open {} else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { c.open {} }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        status.text = "Listening for brightness messages..."
        tv.post(ticker)
        tv.postDelayed(scanner, 3000)
    }

    private fun showTalk(opts: Talk.Opts) {
        onMenu = false
        val cams = CamSession.backCameras(this)
        if (cams.isEmpty()) { scrollPage { text("No back camera found.") }; return }
        val pg = cameraPage("Talking in turns")
        val tv = TextureView(this)
        pg.camBox.addView(tv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val status = pg.info.text("Starting...", 18f, bold = true, color = Color.rgb(255, 200, 0))
        val heard = pg.info.text("", 18f, bold = true, color = Color.rgb(120, 200, 255))
        val file = LiveFile(this, "talk")
        pg.info.text("${if (opts.outbox.isEmpty()) "Listening and replying" else "Sending ${opts.outbox.size} messages, then listening"}. Saved live in ${file.path}", 13f, color = Color.LTGRAY)
        val talk = Talk(this, tv, cams[0].first, savedPass(), opts, file) { st ->
            runOnUiThread { if (st.startsWith("Read:")) heard.text = st else status.text = st }
        }
        pg.bar.barButton("Stop") { leave(); showMenu() }
        stopCurrent = { talk.stop(); file.append("\nSTOPPED.\n") }
        talk.start()
    }

    /** Draws the 12 x 8 blocks over the camera view; brighter yellow = light changing there. */
    class BlockView(ctx: android.content.Context) : View(ctx) {
        private var live: FloatArray? = null
        private var orient = 90
        private val cell = Paint()
        private val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(60, 255, 255, 255) }
        fun show(v: FloatArray, orientation: Int) { live = v; orient = orientation; invalidate() }
        private fun toScreen(x: Float, y: Float): Pair<Float, Float> = when (orient) {
            90 -> (1 - y) to x
            270 -> y to (1 - x)
            180 -> (1 - x) to (1 - y)
            else -> x to y
        }
        override fun onDraw(c: Canvas) {
            val v = live ?: return
            val mx = (v.maxOrNull() ?: 0f).coerceAtLeast(1e-3f)
            val nb = BrightListener.NB; val nc = BrightListener.NC
            for (b in 0 until nb) for (k in 0 until nc) {
                val (x0, y0) = toScreen(k.toFloat() / nc, b.toFloat() / nb)
                val (x1, y1) = toScreen((k + 1f) / nc, (b + 1f) / nb)
                val l = minOf(x0, x1) * width; val r = maxOf(x0, x1) * width
                val t = minOf(y0, y1) * height; val btm = maxOf(y0, y1) * height
                val a = (v[b * nc + k] / mx * 150).toInt()
                if (a > 10) { cell.color = Color.argb(a, 255, 220, 0); c.drawRect(l, t, r, btm, cell) }
                c.drawRect(l, t, r, btm, grid)
            }
        }
    }
}
