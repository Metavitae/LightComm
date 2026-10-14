package com.metavitae.lightlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

/**
 * One open camera that reports, for every frame, how bright the brightest
 * spot is. Exposure can be fixed by hand (best for reading a light) or
 * automatic and then locked.
 */
class CamSession(
    private val ctx: Context,
    val camId: String,
    private val preview: TextureView?,
    private val onFrame: (Frame) -> Unit,
) {
    data class Frame(
        val tsNs: Long,        // sensor timestamp, same clock as SystemClock.elapsedRealtimeNanos
        val block: Int,        // average of the brightest of 16x12 blocks, 0-255
        val bx: Int, val by: Int,
        val peak: Int,         // brightest single sampled pixel, 0-255
        val mean: Int,         // whole picture average, 0-255
        val expNs: Long, val iso: Int, val skewNs: Long, val flashState: Int,
    )

    var manual = true
    var expNs = 1_000_000L
    var iso = 400
    var fps = 30
    var torch = false
    var onError: (String) -> Unit = {}

    /** When set, also receives each frame as a fine grid of zone brightnesses (ZX x ZY, sensor orientation). */
    var zoneSink: ((Long, FloatArray) -> Unit)? = null

    /** How the sensor is turned relative to the phone held upright (0, 90, 180 or 270). */
    val sensorOrientation: Int =
        ctx.getSystemService(CameraManager::class.java).getCameraCharacteristics(camId)[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
    var size: Size = Size(640, 480)
        private set

    private val thread = HandlerThread("cam").apply { start() }
    val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var builder: CaptureRequest.Builder? = null
    private var aeLocked = false

    @Volatile private var lastExp = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastSkew = 0L
    @Volatile private var lastFlash = -1

    private val cm = ctx.getSystemService(CameraManager::class.java)

    @SuppressLint("MissingPermission")
    fun open(onReady: () -> Unit) {
        size = pickSize()
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 6).apply {
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { analyze(it) } }, handler)
        }
        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; createSession(d, onReady) }
                override fun onDisconnected(d: CameraDevice) { d.close(); onError("camera disconnected") }
                override fun onError(d: CameraDevice, e: Int) { d.close(); onError("camera error $e") }
            }, handler)
        } catch (e: Exception) {
            onError("could not open camera: ${e.message}")
        }
    }

    private fun pickSize(): Size {
        val map = cm.getCameraCharacteristics(camId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(640, 480)
        return sizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: Size(640, 480)
    }

    /** Shortest frame time this camera allows at the chosen size, in ns (0 if unknown). */
    fun minFrameNs(): Long {
        val map = cm.getCameraCharacteristics(camId)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        return map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) ?: 0L
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice, onReady: () -> Unit) {
        val surfaces = mutableListOf(reader!!.surface)
        preview?.surfaceTexture?.let { st ->
            st.setDefaultBufferSize(size.width, size.height)
            surfaces.add(Surface(st))
        }
        try {
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach { addTarget(it) }
                    }
                    apply()
                    if (!manual) handler.postDelayed({ aeLocked = true; apply() }, 1500)
                    onReady()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { onError("camera setup failed") }
            }, handler)
        } catch (e: Exception) {
            onError("camera setup failed: ${e.message}")
        }
    }

    /** Push the current settings to the camera. Safe to call from any thread. */
    fun apply() {
        handler.post {
            val b = builder ?: return@post
            val s = session ?: return@post
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            if (manual) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLocked)
            }
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            try {
                val pat = torchPattern
                if (pat != null) s.setRepeatingBurst(requests(b, pat), captureCb, handler)
                else s.setRepeatingRequest(b.build(), captureCb, handler)
            } catch (e: Exception) {
                onError("camera settings refused: ${e.message}")
            }
        }
    }

    /**
     * When set, the flashlight follows this pattern one camera picture at a
     * time, repeating (used for two-way talk: the camera itself blinks the light).
     */
    var torchPattern: BooleanArray? = null

    /** Bursts queued but not finished yet. */
    @Volatile var queuedBursts = 0

    private fun requests(b: CaptureRequest.Builder, states: BooleanArray): List<CaptureRequest> = states.map { on ->
        b.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        b.build()
    }

    /** Queue pictures with these flashlight states, one per picture, after anything already queued. */
    fun queueTorch(states: BooleanArray) {
        handler.post {
            val b = builder ?: return@post
            val s = session ?: return@post
            try {
                queuedBursts++
                s.captureBurst(requests(b, states), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        captureCb.onCaptureCompleted(cs, r, res)
                    }
                    override fun onCaptureSequenceCompleted(cs: CameraCaptureSession, id: Int, frame: Long) { queuedBursts-- }
                    override fun onCaptureSequenceAborted(cs: CameraCaptureSession, id: Int) { queuedBursts-- }
                }, handler)
            } catch (e: Exception) {
                queuedBursts--
                onError("could not queue flashlight pattern: ${e.message}")
            }
        }
    }

    fun unlockAuto() { aeLocked = false; apply(); handler.postDelayed({ aeLocked = true; apply() }, 1500) }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            lastExp = res[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 0L
            lastIso = res[CaptureResult.SENSOR_SENSITIVITY] ?: 0
            lastSkew = res[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW] ?: 0L
            lastFlash = res[CaptureResult.FLASH_STATE] ?: -1
        }
    }

    private fun analyze(img: Image) {
        try {
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            val w = img.width
            val h = img.height
            val gx = 16
            val gy = 12
            val bw = maxOf(1, w / gx)
            val bh = maxOf(1, h / gy)
            val sums = IntArray(gx * gy)
            val counts = IntArray(gx * gy)
            var total = 0L
            var n = 0
            var peak = 0
            val sink = zoneSink
            val fine = if (sink != null) FloatArray(ZX * ZY) else null
            val fineN = if (sink != null) IntArray(ZX * ZY) else null
            var y = 0
            while (y < h) {
                val row = y * rs
                val by = minOf(y / bh, gy - 1)
                val fy = minOf(y * ZY / h, ZY - 1) * ZX
                var x = 0
                while (x < w) {
                    val v = buf.get(row + x * ps).toInt() and 0xff
                    val i = by * gx + minOf(x / bw, gx - 1)
                    sums[i] += v
                    counts[i]++
                    total += v
                    n++
                    if (v > peak) peak = v
                    if (fine != null) {
                        val fi = fy + minOf(x * ZX / w, ZX - 1)
                        fine[fi] += v.toFloat()
                        fineN!![fi]++
                    }
                    x += 2
                }
                y += 2
            }
            var best = 0
            var bi = 0
            for (i in sums.indices) {
                val m = if (counts[i] > 0) sums[i] / counts[i] else 0
                if (m > best) { best = m; bi = i }
            }
            if (sink != null && fine != null) {
                val nn = fineN!!
                for (i in fine.indices) if (nn[i] > 0) fine[i] = fine[i] / nn[i]
                sink(img.timestamp, fine)
            }
            onFrame(Frame(img.timestamp, best, bi % gx, bi / gx, peak, (total / maxOf(1, n)).toInt(),
                lastExp, lastIso, lastSkew, lastFlash))
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    fun close() { closeAndWait(0) }

    /** Close, and wait up to `ms` for it to be done (the flashlight can't be switched while the camera is open). */
    fun closeAndWait(ms: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            session = null; device = null; reader = null
            done.countDown()
            thread.quitSafely()
        }
        if (ms > 0) done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        const val ZX = 64
        const val ZY = 48

        /** Back cameras an app may open, with a plain label. */
        fun backCameras(ctx: Context): List<Pair<String, String>> {
            val cm = ctx.getSystemService(CameraManager::class.java)
            return cm.cameraIdList.mapNotNull { id ->
                val c = cm.getCameraCharacteristics(id)
                if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) null
                else {
                    val f = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                    id to "Back camera $id (lens ${String.format("%.1f", f)} mm)"
                }
            }
        }
    }
}
