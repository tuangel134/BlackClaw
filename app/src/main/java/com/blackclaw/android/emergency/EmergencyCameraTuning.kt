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

    /** Frame-rate bound we ask both the sensor and the encoder to agree on. */
    const val TARGET_FPS = 24

    /** Resolution cap: keeps segments small and the encoder reliable on low-end hardware. */
    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720

    /** How far from exact 16:9 a sensor may be and still count as widescreen. */
    private const val WIDESCREEN_TOLERANCE = 0.06

    data class Fps(val lower: Int, val upper: Int)

    data class Dimensions(val width: Int, val height: Int) {
        val area: Int get() = width * height
    }

    /**
     * Pick the auto-exposure target frame-rate range that allows the longest exposure.
     *
     * This is the single most important setting for footage brightness. A range with
     * a high lower bound (e.g. `[30,30]`) forbids the HAL from exposing longer than
     * 1/30 s, which is exactly what yields near-black video indoors or at night.
     *
     * Strategy: keep only ranges the encoder can actually sustain (`upper <=`
     * [TARGET_FPS]), then take the lowest lower bound. Ties go to the higher upper
     * bound so motion stays smooth when there is enough light.
     *
     * @return null when the device reports nothing usable, so the caller can leave
     *   the capture key unset rather than send a fabricated range.
     */
    fun selectAeFpsRange(available: List<Fps>): Fps? {
        if (available.isEmpty()) return null
        val achievable = available.filter { it.upper <= TARGET_FPS }
        // Every range is faster than we want: take the slowest one on offer.
        val pool = achievable.ifEmpty { listOf(available.minByOrNull { it.upper }!!) }
        return pool.sortedWith(compareBy({ it.lower }, { -it.upper })).first()
    }

    /**
     * Choose a recording resolution.
     *
     * Prefers the largest widescreen mode within the 720p cap, then any mode within
     * the cap, then anything up to 1080p. The final fallback is the *smallest*
     * reported size, not the first: `getOutputSizes` returns descending order, so
     * taking the head would pair a multi-megapixel frame with a 1.2 Mbps bitrate and
     * stall or fail the encoder.
     */
    fun selectRecordingSize(sizes: List<Dimensions>): Dimensions {
        if (sizes.isEmpty()) return Dimensions(640, 480)
        return sizes.filter { it.withinCap() && it.isWidescreen() }.maxByOrNull { it.area }
            ?: sizes.filter { it.withinCap() }.maxByOrNull { it.area }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.area }
            ?: sizes.minByOrNull { it.area }
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

    /** Encoder bitrate for a chosen resolution. */
    fun bitrateFor(size: Dimensions): Int = if (size.width >= 1280) 2_500_000 else 1_200_000

    private fun Dimensions.withinCap(): Boolean = width <= MAX_WIDTH && height <= MAX_HEIGHT

    private fun Dimensions.isWidescreen(): Boolean {
        if (height == 0) return false
        val ratio = width.toDouble() / height.toDouble()
        return kotlin.math.abs(ratio - 16.0 / 9.0) < WIDESCREEN_TOLERANCE
    }
}
