package com.metavitae.lightlab

import android.app.Activity
import android.os.SystemClock
import android.view.TextureView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Taking turns inside the app, no computer in the loop (Oct 14).
 *
 * Each phone listens by default. When it reads a message it closes the camera, waits until
 * the other phone is surely listening again, blinks the short reply ("got it", or "saw it but
 * could not read it" when the header was read but the text was not), and listens again.
 * A phone with texts to send: closes the camera, sends, opens it again and waits for the reply.
 * No "got it": it sends again (auto-resend), slower if allowed (auto-speed), up to `tries` times.
 *
 * Short openings: after one full send was answered "got it", the two phones are linked and the
 * next sends use a 1 s sync and a 24-bit header (about 4.7 s of fixed cost at 15 per second
 * instead of about 10.5 s). A short send that gets no reply at all drops the link (next try: full).
 *
 * Turns (both phones have texts): a phone sends when it starts first, or right after it answered
 * "got it" to the other phone's message (then it's its turn, and the other phone is still listening).
 * A phone keeps its turn until its message got through. Only the first phone may break a long silence
 * (nothing heard for `idle` seconds), so the two never both decide to send at the same moment
 * (Oct 14, first try: both waited the same 45 s and kept sending over each other).
 *
 * Speeds for auto-speed: on/off only, 30, 15 and 10 per second (modes 0, 5, 6). It steps down
 * after each failed try and up after 2 messages in a row got through on the first try.
 */
