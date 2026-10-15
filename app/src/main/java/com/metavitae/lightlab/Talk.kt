package com.metavitae.lightlab

import android.app.Activity
import android.os.SystemClock
import android.view.TextureView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

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
    }
    @Volatile var events: Events? = null
    /** Also look for the other phone's Find pattern while listening (Field screen). */
    @Volatile var watchFind = false
    /** Blink the Find pattern for this many seconds as soon as possible (0 = no). */
    @Volatile var findSec = 0

    /** Messages to send: the start list, plus any added later (Field screen). */
    private val box: MutableList<String> = java.util.Collections.synchronizedList(ArrayList(o.outbox))
    fun enqueue(text: String) { box.add(text) }
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
    private fun log(s: String) = file.append(s)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    // ------------------------------------------------------------ camera

    /** Open the camera and wait until pictures come and have settled; then the listener counts them. */
    private fun openCam(): Double {
        val t0 = now()
        val before = rx.newest()
        val c = CamSession(act, camId, tv) { }
        c.manual = true; c.fps = 30; c.expNs = (exp * 1e9).toLong(); c.iso = iso
        c.onError = { e -> log("# camera problem: $e\n") }
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
        // until the phone's own Camera app was opened once. Try a fresh start once; then tell the person.
        if (blackCamera() && alive) {
            log("# camera gives pure black pictures: starting it again\n")
            cam?.closeAndWait(2000); Thread.sleep(400)
            val c2 = CamSession(act, camId, tv) { }
            c2.manual = true; c2.fps = 30; c2.expNs = (exp * 1e9).toLong(); c2.iso = iso
            c2.onError = { e -> log("# camera problem: $e\n") }
            c2.zoneSink = { ts, fine -> rx.add(ts, fine) }
            cam = c2; c2.open {}
            Thread.sleep(1200)
            if (blackCamera()) { log("# camera still black\n"); events?.problem("Camera stuck: open the phone's Camera app once, then come back.") }
        }
        rx.iso = iso
        rx.readyAt = rx.newest()
        return now() - t0
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
        if (o.expect.isNotEmpty()) rx.expectTexts = o.expect
        rx.startLux()
        log(Results.header("Talking in turns") + "Passphrase rhythm: ${MessageFormat.beaconHz(pass)} /s (passphrase itself not saved)\n" +
            "To send: ${o.outbox.size} messages; start speed: ${BrightFormat.MODES[o.startMode].name}; auto-speed ${if (o.auto && li >= 0) "on" else "off"}; " +
            "short openings ${if (o.short) "on" else "off"}; reply ${f1(o.replyAfter)} s after a message ends; wait for reply ${f1(o.waitReply)} s; up to ${o.tries} tries\n\n")
        while (tv != null && !tv.isAvailable && alive) Thread.sleep(50)
        show("Starting camera...")
        openCam()
        if (!alive) { log("# stopped before listening began\n"); return }
        log("# ready: listening, background ${f1(rx.background())}, brightest spots ${f1(rx.brightTop())}, ISO $iso, picture ${f1(exp * 1000)} ms\n")
        val findWave = BrightFormat.aimWave(15, 20.0)
        val findPeriod = BrightFormat.SHORT_SYNC.size / 15.0
        scanner.scheduleWithFixedDelay({
            if (rx.readyAt > 0) {
                val lines = try { rx.scan() } catch (e: Exception) { listOf("# scan problem: ${e.message}\n") }
                for (l in lines) log(l)
                if (watchFind && !rx.hearing) {
                    val sc = try { rx.aimScore(3.0, findWave, findPeriod).first } catch (_: Exception) { 0.0 }
                    events?.aim(sc)
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
            if (findSec > 0 && !rx.hearing) {
                val sec = findSec; findSec = 0
                log("# find: blinking the find pattern for $sec s\n")
                closeCam(); sender.aim(15, sec.toDouble()); openCam()
                quietSince = now(); continue
            }
            if (o.turns && o.first && !myTurn && next < box.size && now() - quietSince >= o.idle) { myTurn = true; log("# nothing heard for ${o.idle.toInt()} s: sending anyway\n") }
            val turnOk = !o.turns || myTurn
            if (next < box.size && turnOk && now() >= startAt && (sendNow || (!rx.hearing && now() - quietSince >= o.gap))) {
                if (sendNow) { sendNow = false; Thread.sleep(800) }
                val text = box[next]
                val (ok, n, round) = deliver(text, next + 1)
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

    /** Send one message until "got it" or out of tries. Returns (delivered, tries used, seconds from first send to got-it seen). */
    private fun deliver(text: String, k: Int): Triple<Boolean, Int, Double> {
        val t0 = now()
        var useShort = o.short && outLink != null
        var seq = if (useShort) outLink!!.lastSeq + 1 else 0
        var fullNonce = -1
        for (attempt in 1..o.tries) {
            if (!alive) break
            val mode = BrightFormat.MODES[if (o.auto && li >= 0) ladder[li] else o.startMode]
            show("Message $k, try $attempt: sending in ${mode.name}${if (useShort) ", short opening" else ""}...")
            events?.sending(k, text, attempt, mode, useShort)
            closeCam()
            rx.replies.clear()
            val s = sender.send(text, mode, pass, 0.0, if (useShort) outLink else null, seq, if (useShort) -1 else fullNonce) { }
            val sendEnd = now()
            if (!useShort) fullNonce = s.nonce
            rx.expectRate = mode.perSec
            rx.expect = s.nonce
            val listenAfter = openCam()
            var got: Boolean? = null; var seenAfter = 0.0
            if (s.failure == null) {
                val until = sendEnd + o.waitReply
                while (alive && now() < until) {
                    val rep = rx.replies.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (rep.first == s.nonce) { got = rep.second; seenAfter = now() - sendEnd; break }
                }
            }
            rx.expect = -1
            val outcome = when { s.failure != null -> "SEND FAILED: ${s.failure}"; got == true -> "GOT IT"; got == false -> "COULD NOT READ"; else -> "NO REPLY" }
            log(String.format(Locale.US, "SEND %d,%d,%s,%d,%d,%d,%d,%.1f,%.1f,%s,%.1f,%.1f,\"%s\"\n", k, attempt, if (useShort) "short#$seq" else "full",
                mode.id, mode.levels, mode.perSec, s.nonce, s.seconds, listenAfter, outcome, seenAfter, if (got == true) now() - t0 else 0.0, text.replace("\"", "'")))
            if (got == true) {
                outLink = if (useShort) outLink!!.copy(lastSeq = seq) else BrightFormat.Link(s.nonce, BrightFormat.phoneTag(android.os.Build.MODEL), s.pminMs, 0)
                if (attempt == 1) streak++ else streak = 0
                if (o.auto && li > 0 && streak >= 2) { li--; streak = 0; log("# auto-speed: up to ${BrightFormat.MODES[ladder[li]].name}\n") }
                return Triple(true, attempt, now() - t0)
            }
            streak = 0
            if (got == null && useShort) {
                // No reply at all to a short opening: maybe the link is gone on the other side. Next try: full opening.
                useShort = false; outLink = null; seq = 0
                log("# link dropped: next try uses the full opening\n")
            } else if (o.auto && li >= 0 && li < ladder.size - 1) { li++; log("# auto-speed: down to ${BrightFormat.MODES[ladder[li]].name}\n") }
            if (rx.hearing) Thread.sleep(500)
            // No reply: maybe the other phone was sending too. Wait a random extra moment so the two drift apart.
            if (got == null && !o.turns) Thread.sleep((1000 + Math.random() * 4000).toLong())
        }
        show("Message $k NOT delivered after ${o.tries} tries")
        return Triple(false, o.tries, 0.0)
    }
}
