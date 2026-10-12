package com.metavitae.lightlab

import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** The back camera that owns the flashlight, and its brightness steps (1 = on/off only). */
fun torchCamera(ctx: Context): Pair<String, Int>? {
    val cm = ctx.getSystemService(CameraManager::class.java)
    for (id in cm.cameraIdList) {
        val c = cm.getCameraCharacteristics(id)
        if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
            c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true) {
            val levels = if (Build.VERSION.SDK_INT >= 33)
                c[CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL] ?: 1 else 1
            return id to levels
        }
    }
    return null
}

/** Sleep until a moment on the nanoTime clock, finishing with a short spin for accuracy. */
private fun waitUntil(t: Long) {
    while (true) {
        val left = t - System.nanoTime()
        if (left <= 0) return
        if (left > 2_000_000) Thread.sleep((left - 1_500_000) / 1_000_000) else Thread.onSpinWait()
    }
}

/**
 * Blinks the flashlight with set timing and records when each switch was
 * really requested, how long the phone took to accept it, and when the phone
 * reported it done. Real light timing is measured by the receiving phone.
 */
class TorchSender(private val ctx: Context) {
    @Volatile var running = false

    fun run(patternKind: Int, symbolsPerSec: Double, seconds: Double, progress: (String) -> Unit): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val (id, levels) = torchCamera(ctx) ?: return "This phone has no flashlight an app can use."
        val sb = StringBuilder()
        sb.append(Results.header("Flashlight sender"))
        sb.append("Pattern: ${PATTERN_NAMES[patternKind]}\nSpeed: $symbolsPerSec light changes per second\nLength: $seconds s\n")
        sb.append("Flashlight camera id: $id, brightness steps reported by phone: $levels\n\n")

        val callbackTimes = mutableListOf<Pair<Long, Boolean>>()
        val cbHandler = Handler(Looper.getMainLooper())
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) synchronized(callbackTimes) { callbackTimes.add(System.nanoTime() to enabled) }
            }
        }
        cm.registerTorchCallback(cb, cbHandler)

        val period = (1e9 / symbolsPerSec).toLong()
        val total = (seconds * symbolsPerSec).toInt()
        val late = mutableListOf<Double>()
        val callMs = mutableListOf<Double>()
        val calls = mutableListOf<Long>()
        val lines = StringBuilder("k,state,requested_ms,late_us,call_us,elapsedRealtime_ms\n")
        var state: Boolean? = null
        var failure: String? = null
        val start = System.nanoTime() + 300_000_000
        val startRealtime = SystemClock.elapsedRealtime()
        var lastThermal = 0L
        val thermalLog = StringBuilder()
        running = true
        for (k in 0 until total) {
            if (!running) break
            val target = start + k * period
            waitUntil(target)
            val on = Patterns.level(patternKind, k, 2) == 1
            if (on != state) {
                val t0 = System.nanoTime()
                try {
                    cm.setTorchMode(id, on)
                } catch (e: Exception) {
                    failure = "Flashlight refused at ${(t0 - start) / 1_000_000} ms after start: ${e.message}"
                    break
                }
                val t1 = System.nanoTime()
                late.add((t0 - target) / 1000.0)
                callMs.add((t1 - t0) / 1000.0)
                calls.add(t0)
                lines.append("$k,${if (on) 1 else 0},${(target - start) / 1_000_000},${(t0 - target) / 1000},${(t1 - t0) / 1000},${SystemClock.elapsedRealtime()}\n")
                state = on
            }
            if (t1Elapsed(start) - lastThermal >= 5_000) {
                lastThermal = t1Elapsed(start)
                thermalLog.append("  at ${lastThermal / 1000} s: thermal status ${pm.currentThermalStatus} (0 = none, 1 light, 2 moderate, 3 severe)\n")
                progress("Sending... ${lastThermal / 1000} s")
            }
        }
        running = false
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        Thread.sleep(300)
        cm.unregisterTorchCallback(cb)

        // Delay between asking for a switch and the phone saying it happened.
        val cbDelays = mutableListOf<Double>()
        synchronized(callbackTimes) {
            var j = 0
            for (c in calls) {
                while (j < callbackTimes.size && callbackTimes[j].first < c) j++
                if (j < callbackTimes.size) cbDelays.add((callbackTimes[j].first - c) / 1000.0)
            }
        }

        sb.append("RESULT\n")
        sb.append(if (failure == null) "Finished without the phone refusing.\n" else "STOPPED: $failure\n")
        sb.append("Switches made: ${calls.size}\n")
        sb.append("Started late by (microseconds): ${Stats(late).line("")}\n")
        sb.append("Time the phone took to accept each switch (microseconds): ${Stats(callMs).line("")}\n")
        sb.append("Time until the phone reported the switch done (microseconds): ${Stats(cbDelays).line("")}\n")
        sb.append("Note: these are software times. The real light timing is what the receiving phone's camera records.\n")
        sb.append("Heat:\n$thermalLog")
        sb.append("Start (elapsedRealtime ms): $startRealtime\n\nPER SWITCH\n").append(lines)
        return sb.toString()
    }

    private fun t1Elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000

    /** Steps through the flashlight's brightness levels, 1 second each, if it has any. */
    fun brightnessSteps(progress: (String) -> Unit): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val (id, levels) = torchCamera(ctx) ?: return "This phone has no flashlight an app can use."
        val sb = StringBuilder(Results.header("Flashlight brightness steps"))
        if (levels <= 1 || Build.VERSION.SDK_INT < 33) {
            sb.append("This phone reports only ON/OFF for its flashlight (maximum level $levels). No brightness steps to show.\n")
            return sb.toString()
        }
        running = true
        for (lvl in 1..levels) {
            if (!running) break
            progress("Level $lvl of $levels")
            try {
                cm.turnOnTorchWithStrengthLevel(id, lvl)
                sb.append("level $lvl at elapsedRealtime ${SystemClock.elapsedRealtime()} ms\n")
            } catch (e: Exception) {
                sb.append("level $lvl refused: ${e.message}\n")
            }
            Thread.sleep(1000)
        }
        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
        running = false
        return sb.toString()
    }

    companion object {
        val PATTERN_NAMES = listOf("Steady blink (on, off, on, off)", "Test pattern (known random on/off)")
    }
}