class Talk(
    private val act: Activity, private val tv: TextureView?, private val camId: String, private val pass: String,
    private val o: Opts, private val file: LiveFile, private val show: (String) -> Unit,
) {
    class Opts(
        val outbox: List<String>, val startMode: Int, val auto: Boolean, val short: Boolean,
        /** The reply starts this long after the message ended (the sender needs that to listen again). */
        val replyAfter: Double,
        /** How long the sender waits for a reply after its send. */
        val waitReply: Double,
        val tries: Int, val gap: Double, val startDelay: Double, val isoFixed: Int,
        val expect: List<String>, val room: String, val paces: String,
        /** Take turns with the other phone (see above); first = this phone starts. */
        val turns: Boolean = false, val first: Boolean = true, val idle: Double = 45.0,
    )

    /** What the Field screen shows (all optional). Called on the talk or scanner thread. */
    interface Events {
        fun sending(k: Int, text: String, attempt: Int, mode: BrightFormat.Mode, short: Boolean) {}
        fun sent(k: Int, text: String, ok: Boolean, tries: Int, seconds: Double) {}
        fun heard(r: BrightDecoder.Result, dup: Boolean) {}
        /** Something the person must act on (camera stuck, ...). */
        fun problem(text: String) {}
        /** How well the other phone's Find pattern is seen now (0..1), about every 0.7 s while listening. */
        fun aim(score: Double) {}
        /** Message k was stopped by the person (to edit it): nothing more is sent for it. */
        fun stopped(k: Int) {}
        /** How well the chat's "us" beacon matches what the camera sees (0-1), about every 1.4 s. */
        fun beacon(score: Double) {}
    }
    @Volatile var events: Events? = null
    /** Also look for the other phone's Find pattern while listening (Field screen). */
    @Volatile var watchFind = false
    /** Blink the Find pattern for this many seconds as soon as possible (0 = no). */
    @Volatile var findSec = 0
    /** Oct 29 (founder: "us" beacon): while idle, blink this chat's beacon twice (8.4 s) every BEACON_EVERY s. */
    @Volatile var beaconOn = false
    private val BEACON_EVERY = 30.0
    private var lastBeacon = 0.0
    /** LightComm is on the screen (set by the app). Oct 24: off the screen, Android refuses the camera ("CAMERA_DISABLED ... from background"),
     *  and the reopen kept trying every 5 s for 25 min. Now it waits until the app is back on the screen. */
    @Volatile var onScreen = true

    /** Messages to send: the start list, plus any added later (Field screen). */
    private val box: MutableList<String> = java.util.Collections.synchronizedList(ArrayList(o.outbox))
    /** Adds a message; returns its number k (from 1), used by sending/sent/stopped and stopMessage. */
    fun enqueue(text: String): Int = synchronized(box) { box.add(text); box.size }
    /** Messages the person stopped to edit them (numbers k). */
    private val stoppedK: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var sendingK = 0
    /**
     * Stop message k (Oct 14, founder: stop it so it can be edited). Still waiting: it is skipped. Being sent:
     * the light goes off at once; the other phone refuses a cut-off message (check bytes), never a wrong text.
     * Fully sent and waiting for "got it": a got-it that still arrives counts (it was delivered); otherwise no more tries.
     */
    fun stopMessage(k: Int) { stoppedK.add(k); if (sendingK == k) sender.running = false }
    fun waiting(): Int = box.size - next
    /** How the camera sensor is turned (for drawing the zone map over the preview). */
    val sensorOrientation: Int get() = cam?.sensorOrientation ?: 90

    private var exp = 0.0325
    val rx = BrightListener(act, pass, exp)
    private val sender = BrightSender(act)
    @Volatile var alive = true
    private var cam: CamSession? = null
    private var iso = if (o.isoFixed > 0) o.isoFixed else 400
    private var isoSet = o.isoFixed > 0
    private val scanner = Executors.newSingleThreadScheduledExecutor()

    /** Out-link: our last full message that got "got it" (lastSeq = last follow-up answered). */
    private var outLink: BrightFormat.Link? = null
    private val ladder = listOf(0, 5, 6)
    private var li = ladder.indexOf(o.startMode)
    private var streak = 0
    private val seen = HashSet<Int>()
    private var next = 0
    private var myTurn = o.first
    /** Answered "got it" and it's our turn: send right away, the other phone is still listening. */
    private var sendNow = false

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
    /** Seconds the other side gets to answer first after our message got through. */
    private val REPLY_PRIORITY = 6.0
    /** Oct 18: both phones started sending at the same moment and kept retrying in step. A random extra wait before
     *  each send (0-1.5 s, new every time), and giving way when the other phone is heard (at most 3 times a message). */
    private var jitter = Math.random() * 1.5
    private var giveWays = 0
    private var busySince = 0.0
    private var busyIgnored = false
    /** deliver() result "round" when it gave way to the other phone (-1 = stopped by the person). */
    private val GAVE_WAY = -2.0
    private fun log(s: String) = file.append(s)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    // ------------------------------------------------------------ camera

    /** Open the camera and wait until pictures come and have settled; then the listener counts them. */
    private fun openCam(): Double {
        val t0 = now()
        val before = rx.newest()
        val c = CamSession(act, camId, tv) { }
        c.manual = true; c.fps = 30; c.expNs = (exp * 1e9).toLong(); c.iso = iso
        watch(c)
        c.zoneSink = { ts, fine -> rx.add(ts, fine) }
        cam = c
        c.open {}
        while (alive && rx.newest() == before && now() - t0 < 5) Thread.sleep(20)
        if (!isoSet) {
            // First time only: automatic setting (twice: glare that is white the first time is measured again).
            Thread.sleep(2000)
            repeat(2) {
                val bg = rx.background(); val top = rx.brightTop()
                val (i2, e2) = BrightListener.settle(bg, top, iso, exp)
                log(String.format(java.util.Locale.US, "# automatic camera: background %.1f, brightest spots %.0f at ISO %d, %.1f ms -> ISO %d, %.1f ms\n", bg, top, iso, exp * 1000, i2, e2 * 1000))
                iso = i2; exp = e2; c.iso = iso; c.expNs = (exp * 1e9).toLong(); rx.expSec = exp; c.apply()
                Thread.sleep(900)
            }
            isoSet = true
            Thread.sleep(600)
        } else Thread.sleep(500)
        // Oct 14: the POCO's camera got stuck giving pure black pictures (no noise at all, even at full sensitivity)
        // until the phone's own Camera app was opened once. Oct 15 (night test): it happened 4 times and a plain
        // fresh start never fixed it. So: start like the Camera app (automatic brightness first), then wake the
        // phone's camera system by opening another camera for a moment. Each step is logged, to learn what works.
        if (blackCamera() && alive) {
            log("# camera gives pure black pictures: starting it again with automatic brightness first\n")
            if (!restartAuto()) {
                val other = otherCamera()
                if (other != null && alive) {
                    log("# camera still black: opening camera $other for a moment to wake the camera system\n")
                    closeCam()
                    val o2 = CamSession(act, other, null) { }
                    o2.manual = false; o2.fps = 30
                    o2.onError = { e -> log("# camera $other problem: $e\n") }
                    o2.open {}; Thread.sleep(1500); o2.closeAndWait(2000); Thread.sleep(400)
                    if (restartAuto()) log("# camera black fixed by: opening camera $other for a moment\n")
                }
            } else log("# camera black fixed by: start with automatic brightness\n")
            if (blackCamera()) { log("# camera still black\n"); events?.problem("Camera stuck: open the phone's Camera app once, then come back.") }
            lastBlackFix = now()
        }
        rx.iso = iso
        rx.readyAt = rx.newest()
        return now() - t0
    }

    /** Oct 15 (23:29): the POCO's camera stopped with "camera error 3" and the app only wrote it down: deaf until restarted. */
    @Volatile private var camLost = ""
    private var lostTries = 0
    private var lastReopen = 0.0
    private fun watch(c: CamSession) {
        c.onError = { e -> log("# camera problem: $e\n"); if (cam === c) camLost = e; if ("CAMERA_DISABLED" in e) blockedOffScreen = true }
    }
    @Volatile private var blockedOffScreen = false
    private var toldOffScreen = false

    /** The camera in use stopped: open it again (every 5 s at most; the person is told after 3 tries). True = tried. */
    private fun reopenIfLost(): Boolean {
        if (camLost.isEmpty()) return false
        if (blockedOffScreen && !onScreen) {
            if (!toldOffScreen) { log("# camera refused while LightComm is off the screen: waiting until it is back\n"); toldOffScreen = true }
            Thread.sleep(300); return true
        }
        if (toldOffScreen) { log("# LightComm back on the screen: opening the camera again\n"); toldOffScreen = false; lastReopen = 0.0 }
        blockedOffScreen = false
        if (now() - lastReopen < 5) return false
        lostTries++
        log("# camera lost ($camLost): opening it again, try $lostTries\n")
        camLost = ""; lastReopen = now()
        val before = rx.newest()
        closeCam(); openCam()
        if (camLost.isEmpty() && rx.newest() > before) { log("# camera back after try $lostTries\n"); lostTries = 0 }
        else if (lostTries == 3) events?.problem("Camera lost: keep LightComm open on the screen. Still trying.")
        return true
    }

    /** Close the camera, open it with automatic brightness for a moment (as the Camera app does), then fixed settings again. True = not black now. */
    private fun restartAuto(): Boolean {
        closeCam()
        val c2 = CamSession(act, camId, tv) { }
        c2.manual = false; c2.fps = 30
        watch(c2)
        c2.zoneSink = { ts, fine -> rx.add(ts, fine) }
        cam = c2; c2.open {}
        Thread.sleep(1500)
        c2.manual = true; c2.expNs = (exp * 1e9).toLong(); c2.iso = iso; c2.apply()
        Thread.sleep(1200)
        return !blackCamera()
    }

    /** Any other camera of this phone (front or back), to wake the camera system; null if there is none. */
    private fun otherCamera(): String? = try {
        act.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.firstOrNull { it != camId }
    } catch (_: Exception) { null }

    /** When the black-camera fix last ran (it may run again while listening, at most once a minute). */
    private var lastBlackFix = 0.0
    private var blackSince = 0.0

    /** Oct 24 (01:42): with the battery low, the POCO's picture got dim but not pure black (brightest spots ~1, were 9-56):
     *  it missed every got-it until the black fix happened to run. The brightest spots while waiting at the last got-it are
     *  remembered; under a quarter of that (and under 3) for 3 s = start the camera again (at most once a minute). */
    private var goodTop = 0.0
    private var fadedSince = 0.0
    private fun checkFaded(): Boolean {
        val top = rx.brightTop()
        if (cam == null || rx.readyAt <= 0 || rx.hearing || goodTop < 5 || top >= 3 || top >= goodTop * 0.25) { fadedSince = 0.0; return false }
        if (fadedSince == 0.0) fadedSince = now()
        if (now() - fadedSince < 3 || now() - lastBlackFix < 60) return false
        log(String.format(Locale.US, "# camera picture faded: brightest spots %.1f (were %.1f at the last got-it), background %.1f: starting it again\n", top, goodTop, rx.background()))
        val ok = restartAuto()
        rx.readyAt = rx.newest()
        log(String.format(Locale.US, "# after the restart: brightest spots %.1f, background %.1f%s\n", rx.brightTop(), rx.background(), if (ok) "" else " (pure black)"))
        lastBlackFix = now(); fadedSince = 0.0
        return true
    }

    /** Oct 27 (founder): the camera was set once at the start and never again. Oct 26 night: room lights at 04:05-05:20, then
     *  daylight from 05:40 (8T background 10 -> 155), and at 06:10 signal 2-6 lost most got-its. Now, while idle, the setting
     *  follows the room: when the one it would choose now is under half or over double the one in use for 3 s, it is applied
     *  (at most every 10 s; never while hearing, while the other phone blinks, or while waiting for a reply). */
    private var driftSince = 0.0
    private var lastAdjust = 0.0
    private fun followRoom() {
        val c = cam
        if (c == null || rx.readyAt <= 0 || rx.hearing || rx.busy() || rx.newest() - rx.readyAt < 2) { driftSince = 0.0; return }
        val bg = rx.background(); val top = rx.brightTop()
        val (i2, e2) = BrightListener.settle(bg, top, iso, exp)
        val ratio = i2 * e2 / (iso * exp)
        if (ratio in 0.5..2.0) { driftSince = 0.0; return }
        if (driftSince == 0.0) driftSince = now()
        if (now() - driftSince < 3 || now() - lastAdjust < 10) return
        log(String.format(Locale.US, "# room light changed: background %.1f, brightest spots %.0f at ISO %d, %.1f ms -> ISO %d, %.1f ms\n", bg, top, iso, exp * 1000, i2, e2 * 1000))
        iso = i2; exp = e2; c.iso = iso; c.expNs = (exp * 1e9).toLong(); rx.expSec = exp; rx.iso = iso; c.apply()
        rx.settingChanged(ratio)
        goodTop = 0.0                 // the faded check compares with the next got-it, seen at the new setting
        Thread.sleep(500)
        rx.readyAt = rx.newest()      // pictures from before the change aren't read together with the ones after
        lastAdjust = now(); driftSince = 0.0
    }

    /** Pure black: not even picture noise (a dark room still gives some). */
    private fun blackCamera() = rx.newest() > 0 && rx.background() < 0.05 && rx.brightTop() < 0.5

    private fun closeCam() {
        rx.readyAt = 0.0
        cam?.closeAndWait(2000)
        cam = null
        Thread.sleep(150)
    }

    // ------------------------------------------------------------ the loop

    fun start() = Thread { try { loop() } catch (e: Exception) { log("# talk stopped by a problem: $e\n"); show("Problem: $e") } }.start()

    fun stop() {
        alive = false; sender.running = false
        scanner.shutdownNow()
        cam?.close(); rx.stopLux()
    }

    private fun loop() {
        rx.room = o.room; rx.paces = o.paces; rx.earlySec = 0.5
        // No list (LightComm app, Field screen): the texts are typed by people, nothing to compare them with.
        rx.expectTexts = o.expect
        rx.startLux()
        log(Results.header("Talking in turns") + "Passphrase rhythm: ${MessageFormat.beaconHz(pass)} /s (passphrase itself not saved)\n" +
            "To send: ${o.outbox.size} messages; start speed: ${BrightFormat.MODES[o.startMode].name}; auto-speed ${if (o.auto && li >= 0) "on" else "off"}; " +
            "short openings ${if (o.short) "on" else "off"}; reply ${f1(o.replyAfter)} s after a message ends; wait for reply ${f1(o.waitReply)} s; up to ${o.tries} tries\n" +
            "Short opening: pattern ${BrightFormat.SHORT_SYNC.size} steps, Barker ${if (BrightFormat.shortBarker) "on" else "off"}\n\n")
        while (tv != null && !tv.isAvailable && alive) Thread.sleep(50)
        show("Starting camera...")
        openCam()
        if (!alive) { log("# stopped before listening began\n"); return }
        log("# ready: listening, background ${f1(rx.background())}, brightest spots ${f1(rx.brightTop())}, ISO $iso, picture ${f1(exp * 1000)} ms\n")
        val findWave = BrightFormat.aimWave(15, 20.0)
        val findPeriod = BrightFormat.SHORT_SYNC.size / 15.0
        // The beacon is looked for over 6 s (more than one 4.2 s round), every second scan (each look tries every start).
        val beaconWave = BrightFormat.beaconWave(pass, 6.0)
        var scans = 0
        scanner.scheduleWithFixedDelay({
            if (rx.readyAt > 0) {
                val lines = try { rx.scan() } catch (e: Exception) { listOf("# scan problem: ${e.message}\n") }
                for (l in lines) log(l)
                if (watchFind && !rx.hearing) {
                    val sc = try { rx.aimScore(3.0, findWave, findPeriod).first } catch (_: Exception) { 0.0 }
                    events?.aim(sc)
                }
                if (!rx.hearing && scans++ % 2 == 0) {
                    val sc = try { rx.aimScore(6.0, beaconWave, BrightFormat.BEACON_PERIOD).first } catch (_: Exception) { 0.0 }
                    events?.beacon(sc)
                }
            }
        }, 700, 700, TimeUnit.MILLISECONDS)
        show("Listening...")
        val startAt = now() + o.startDelay
        var quietSince = now()
        val rounds = ArrayList<Double>(); var delivered = 0; var tries = 0
        log("SEND_HEAD msg,try,opening,mode,levels,per_sec,nonce,send_s,listening_after_s,outcome,reply_seen_after_s,round_s,text\n")
        while (alive) {
            val r = rx.heard.poll(200, TimeUnit.MILLISECONDS)
            if (r != null) { answer(r); quietSince = now(); if (!sendNow) continue }
            if (reopenIfLost()) { quietSince = now(); continue }
            if (findSec > 0 && !rx.hearing) {
                val sec = findSec; findSec = 0
                log("# find: blinking the find pattern for $sec s\n")
                closeCam(); sender.aim(15, sec.toDouble()); openCam()
                quietSince = now(); continue
            }
            // The beacon only when nothing else is going on (no message waiting, nobody sending, nothing just heard).
            if (beaconOn && next >= box.size && !rx.hearing && !rx.busy() && now() - quietSince >= 5 && now() - lastBeacon >= BEACON_EVERY) {
                lastBeacon = now()
                log("# beacon: blinking this chat's pattern twice\n")
                closeCam(); sender.play(BrightFormat.beaconWave(pass, 2 * BrightFormat.BEACON_PERIOD - 0.01)); openCam()
                quietSince = now(); continue
            }
            // Oct 15: the POCO's camera also went black in the middle of listening (01:46). Black for 3 s: fix it.
            if (cam != null && rx.readyAt > 0 && !rx.hearing && blackCamera()) {
                if (blackSince == 0.0) blackSince = now()
                if (now() - blackSince >= 3 && now() - lastBlackFix >= 60) {
                    log("# camera went black while listening\n")
                    blackSince = 0.0
                    closeCam(); openCam(); quietSince = now(); continue
                }
            } else blackSince = 0.0
            followRoom()
            // Oct 26: no faded check here. Idle, the light around can simply go off (8T 05:00: background 47 -> 1.4 when the
            // room went dark) and it restarted the camera for nothing. The real fade (Oct 24) showed as missed got-its: checked while waiting.
            if (o.turns && o.first && !myTurn && next < box.size && now() - quietSince >= o.idle) { myTurn = true; log("# nothing heard for ${o.idle.toInt()} s: sending anyway\n") }
            val turnOk = !o.turns || myTurn
            if (next < box.size && (next + 1) in stoppedK) {
                log("# message ${next + 1} stopped by the person before it was sent\n")
                events?.stopped(next + 1); next++; continue
            }
            // Oct 19: listen before talking. The other phone's light blinking (even with no sync seen) = it is sending: let it finish.
            // At most 30 s: something else flickering in that spot (a screen, a person waving) must not block sending for good.
            val busy = next < box.size && !sendNow && rx.busy()
            if (!busy) { busySince = 0.0; busyIgnored = false }
            else if (busySince == 0.0) { log("# other phone blinking: waiting before sending\n"); busySince = now() }
            if (busy && !busyIgnored) {
                if (now() - busySince < 30) { quietSince = now(); continue }
                log("# still blinking after 30 s: sending anyway\n"); busyIgnored = true
            }
            if (next < box.size && turnOk && now() >= startAt && (sendNow || (!rx.hearing && now() - quietSince >= o.gap + jitter))) {
                if (sendNow) { sendNow = false; Thread.sleep(800) }
                val text = box[next]
                val (ok, n, round) = deliver(text, next + 1)
                jitter = Math.random() * 1.5
                if (!ok && round == GAVE_WAY) { giveWays++; quietSince = now(); continue }   // answer the other phone first, then this one again
                giveWays = 0
                if (!ok && round < 0) {
                    log("# message ${next + 1} stopped by the person after $n tries\n")
                    events?.stopped(next + 1); next++; quietSince = now(); continue
                }
                events?.sent(next + 1, text, ok, n, round)
                // After "got it", the other side gets the first turn to answer (Oct 14: both phones kept sending at once).
                if (ok && !o.turns) { quietSince = now() + REPLY_PRIORITY; sendNow = false }
                if (ok) myTurn = false
                tries += n; if (ok) { delivered++; rounds.add(round) }
                next++; quietSince = now()
                if (next == box.size) {
                    log(String.format(Locale.US, "# DONE: %d of %d delivered, %d tries, round (send to got-it seen) mean %.1f s, min %.1f, max %.1f\n",
                        delivered, box.size, tries, if (rounds.isEmpty()) 0.0 else rounds.average(), rounds.minOrNull() ?: 0.0, rounds.maxOrNull() ?: 0.0))
                    show("Done: $delivered of ${box.size} delivered. Still listening.")
                }
            }
        }
    }

    /** We read (at least the header of) a message: reply. */
    private fun answer(r: BrightDecoder.Result) {
        if (r.mode == null) return            // nothing known about it: nobody to answer
        val dup = r.ok && !seen.add(r.nonce)
        if (r.ok) {
            rx.inLink = if (r.seq == 0) BrightFormat.Link(r.nonce, r.phoneTag, r.pminMs, 0)
                        else rx.inLink?.let { it.copy(lastSeq = max(it.lastSeq, r.seq)) }
            if (!dup) show("Read: \"${r.text}\"")
        }
        events?.heard(r, dup)
        val ended = now() - (rx.newest() - r.endT)     // when the message ended, on this clock
        val noticed = now() - ended
        closeCam()
        val wait = ended + o.replyAfter - now()
        if (wait > 0) Thread.sleep((wait * 1000).toLong())
        val startedAfter = now() - ended
        val (sec, lateUs, fail) = sender.reply(pass, r.nonce, r.ok, r.mode.perSec)
        log(String.format(Locale.US, "# replied %s to nonce %d%s%s: noticed %.1f s after it ended, reply started %.1f s after, took %.1f s, late %.0f us, %s\n",
            if (r.ok) "GOT IT" else "COULD NOT READ", r.nonce, if (r.seq > 0) " (short opening #${r.seq})" else "", if (dup) " (again: seen before)" else "",
            noticed, startedAfter, sec, lateUs, fail ?: "ok"))
        // Our turn now: send straight away (the other phone is listening), no need to open the camera first.
        if (o.turns && r.ok && !dup && next < box.size) { myTurn = true; sendNow = true; return }
        openCam()
    }

    /** Oct 16 (04:51): a stopped message's short-opening number went to the next message, same nonce. If the other
     *  phone had read the stopped one, it would take the next as a repeat. The receiver tries lastSeq .. +5, so skipping one is fine. */
    private fun usedUp(useShort: Boolean, seq: Int, blinked: Boolean) {
        if (useShort && blinked && outLink != null) outLink = outLink!!.copy(lastSeq = seq)
    }
    /** Oct 26: giving way keeps the number (the next try resends the same short opening: read = "got it" again as seen before,
     *  not read = read now). Burning one per give-way ran 3+ ahead of the receiver, which then couldn't read any (6 of 10 misses). */
    private var gaveWaySeq = -1
    private fun blinkedBefore(useShort: Boolean, seq: Int, attempt: Int) = attempt > 1 || (useShort && seq == gaveWaySeq)

    /** What the camera showed while waiting for a reply, looked at about 5 times a second. */
    private inner class WaitLook {
        var n = 0; var stalled = 0; var black = 0; var heard = false
        var bgMin = Double.MAX_VALUE; var bgMax = 0.0; var topMin = Double.MAX_VALUE; var topMax = 0.0
        private var last = rx.newest()
        fun look() {
            val t = rx.newest(); val bg = rx.background(); val top = rx.brightTop()
            n++; if (t == last) stalled++; last = t
            if (blackCamera()) black++
            if (rx.hearing || rx.busy()) heard = true
            bgMin = min(bgMin, bg); bgMax = max(bgMax, bg); topMin = min(topMin, top); topMax = max(topMax, top)
        }
        fun line(): String = if (n == 0) "# while waiting: camera not looked at\n" else String.format(Locale.US,
            "# while waiting: %d looks, no new picture %d, pure black %d, background %.1f-%.1f, brightest spots %.1f-%.1f, something heard: %s\n",
            n, stalled, black, bgMin, bgMax, topMin, topMax, if (heard) "yes" else "no")
    }

    /** Send one message until "got it" or out of tries. Returns (delivered, tries used, seconds from first send to got-it seen). */
    private fun deliver(text: String, k: Int): Triple<Boolean, Int, Double> {
        val t0 = now()
        var useShort = o.short && outLink != null
        var seq = if (useShort) outLink!!.lastSeq + 1 else 0
        var fullNonce = -1
        var shortMisses = 0
        for (attempt in 1..o.tries) {
            if (!alive) break
            if (k in stoppedK) { usedUp(useShort, seq, blinkedBefore(useShort, seq, attempt)); return Triple(false, attempt - 1, -1.0) }
            // Oct 19: listen before a resend too. The other phone blinking now = it's talking: read and answer it first.
            if (attempt > 1 && giveWays < 3 && rx.busy()) {
                log("# other phone blinking before try $attempt: giving way\n")
                if (useShort) gaveWaySeq = seq
                return Triple(false, attempt - 1, GAVE_WAY)
            }
            val mode = BrightFormat.MODES[if (o.auto && li >= 0) ladder[li] else o.startMode]
            show("Message $k, try $attempt: sending in ${mode.name}${if (useShort) ", short opening" else ""}...")
            events?.sending(k, text, attempt, mode, useShort)
            closeCam()
            rx.replies.clear(); rx.replyNear = 0.0
            if (k in stoppedK) { usedUp(useShort, seq, blinkedBefore(useShort, seq, attempt)); openCam(); return Triple(false, attempt - 1, -1.0) }   // stopped while the camera closed
            sendingK = k
            // Also asked before every switch: a stop that lands before the sender starts is never overwritten.
            val s = sender.send(text, mode, pass, 0.0, if (useShort) outLink else null, seq, if (useShort) -1 else fullNonce, { k in stoppedK }) { }
            sendingK = 0
            val sendEnd = now()
            if (!useShort) fullNonce = s.nonce
            rx.expectRate = mode.perSec
            rx.expect = s.nonce
            val listenAfter = openCam()
            var got: Boolean? = null; var seenAfter = 0.0
            val w = WaitLook()
            if (s.failure == null) {
                // Oct 27: the got-it is longer now (6.3 s at 15 per second): wait at least for it to end, plus 5 s.
                val until = sendEnd + max(o.waitReply, o.replyAfter + BrightFormat.replyWave(pass, s.nonce, true, mode.perSec).end + 5.0)
                while (alive && now() < until) {
                    w.look()
                    if (reopenIfLost()) continue
                    if (checkFaded()) continue
                    val rep = rx.replies.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (rep.first == s.nonce) { got = rep.second; seenAfter = now() - sendEnd; break }
                }
            }
            // Oct 16 (05:04): the POCO missed 3 got-its in a row, yet the black check found nothing. Write down what it saw.
            if (s.failure == null && got != true) log(w.line() + String.format(Locale.US, "# best reply match seen: %.2f (taken at %.2f, or %.2f with a clear lead)\n",
                rx.replyNear, BrightDecoder.REPLY_SURE, BrightDecoder.REPLY_WEAK))
            // No got-it, and the other phone was blinking meanwhile: it's talking too. Let the main loop read and answer it, then resend.
            if (got == null && !(k in stoppedK) && giveWays < 3 && (w.heard || rx.hearing || rx.heard.isNotEmpty())) {
                rx.expect = -1
                log(String.format(Locale.US, "SEND %d,%d,%s,%d,%d,%d,%d,%.1f,%.1f,NO REPLY - OTHER PHONE TALKING: GIVING WAY,0.0,0.0,\"%s\"\n", k, attempt, if (useShort) "short#$seq" else "full",
                    mode.id, mode.levels, mode.perSec, s.nonce, s.seconds, listenAfter, text.replace("\"", "'")))
                if (useShort) gaveWaySeq = seq
                return Triple(false, attempt, GAVE_WAY)
            }
            rx.expect = -1
            // Stopped by the person: a got-it that still came means it was delivered; otherwise stop here.
            if (k in stoppedK && got != true) {
                log(String.format(Locale.US, "SEND %d,%d,%s,%d,%d,%d,%d,%.1f,%.1f,STOPPED BY THE PERSON,0.0,0.0,\"%s\"\n", k, attempt, if (useShort) "short#$seq" else "full",
                    mode.id, mode.levels, mode.perSec, s.nonce, s.seconds, listenAfter, text.replace("\"", "'")))
                usedUp(useShort, seq, true)
                return Triple(false, attempt, -1.0)
            }
            val outcome = when { s.failure != null -> "SEND FAILED: ${s.failure}"; got == true -> "GOT IT"; got == false -> "COULD NOT READ"; else -> "NO REPLY" }
            log(String.format(Locale.US, "SEND %d,%d,%s,%d,%d,%d,%d,%.1f,%.1f,%s,%.1f,%.1f,\"%s\"\n", k, attempt, if (useShort) "short#$seq" else "full",
                mode.id, mode.levels, mode.perSec, s.nonce, s.seconds, listenAfter, outcome, seenAfter, if (got == true) now() - t0 else 0.0, text.replace("\"", "'")))
            if (got == true) {
                if (w.n > 0) goodTop = w.topMin
                outLink = if (useShort) outLink!!.copy(lastSeq = seq) else BrightFormat.Link(s.nonce, BrightFormat.phoneTag(android.os.Build.MODEL), s.pminMs, 0)
                if (attempt == 1) streak++ else streak = 0
                if (o.auto && li > 0 && streak >= 2) { li--; streak = 0; log("# auto-speed: up to ${BrightFormat.MODES[ladder[li]].name}\n") }
                return Triple(true, attempt, now() - t0)
            }
            streak = 0
            if (got == null && useShort && ++shortMisses >= 2) {
                // No reply twice to a short opening: maybe the link is gone on the other side. Next try: full opening.
                // (Oct 19: after ONE miss the message had nearly always been read and only the reply was lost, so the
                // same short opening again is answered "seen before"; going full at once cost 3-5 s per miss.)
                useShort = false; outLink = null; seq = 0
                log("# link dropped: next try uses the full opening\n")
            } else if (got == null && useShort) {
                log("# no reply: one more try with the same short opening\n")
            } else if (o.auto && li >= 0 && li < ladder.size - 1) { li++; log("# auto-speed: down to ${BrightFormat.MODES[ladder[li]].name}\n") }
            if (rx.hearing) Thread.sleep(500)
            // No reply: maybe the other phone was sending too. Wait a random extra moment so the two drift apart.
            if (got == null && !o.turns) Thread.sleep((1000 + Math.random() * 4000).toLong())
        }
        usedUp(useShort, seq, true)
        show("Message $k NOT delivered after ${o.tries} tries")
        return Triple(false, o.tries, 0.0)
    }
}
