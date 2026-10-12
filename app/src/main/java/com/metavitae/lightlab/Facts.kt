package com.metavitae.lightlab

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the phone reports about itself, plus short measurements that
 * need no help from the founder: camera frame timing, flashlight switching
 * times, flashlight while the camera runs, light sensor speed, power draw.
 */
class Facts(private val ctx: Context, private val progress: (String) -> Unit) {
    private val sb = StringBuilder(Results.header("Phone facts (automatic)"))
    private val cm = ctx.getSystemService(CameraManager::class.java)

    fun run(): String {
        step("Cameras") { cameras() }
        step("Flashlight") { torch() }
        step("Infrared") { sb.append(IrSender(ctx).describe()).append("\n") }
        step("Screen") { screen() }
        step("Light sensor") { lightSensor() }
        step("Camera timing") { cameraTiming() }
        step("Flashlight while camera runs") { torchWithCamera() }
        step("Power") { power() }
        sb.append("\nThermal status at end: ${ctx.getSystemService(PowerManager::class.java).currentThermalStatus}\n")
        return sb.toString()
    }

    private fun step(name: String, f: () -> Unit) {
        progress("Checking: $name")
        sb.append("\n== $name ==\n")
        try { f() } catch (e: Exception) { sb.append("FAILED: ${e.javaClass.simpleName}: ${e.message}\n") }
    }

