package com.blackclaw.android.emergency

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 video evidence controller with explicit concurrent-camera capability checks.
 *
 * Recording correctness notes (these are the difference between usable evidence and
 * a black file):
 *
 *  - **3A must be requested explicitly.** `TEMPLATE_RECORD` is documented to enable
 *    auto-exposure, but several OEM HALs (MIUI, MagicOS/EMUI) do not apply sane 3A
 *    defaults when the only stream target is a [MediaRecorder] surface with no
 *    preview attached. We set AE/AF/AWB on every request instead of trusting the
 *    template.
 *  - **The AE target FPS range decides how dark the video is.** A range with a high
 *    lower bound (e.g. `[30,30]`) caps sensor exposure at 1/30 s, which is what
 *    produces near-black footage indoors or at night. We deliberately pick the
 *    range with the *lowest* lower bound so AE can lengthen exposure.
 *  - **AE needs frames to converge before the encoder starts.** We run the repeating
 *    request first and only call [MediaRecorder.start] once AE reports converged
 *    (bounded by [AE_WARMUP_TIMEOUT_MS]). Frames produced before `start()` are
 *    dropped by the surface, so this costs nothing but brightness.
 *  - **A failed `stop()` must not destroy evidence.** `MediaRecorder.stop()` throws
 *    when no frames reached the encoder, and it can also throw after a partial
 *    write. We keep any file that has bytes in it rather than deleting it.
 */
