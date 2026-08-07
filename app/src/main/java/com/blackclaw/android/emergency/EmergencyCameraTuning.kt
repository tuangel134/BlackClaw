package com.blackclaw.android.emergency

/**
 * Pure recording-quality decisions for emergency video, expressed over plain types
 * so they are unit-testable on the JVM (`android.util.Size` / `android.util.Range`
 * are stubs in unit tests and would report zero for every field).
 *
 * [EmergencyCameraController] owns the Camera2 plumbing and adapts framework types
 * to and from these.
 */
internal object EmergencyCameraTuning {

    /** Preferred evidence rate. The controller falls back when this is unavailable. */
    const val TARGET_FPS = 60

    /**
     * Full HD is the highest broadly reliable Camera2 + MediaRecorder target at
     * 60 fps. Devices that advertise a validated 4K60 H.264 recording profile are
     * selected by [EmergencyCameraController] before this general fallback.
     */
    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080

    /** How far from exact 16:9 a sensor may be and still count as widescreen. */
    private const val WIDESCREEN_TOLERANCE = 0.06

    data class Fps(val lower: Int, val upper: Int)

    data class Dimensions(val width: Int, val height: Int) {
        val area: Int get() = width * height
    }

    /**
     * Pick the best sensor rate for a 60-fps recording.
     *
     * A true 60-fps range is preferred whenever one is reported. This is important
     * because setting `MediaRecorder` to 60 alone does not make the camera deliver
     * 60 frames. If 60 is not available we choose the smoothest rate at or below
     * it instead of fabricating an unsupported request.
     *
     * A fixed `[60,60]` range wins over a variable range containing 60. For a
     * fallback, higher upper and lower bounds win, yielding the smoothest genuine
     * stream the encoder can receive.
     *
     * @return null when the device reports nothing usable, so the caller can leave
     *   the capture key unset rather than send a fabricated range.
     */
    fun selectAeFpsRange(available: List<Fps>): Fps? {
        if (available.isEmpty()) return null
        val supportsTarget = available.filter { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }
        if (supportsTarget.isNotEmpty()) {
            return supportsTarget.sortedWith(
                compareBy<Fps>(
                    { if (it.lower == TARGET_FPS && it.upper == TARGET_FPS) 0 else 1 },
                    { kotlin.math.abs(it.upper - TARGET_FPS) },
                    { -it.lower },
                )
            ).first()
        }
        val sustainable = available.filter { it.upper <= TARGET_FPS }
        val pool = sustainable.ifEmpty { listOf(available.minByOrNull { it.upper }!!) }
        return pool.sortedWith(compareByDescending<Fps> { it.upper }.thenByDescending { it.lower }).first()
    }

    /**
     * Choose a recording resolution.
     *
     * Prefers the largest widescreen mode within the Full-HD cap, then any mode within
     * the cap, then anything up to 4K. The final fallback is the *smallest*
     * reported size, not the first: `getOutputSizes` returns descending order, so
     * taking the head can select a sensor mode that the general 60-fps encoder path
     * cannot sustain.
     */
    fun selectRecordingSize(sizes: List<Dimensions>): Dimensions {
        if (sizes.isEmpty()) return Dimensions(640, 480)
        return sizes.filter { it.valid() && it.withinCap() && it.isWidescreen() }.maxByOrNull { it.area }
            ?: sizes.filter { it.valid() && it.withinCap() }.maxByOrNull { it.area }
            ?: sizes.filter { it.valid() && it.width <= 3840 && it.height <= 2160 }.maxByOrNull { it.area }
            ?: sizes.filter { it.valid() }.minByOrNull { it.area }
            ?: Dimensions(640, 480)
    }

    /**
     * MP4 orientation hint.
     *
     * Front sensors need the device rotation *added* rather than subtracted;
     * subtracting for both lenses is why selfie evidence lands upside down.
     */
    fun orientationHint(sensorOrientation: Int, deviceRotationDegrees: Int, front: Boolean): Int {
        val normalizedSensor = ((sensorOrientation % 360) + 360) % 360
        val normalizedRotation = ((deviceRotationDegrees % 360) + 360) % 360
        return if (front) (normalizedSensor + normalizedRotation) % 360
        else (normalizedSensor - normalizedRotation + 360) % 360
    }

    /** Encoder bitrate for a chosen resolution/rate, tuned for readable evidence. */
    fun bitrateFor(size: Dimensions, fps: Int = TARGET_FPS): Int = when {
        size.width >= 3840 -> if (fps >= 60) 55_000_000 else 35_000_000
        size.width >= 1920 -> if (fps >= 60) 22_000_000 else 14_000_000
        size.width >= 1280 -> if (fps >= 60) 12_000_000 else 7_000_000
        else -> if (fps >= 60) 5_000_000 else 3_000_000
    }

    private fun Dimensions.withinCap(): Boolean = width <= MAX_WIDTH && height <= MAX_HEIGHT

    private fun Dimensions.valid(): Boolean = width > 0 && height > 0

    private fun Dimensions.isWidescreen(): Boolean {
        if (height == 0) return false
        val ratio = width.toDouble() / height.toDouble()
        return kotlin.math.abs(ratio - 16.0 / 9.0) < WIDESCREEN_TOLERANCE
    }
}
