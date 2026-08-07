package com.blackclaw.android.emergency

import com.blackclaw.android.emergency.EmergencyCameraTuning.Dimensions
import com.blackclaw.android.emergency.EmergencyCameraTuning.Fps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the recording-quality decisions that determine whether emergency footage is
 * usable at all. Two of these were the cause of black and empty video files: the AE
 * target FPS range was never set, and the resolution fallback could hand the encoder
 * a multi-megapixel frame.
 */
class EmergencyCameraTuningTest {

    // ── AE target FPS range: the 60-fps decision ──────────────────────────────

    @Test fun `prefers an exact sixty fps sensor range`() {
        val available = listOf(Fps(30, 30), Fps(30, 60), Fps(60, 60))
        assertEquals(Fps(60, 60), EmergencyCameraTuning.selectAeFpsRange(available))
    }

    @Test fun `selects a variable range that includes sixty fps when fixed is absent`() {
        val available = listOf(Fps(30, 30), Fps(15, 60), Fps(30, 60))
        assertEquals(Fps(30, 60), EmergencyCameraTuning.selectAeFpsRange(available))
    }

    @Test fun `falls back to the highest real rate below sixty`() {
        val available = listOf(Fps(7, 24), Fps(15, 30), Fps(30, 30))
        assertEquals(Fps(30, 30), EmergencyCameraTuning.selectAeFpsRange(available))
    }

    @Test fun `uses the lowest reported rate when every option is faster than sixty`() {
        val available = listOf(Fps(120, 120), Fps(240, 240))
        assertEquals(Fps(120, 120), EmergencyCameraTuning.selectAeFpsRange(available))
    }

    @Test fun `returns null rather than fabricating a range when nothing is reported`() {
        assertNull(EmergencyCameraTuning.selectAeFpsRange(emptyList()))
    }

    @Test fun `accepts a single fixed range`() {
        assertEquals(Fps(24, 24), EmergencyCameraTuning.selectAeFpsRange(listOf(Fps(24, 24))))
    }

    // ── Recording resolution ──────────────────────────────────────────────────

    @Test fun `picks the largest widescreen size within the cap`() {
        val sizes = listOf(Dimensions(1920, 1080), Dimensions(1280, 720), Dimensions(640, 480))
        assertEquals(Dimensions(1920, 1080), EmergencyCameraTuning.selectRecordingSize(sizes))
    }

    @Test fun `tolerates sensors that are only approximately sixteen by nine`() {
        // 1024x600 is 1.706 against 1.778 — an exact ratio test would reject it.
        val sizes = listOf(Dimensions(1024, 600), Dimensions(320, 240))
        assertEquals(Dimensions(1024, 600), EmergencyCameraTuning.selectRecordingSize(sizes))
    }

    @Test fun `accepts a non-widescreen size when nothing widescreen fits the cap`() {
        val sizes = listOf(Dimensions(1024, 768), Dimensions(640, 480))
        assertEquals(Dimensions(1024, 768), EmergencyCameraTuning.selectRecordingSize(sizes))
    }

    @Test fun `falls back to the smallest size rather than the first reported one`() {
        // getOutputSizes returns descending order, so only a documented 4K60
        // profile may exceed Full HD. The generic selector must stay stable.
        val sizes = listOf(Dimensions(4000, 3000), Dimensions(2560, 1920))
        assertEquals(Dimensions(2560, 1920), EmergencyCameraTuning.selectRecordingSize(sizes))
    }

    @Test fun `uses a safe default when the device reports no sizes at all`() {
        assertEquals(Dimensions(640, 480), EmergencyCameraTuning.selectRecordingSize(emptyList()))
    }

    @Test fun `never returns a zero-height size`() {
        val sizes = listOf(Dimensions(1280, 0), Dimensions(640, 480))
        assertEquals(Dimensions(640, 480), EmergencyCameraTuning.selectRecordingSize(sizes))
    }

    // ── Bitrate ───────────────────────────────────────────────────────────────

    @Test fun `bitrate scales with resolution`() {
        assertEquals(22_000_000, EmergencyCameraTuning.bitrateFor(Dimensions(1920, 1080)))
        assertEquals(5_000_000, EmergencyCameraTuning.bitrateFor(Dimensions(640, 480)))
    }

    // ── MP4 orientation hint ──────────────────────────────────────────────────

    @Test fun `rear camera subtracts device rotation`() {
        assertEquals(90, EmergencyCameraTuning.orientationHint(90, 0, front = false))
        assertEquals(0, EmergencyCameraTuning.orientationHint(90, 90, front = false))
        assertEquals(270, EmergencyCameraTuning.orientationHint(90, 180, front = false))
    }

    @Test fun `front camera adds device rotation so selfies are not upside down`() {
        assertEquals(270, EmergencyCameraTuning.orientationHint(270, 0, front = true))
        assertEquals(0, EmergencyCameraTuning.orientationHint(270, 90, front = true))
        assertEquals(90, EmergencyCameraTuning.orientationHint(270, 180, front = true))
    }

    @Test fun `front and rear diverge at quarter turns`() {
        // rear = (s - r) mod 360, front = (s + r) mod 360, so the two coincide
        // exactly when 2r is a multiple of 360 — that is, at 0 and 180 degrees.
        listOf(90, 270).forEach { rotation ->
            val rear = EmergencyCameraTuning.orientationHint(90, rotation, front = false)
            val front = EmergencyCameraTuning.orientationHint(90, rotation, front = true)
            assertTrue("rotation=$rotation produced identical hints", rear != front)
        }
        listOf(0, 180).forEach { rotation ->
            assertEquals(
                EmergencyCameraTuning.orientationHint(90, rotation, front = false),
                EmergencyCameraTuning.orientationHint(90, rotation, front = true),
            )
        }
    }

    @Test fun `orientation hint always lands inside a single rotation`() {
        listOf(-90, 0, 90, 180, 270, 360, 450).forEach { sensor ->
            listOf(0, 90, 180, 270).forEach { rotation ->
                listOf(true, false).forEach { front ->
                    val hint = EmergencyCameraTuning.orientationHint(sensor, rotation, front)
                    assertTrue(
                        "sensor=$sensor rotation=$rotation front=$front hint=$hint",
                        hint in 0..359,
                    )
                }
            }
        }
    }
}