class EmergencyCameraController(
    private val context: Context,
    private val onSegmentReady: (File, String) -> Unit,
    private val onEvent: (String) -> Unit,
) : Closeable {
    companion object {
        /** Upper bound we ask the encoder and the sensor to agree on. */
        internal const val TARGET_FPS = EmergencyCameraTuning.TARGET_FPS

        /**
         * How long we let auto-exposure settle before starting the encoder. Some
         * HALs never emit CONTROL_AE_STATE_CONVERGED, so this is also the fallback
         * that guarantees recording always begins.
         */
        private const val AE_WARMUP_TIMEOUT_MS = 1_500L

        /** Never start the encoder before the sensor has produced a few frames. */
        private const val AE_WARMUP_MIN_FRAMES = 3

        fun inspect(context: Context): Capability {
            val manager = context.getSystemService(CameraManager::class.java)
            val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
            fun firstFacing(target: Int): String? = ids.firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                }.getOrNull() == target
            }
            val front = firstFacing(CameraCharacteristics.LENS_FACING_FRONT)
            val back = firstFacing(CameraCharacteristics.LENS_FACING_BACK)
            val concurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                front != null && back != null) {
                runCatching {
                    manager.concurrentCameraIds.any { pair -> pair.contains(front) && pair.contains(back) }
                }.getOrDefault(false)
            } else false
            return Capability(front, back, concurrent)
        }

        internal fun select(choice: EmergencyCameras, capability: Capability): List<Pair<String, String>> = when (choice) {
            EmergencyCameras.NONE -> emptyList()
            EmergencyCameras.FRONT -> listOfNotNull(capability.frontId?.let { it to "front" })
            EmergencyCameras.BACK -> listOfNotNull(capability.backId?.let { it to "back" })
            EmergencyCameras.BOTH -> if (capability.concurrentFrontBack) {
                listOfNotNull(capability.frontId?.let { it to "front" }, capability.backId?.let { it to "back" })
            } else listOfNotNull((capability.backId ?: capability.frontId)?.let {
                it to if (it == capability.backId) "back" else "front"
            })
        }

        /** Adapts the Camera2 FPS ranges to [EmergencyCameraTuning] and back. */
        internal fun selectAeFpsRange(available: Array<Range<Int>>?): Range<Int>? {
            val ranges = available?.toList().orEmpty()
            val chosen = EmergencyCameraTuning.selectAeFpsRange(
                ranges.map { EmergencyCameraTuning.Fps(it.lower, it.upper) }
            ) ?: return null
            return Range(chosen.lower, chosen.upper)
        }

        /** Adapts the Camera2 output sizes to [EmergencyCameraTuning] and back. */
        internal fun selectRecordingSize(sizes: List<Size>): Size {
            val chosen = EmergencyCameraTuning.selectRecordingSize(
                sizes.map { EmergencyCameraTuning.Dimensions(it.width, it.height) }
            )
            return Size(chosen.width, chosen.height)
        }
    }

    data class Capability(
        val frontId: String?,
        val backId: String?,
        val concurrentFrontBack: Boolean,
    )

    private inner class Slot(val id: String, val lens: String) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var output: File? = null
        /** Set once the encoder actually started, so we know `stop()` is meaningful. */
        val started = AtomicBoolean(false)
        /** Guards the one-shot transition from AE warm-up to `recorder.start()`. */
        val encoderLaunched = AtomicBoolean(false)
        var warmupFrames = 0
        var warmupFallback: Runnable? = null
    }

    private data class RecordingSpec(
        val size: Size,
        val frameRate: Int,
        val bitRate: Int,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("BlackClawEmergencyCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val slotLock = Any()
    private val slots = ArrayList<Slot>()
    @Volatile private var closed = false
    @Volatile private var requested = EmergencyCameras.NONE
    @Volatile private var torch = false

    fun capability(): Capability = inspect(context)

    /**
     * Open the requested cameras and begin a segment.
     *
     * @param useTorch turn the rear flash on while recording. Meaningful evidence in
     *   the dark is impossible without it, but it is visible to bystanders, so the
     *   caller decides (never enabled for discreet mode).
     * @return the camera set actually acquired, which may be narrower than requested.
     */
    fun start(choice: EmergencyCameras, useTorch: Boolean = false): EmergencyCameras {
        requested = choice
        torch = useTorch
        val capability = capability()
        val selected = select(choice, capability)
        if (choice == EmergencyCameras.BOTH && !capability.concurrentFrontBack) {
            onEvent("dual_camera_unsupported fallback=${selected.firstOrNull()?.second ?: "none"}")
        }
        selected.forEach { (id, lens) ->
            val slot = Slot(id, lens)
            synchronized(slotLock) { slots += slot }
            // Camera2 callbacks are delivered on `handler`; keep every open/close on
            // that thread too so slot state is only ever touched from one place.
            handler.post { open(slot) }
        }
        return when (selected.map { it.second }.toSet()) {
            setOf("front", "back") -> EmergencyCameras.BOTH
            setOf("front") -> EmergencyCameras.FRONT
            setOf("back") -> EmergencyCameras.BACK
            else -> EmergencyCameras.NONE
        }
    }

    /**
     * Close current files, emit them for encryption, then start a fresh segment.
     *
     * Runs on the camera thread: `MediaRecorder.stop()` and `CameraDevice.close()`
     * block for hundreds of milliseconds and must never run on the main thread.
     */
    fun rotate() {
        if (closed || requested == EmergencyCameras.NONE) return
        handler.post {
            if (closed) return@post
            stopSlots()
            if (!closed) start(requested, torch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(slot: Slot) {
        runCatching {
            manager.openCamera(slot.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    slot.device = camera
                    configure(slot)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    onEvent("camera_disconnected lens=${slot.lens}")
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    onEvent("camera_error lens=${slot.lens} code=$error")
                    camera.close()
                }
            }, handler)
        }.onFailure { onEvent("camera_open_failed lens=${slot.lens} error=${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun configure(slot: Slot) {
        val camera = slot.device ?: return
        val characteristics = runCatching { manager.getCameraCharacteristics(slot.id) }.getOrNull()
        runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
            val fpsRange = selectAeFpsRange(
                characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            )
            val spec = recordingSpec(slot.id, characteristics, fpsRange)
            val size = spec.size
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val output = EmergencyEvidenceVault.newPlainVideoSegment(context, stamp, slot.lens)
            recorder.apply {
                setOnErrorListener { _, what, extra ->
                    onEvent("camera_recorder_error lens=${slot.lens} what=$what extra=$extra")
                }
                setOnInfoListener { _, what, extra ->
                    onEvent("camera_recorder_info lens=${slot.lens} what=$what extra=$extra")
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(spec.bitRate)
                setVideoFrameRate(spec.frameRate)
                setVideoSize(size.width, size.height)
                setOrientationHint(orientationHint(characteristics, slot.lens))
                setOutputFile(output.absolutePath)
                prepare()
            }
            slot.recorder = recorder
            slot.output = output
            val surface = recorder.surface
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed) { session.close(); return }
                    slot.session = session
                    runCatching {
                        val request = buildRecordRequest(camera, surface, characteristics, fpsRange)
                        // Stream frames first so 3A converges, then hand the surface to
                        // the encoder. Starting the encoder on this same tick is what
                        // produces dark footage — the sensor has not metered yet.
                        session.setRepeatingRequest(request, warmupCallback(slot, size, fpsRange), handler)
                        scheduleWarmupFallback(slot, size, fpsRange)
                        onEvent(
                            "camera_warmup lens=${slot.lens} size=${size.width}x${size.height} " +
                                "fps=${spec.frameRate} sensor=${fpsRange?.lower ?: "?"}-${fpsRange?.upper ?: "?"} torch=$torch"
                        )
                    }.onFailure {
                        onEvent("camera_start_failed lens=${slot.lens} error=${it.javaClass.simpleName}: ${it.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onEvent("camera_configure_failed lens=${slot.lens}")
                }
            }, handler)
        }.onFailure { onEvent("camera_prepare_failed lens=${slot.lens} error=${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Build the recording request with 3A stated explicitly. Relying on
     * `TEMPLATE_RECORD` defaults is what leaves footage black on several OEM HALs.
     */
    private fun buildRecordRequest(
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics?,
        fpsRange: Range<Int>?,
    ): CaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        addTarget(surface)
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AE_LOCK, false)
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        // Bias slightly bright: evidence is more useful over-exposed than black.
        exposureCompensationStep(characteristics)?.let { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (torch && hasFlash(characteristics)) {
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        }
        // Video stabilisation when the device offers it — emergency footage is
        // almost always handheld and moving.
        if (hasVideoStabilisation(characteristics)) {
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            )
        }
    }.build()

    /**
     * Watches auto-exposure and starts the encoder as soon as the scene is metered.
     * Falls through on any HAL that never reports CONVERGED via
     * [scheduleWarmupFallback].
     */
    private fun warmupCallback(
        slot: Slot,
        size: Size,
        fpsRange: Range<Int>?,
    ) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (slot.encoderLaunched.get()) return
            slot.warmupFrames++
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val settled = aeState == null ||
                aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED ||
                aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
            if (settled && slot.warmupFrames >= AE_WARMUP_MIN_FRAMES) {
                launchEncoder(slot, size, fpsRange, "ae_state=$aeState frames=${slot.warmupFrames}")
            }
        }
    }

    private fun scheduleWarmupFallback(slot: Slot, size: Size, fpsRange: Range<Int>?) {
        val fallback = Runnable {
            if (!slot.encoderLaunched.get()) {
                launchEncoder(slot, size, fpsRange, "ae_timeout frames=${slot.warmupFrames}")
            }
        }
        slot.warmupFallback = fallback
        handler.postDelayed(fallback, AE_WARMUP_TIMEOUT_MS)
    }

    /** Starts the encoder exactly once per slot. */
    private fun launchEncoder(slot: Slot, size: Size, fpsRange: Range<Int>?, reason: String) {
        if (closed) return
        if (!slot.encoderLaunched.compareAndSet(false, true)) return
        slot.warmupFallback?.let { handler.removeCallbacks(it) }
        slot.warmupFallback = null
        val recorder = slot.recorder ?: return
        runCatching { recorder.start() }
            .onSuccess {
                slot.started.set(true)
                onEvent(
                    "camera_recording lens=${slot.lens} size=${size.width}x${size.height} " +
                        "fps=${fpsRange?.lower ?: "?"}-${fpsRange?.upper ?: TARGET_FPS} $reason"
                )
            }
            .onFailure {
                onEvent("camera_start_failed lens=${slot.lens} error=${it.javaClass.simpleName}: ${it.message}")
            }
    }

    private fun recordingSize(characteristics: CameraCharacteristics?): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = runCatching { map?.getOutputSizes(MediaRecorder::class.java).orEmpty().toList() }
            .getOrDefault(emptyList())
        return selectRecordingSize(sizes)
    }

    /**
     * Uses a device-declared 60-fps H.264 profile when possible. This is the
     * only safe way to opt into 4K60: a large output size by itself says nothing
     * about encoder throughput. All other devices get the robust Full-HD-or-less
     * selector plus their highest real sensor frame rate.
     */
    private fun recordingSpec(
        cameraId: String,
        characteristics: CameraCharacteristics?,
        fpsRange: Range<Int>?,
    ): RecordingSpec {
        preferred60FpsProfile(cameraId)?.let { return it }
        val size = recordingSize(characteristics)
        val frameRate = fpsRange?.let {
            if (it.lower <= TARGET_FPS && it.upper >= TARGET_FPS) TARGET_FPS
            else it.upper.coerceIn(15, TARGET_FPS)
        } ?: 30
        return RecordingSpec(
            size = size,
            frameRate = frameRate,
            bitRate = EmergencyCameraTuning.bitrateFor(
                EmergencyCameraTuning.Dimensions(size.width, size.height), frameRate
            ),
        )
    }

    private fun preferred60FpsProfile(cameraId: String): RecordingSpec? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val qualities = listOf(
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_2K,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
        )
        return qualities.asSequence()
            .mapNotNull { quality -> runCatching { CamcorderProfile.getAll(cameraId, quality) }.getOrNull() }
            .flatMap { it.videoProfiles.asSequence() }
            .filter {
                it.codec == MediaRecorder.VideoEncoder.H264 &&
                    it.frameRate == TARGET_FPS && it.width > 0 && it.height > 0
            }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { RecordingSpec(Size(it.width, it.height), it.frameRate, it.bitrate) }
    }

    private fun hasFlash(characteristics: CameraCharacteristics?): Boolean =
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun hasVideoStabilisation(characteristics: CameraCharacteristics?): Boolean =
        characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

    /**
     * Nudge exposure one step above neutral when the device supports compensation.
     * Clamped to the reported range so we never send an out-of-bounds key.
     */
    private fun exposureCompensationStep(characteristics: CameraCharacteristics?): Int? {
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
        if (range.upper <= 0) return null
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        // One third of a stop is a safe, barely-perceptible bias on every device;
        // CONTROL_AE_COMPENSATION_STEP is a rational in EV units.
        val perEv = step?.let { runCatching { it.denominator / it.numerator }.getOrNull() } ?: 3
        return (perEv / 3).coerceIn(1, range.upper)
    }

    private fun orientationHint(characteristics: CameraCharacteristics?, lens: String): Int {
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return EmergencyCameraTuning.orientationHint(sensor, degrees, front = lens == "front")
    }

    private fun stopSlots() {
        val current = synchronized(slotLock) {
            val copy = slots.toList()
            slots.clear()
            copy
        }
        current.forEach { slot ->
            slot.warmupFallback?.let { handler.removeCallbacks(it) }
            slot.warmupFallback = null
            runCatching { slot.session?.stopRepeating() }
            runCatching { slot.session?.abortCaptures() }
            runCatching { slot.session?.close() }
            runCatching { slot.device?.close() }

            val hadEncoder = slot.started.get()
            val stopError = if (hadEncoder) {
                runCatching { slot.recorder?.stop() }.exceptionOrNull()
            } else null
            runCatching { slot.recorder?.reset() }
            runCatching { slot.recorder?.release() }

            val file = slot.output
            val bytes = file?.length() ?: 0L
            when {
                // Keep anything with data even if stop() failed. MediaRecorder throws
                // when the moov atom could not be finalised, but a truncated MP4 is
                // still recoverable evidence — deleting it is not.
                bytes > 0L && file != null -> {
                    if (stopError != null) {
                        onEvent(
                            "camera_segment_partial lens=${slot.lens} bytes=$bytes " +
                                "error=${stopError.javaClass.simpleName}: ${stopError.message}"
                        )
                    }
                    onSegmentReady(file, slot.lens)
                }
                !hadEncoder -> {
                    onEvent("camera_segment_never_started lens=${slot.lens}")
                    runCatching { file?.delete() }
                }
                else -> {
                    onEvent(
                        "camera_segment_empty lens=${slot.lens} " +
                            "error=${stopError?.javaClass?.simpleName ?: "no_frames"}"
                    )
                    runCatching { file?.delete() }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            stopSlots()
            thread.quitSafely()
        }
    }
}