    private fun cameras() {
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c[CameraCharacteristics.LENS_FACING]) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "other"
            }
            val level = when (c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "other"
            }
            val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList() ?: emptyList()
            val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            val fpsRanges = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.joinToString { "${it.lower}-${it.upper}" }
            val exp = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            val iso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
            val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.joinToString()
            val zoom = if (Build.VERSION.SDK_INT >= 30) c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { "${it.lower}-${it.upper}" } else "n/a"
            val pix = c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val small = yuv?.filter { it.width <= 1920 }?.sortedByDescending { it.width * it.height }?.take(4)
                ?.joinToString { s ->
                    val d = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, s)
                    "${s.width}x${s.height}@${if (d > 0) 1_000_000_000L / d else 0}fps-max"
                }
            val hs = map?.highSpeedVideoFpsRanges?.joinToString { "${it.lower}-${it.upper}" } ?: "none"
            val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString() else ""
            sb.append("Camera $id ($facing): hardware level $level, lens ${focal} mm, sensor ${pix?.width}x${pix?.height}, zoom $zoom\n")
            sb.append("  flash: ${c[CameraCharacteristics.FLASH_INFO_AVAILABLE]}, manual exposure+ISO: $manual, AE lock: ${c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE]}, AWB lock: ${c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE]}\n")
            sb.append("  exposure range: ${exp?.lower} - ${exp?.upper} ns, ISO range: ${iso?.lower} - ${iso?.upper}\n")
            sb.append("  auto-exposure frame-rate ranges: $fpsRanges\n")
            sb.append("  readable picture sizes (max frame rate the phone claims): $small\n")
            sb.append("  slow-motion (high speed, preview only) ranges: $hs\n")
            if (physical.isNotEmpty()) sb.append("  combines physical cameras: $physical\n")
        }
    }

    private fun torch() {
        val tc = torchCamera(ctx)
        if (tc == null) { sb.append("No flashlight an app can use.\n"); return }
        val (id, levels) = tc
        sb.append("Flashlight belongs to camera $id. Brightness steps reported: $levels ")
        sb.append(if (levels <= 1) "(ON/OFF only, no dimming)\n" else "(can dim)\n")
        if (Build.VERSION.SDK_INT >= 33) {
            val c = cm.getCameraCharacteristics(id)
            sb.append("  default level: ${c[CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL]}\n")
        }
        // Software switching times at several speeds.
        for (rate in listOf(10.0, 30.0, 60.0)) {
            val out = TorchSender(ctx).run(0, rate, 2.0) {}
            val keep = out.lines().filter {
                it.startsWith("Started late") || it.startsWith("Time the phone") || it.startsWith("Time until") ||
                    it.startsWith("STOPPED") || it.startsWith("Switches made")
            }
            sb.append("  At $rate changes per second:\n")
            keep.forEach { sb.append("    ").append(it).append("\n") }
            Thread.sleep(500)
        }
    }

    private fun screen() {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        sb.append("Current refresh rate: ${d.refreshRate} Hz\n")
        sb.append("Modes: ${d.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}Hz" }}\n")
        d.hdrCapabilities?.let { sb.append("Max brightness reported: ${it.desiredMaxLuminance} nits\n") }
    }

    private fun lightSensor() {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (s == null) { sb.append("No light sensor.\n"); return }
        sb.append("Sensor: ${s.name} by ${s.vendor}, max ${s.maximumRange} lux, step ${s.resolution} lux, fastest promised interval ${s.minDelay} us, reports ${if (s.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE) "only on change" else "continuously"}\n")
        val times = CopyOnWriteArrayList<Long>()
        val values = CopyOnWriteArrayList<Float>()
        val t = HandlerThread("lux").apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { times.add(e.timestamp); values.add(e.values[0]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        Thread.sleep(4000)
        sm.unregisterListener(l)
        t.quitSafely()
        val gaps = times.zipWithNext { a, b -> (b - a) / 1e6 }
        sb.append("In 4 s: ${times.size} readings, values ${values.minOrNull()}-${values.maxOrNull()} lux, gap between readings (ms): ${Stats(gaps).line("")}\n")
    }

    private fun runCamera(id: String, fps: Int, ms: Long, setup: (CamSession) -> Unit = {}, during: (CamSession) -> Unit = {}): List<CamSession.Frame> {
        val frames = CopyOnWriteArrayList<CamSession.Frame>()
        var err: String? = null
        val cam = CamSession(ctx, id, null) { frames.add(it) }
        cam.onError = { err = it }
        cam.manual = true; cam.expNs = 1_000_000; cam.iso = 400; cam.fps = fps
        setup(cam)
        var ready = false
        cam.open { ready = true }
        val t0 = SystemClock.elapsedRealtime()
        while (!ready && err == null && SystemClock.elapsedRealtime() - t0 < 5000) Thread.sleep(20)
        if (!ready) { cam.close(); throw IllegalStateException(err ?: "camera did not open") }
        Thread.sleep(600) // settle
        frames.clear()
        during(cam)
        Thread.sleep(ms)
        cam.close()
        Thread.sleep(400)
        if (err != null) sb.append("  camera reported: $err\n")
        return frames.toList()
    }

    private fun timing(frames: List<CamSession.Frame>, fps: Int): String {
        if (frames.size < 3) return "only ${frames.size} frames arrived"
        val gaps = frames.zipWithNext { a, b -> (b.tsNs - a.tsNs) / 1e6 }
        val expected = 1000.0 / fps
        val dropped = gaps.count { it > expected * 1.5 }
        val real = (frames.size - 1) * 1e9 / (frames.last().tsNs - frames.first().tsNs)
        val skew = frames.map { it.skewNs / 1e6 }.filter { it > 0 }
        return "real rate ${Stats.f(real)} fps, gap between frames (ms) ${Stats(gaps).line("")}, late/dropped frames: $dropped of ${frames.size}, " +
            "reported exposure ${frames.last().expNs / 1000} us ISO ${frames.last().iso}, rolling-shutter readout ${if (skew.isEmpty()) "not reported" else Stats.f(skew.average()) + " ms"}"
    }

    private fun cameraTiming() {
        val back = CamSession.backCameras(ctx)
        val front = cm.cameraIdList.filter { cm.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT }
        val ids = back.map { it.first } + front
        for (id in ids) {
            for (fps in listOf(30, 60, 120)) {
                try {
                    val fr = runCamera(id, fps, 3000)
                    sb.append("Camera $id asked for $fps fps (fixed 1 ms exposure, ISO 400, ~640x480): ${timing(fr, fps)}\n")
                } catch (e: Exception) {
                    sb.append("Camera $id at $fps fps: FAILED ${e.message}\n")
                }
            }
        }
    }

    private fun torchWithCamera() {
        val tc = torchCamera(ctx) ?: run { sb.append("No flashlight.\n"); return }
        val id = tc.first
        // 1) The plain flashlight switch while this camera is open.
        var plainResult = "not tried"
        // 2) The flashlight driven through the open camera itself.
        val toggles = mutableListOf<Long>()
        val frames = runCamera(id, 30, 4000, during = { cam ->
            try {
                cm.setTorchMode(id, true)
                plainResult = "accepted"
                cm.setTorchMode(id, false)
            } catch (e: Exception) {
                plainResult = "refused (${e.message})"
            }
            Thread {
                for (i in 0 until 20) {
                    cam.torch = i % 2 == 0
                    toggles.add(SystemClock.elapsedRealtimeNanos())
                    cam.apply()
                    Thread.sleep(200)
                }
                cam.torch = false; cam.apply()
            }.start()
        })
        sb.append("Plain flashlight switch while the camera is open: $plainResult\n")
        val fired = frames.count { it.flashState == 3 }
        sb.append("Flashlight through the open camera: frames reporting flash ON: $fired of ${frames.size}\n")
        if (frames.size > 10) {
            val means = frames.map { it.mean.toDouble() }
            sb.append("  whole-picture brightness while toggling (0-255): ${Stats(means).line("")}\n")
            // How many frames after the request did the camera report the flash state change?
            val lags = mutableListOf<Double>()
            for ((i, t) in toggles.withIndex()) {
                val wantOn = i % 2 == 0
                val f = frames.firstOrNull { it.tsNs > t && (it.flashState == 3) == wantOn }
                if (f != null) lags.add((f.tsNs - t) / 1e6)
            }
            sb.append("  time from request to first frame with the flash in the new state (ms): ${Stats(lags).line("")}\n")
            sb.append("  (two-way talk needs this: the same phone sending with its flashlight while its camera watches the other phone)\n")
        }
    }

    private fun currentUa(): Double {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val vals = mutableListOf<Double>()
        repeat(15) { vals.add(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()); Thread.sleep(200) }
        return vals.average()
    }

    private fun power() {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        sb.append("Charger connected: $plugged ${if (plugged) "(power numbers below are not meaningful while charging)" else ""}\n")
        val idle = currentUa()
        sb.append("Battery current, idle with screen on: ${Stats.f(idle / 1000)} mA (sign depends on phone maker)\n")
        val tc = torchCamera(ctx)
        if (tc != null) {
            cm.setTorchMode(tc.first, true)
            Thread.sleep(500)
            val on = currentUa()
            cm.setTorchMode(tc.first, false)
            sb.append("Battery current, flashlight steady on: ${Stats.f(on / 1000)} mA\n")
        }
        val back = CamSession.backCameras(ctx).firstOrNull()
        if (back != null) {
            var cur = 0.0
            runCamera(back.first, 30, 100, during = { Thread.sleep(500); cur = currentUa() })
            sb.append("Battery current, camera receiving at 30 fps: ${Stats.f(cur / 1000)} mA\n")
        }
        val bm = ctx.getSystemService(BatteryManager::class.java)
        sb.append("Battery level: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%\n")
    }
}