/** Infrared emitter (the TV-remote light). Blinks bursts of 38 kHz infrared. */
class IrSender(private val ctx: Context) {
    @Volatile var running = false

    fun describe(): String {
        val ir = ctx.getSystemService(ConsumerIrManager::class.java) ?: return "No infrared service."
        if (!ir.hasIrEmitter()) return "No infrared emitter on this phone."
        val ranges = ir.carrierFrequencies?.joinToString { "${it.minFrequency}-${it.maxFrequency} Hz" } ?: "not reported"
        return "Infrared emitter present. Carrier frequencies allowed: $ranges"
    }

    fun run(flashesPerSec: Double, seconds: Double, progress: (String) -> Unit): String {
        val sb = StringBuilder(Results.header("Infrared sender"))
        sb.append(describe()).append("\n")
        val ir = ctx.getSystemService(ConsumerIrManager::class.java)
        if (ir == null || !ir.hasIrEmitter()) return sb.toString()
        val halfUs = (500_000 / flashesPerSec).toInt()
        // One transmit call may last up to about 2 s, so send in chunks of whole flashes.
        val perChunk = maxOf(1, (1_000_000 / (2 * halfUs)))
        val pattern = IntArray(perChunk * 2) { halfUs }
        sb.append("Flashes per second: $flashesPerSec, on time per flash: ${halfUs / 1000.0} ms, 38000 Hz carrier\n")
        val start = SystemClock.elapsedRealtime()
        sb.append("Start elapsedRealtime: $start ms\n")
        running = true
        var chunks = 0
        try {
            while (running && SystemClock.elapsedRealtime() - start < seconds * 1000) {
                ir.transmit(38000, pattern)
                chunks++
                progress("Sending infrared... ${(SystemClock.elapsedRealtime() - start) / 1000} s")
            }
            sb.append("Finished. ${chunks} chunks sent in ${SystemClock.elapsedRealtime() - start} ms (expected about ${chunks * perChunk * 2L * halfUs / 1000} ms of pattern).\n")
        } catch (e: Exception) {
            sb.append("Infrared refused: ${e.message}\n")
        }
        running = false
        return sb.toString()
    }
}
