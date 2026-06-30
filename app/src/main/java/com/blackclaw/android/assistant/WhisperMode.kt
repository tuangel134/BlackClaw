package com.blackclaw.android.assistant

import com.blackclaw.android.utils.KVUtils
import kotlin.math.sqrt

/**
 * "Whisper mode" — like Alexa: if the user whispers a command, BlackClaw replies
 * whispering (soft, low-volume TTS).
 *
 * Detection is acoustic: whispered speech is much quieter and lacks the energy
 * peaks of normal voice. The wake engine feeds per-buffer RMS amplitude samples
 * here while it transcribes the command; we average them and, if the speech was
 * consistently quiet (below a calibrated threshold) while still being loud
 * enough to be recognized, we classify it as a whisper.
 *
 * The threshold is adaptive: we track a running estimate of the user's NORMAL
 * speaking level so whisper detection works regardless of mic gain / distance.
 */
object WhisperMode {

    private const val KEY_ENABLED = "whisper_mode_enabled"
    private const val KEY_NORMAL_LEVEL = "whisper_normal_level"

    /** User can disable auto-whisper detection entirely. Default on. */
    var enabled: Boolean
        get() = KVUtils.getBoolean(KEY_ENABLED, true)
        set(v) { KVUtils.putBoolean(KEY_ENABLED, v); KVUtils.sync() }

    // Running estimate of the user's normal (non-whisper) speech RMS.
    private var normalLevel: Double
        get() = KVUtils.getDouble(KEY_NORMAL_LEVEL, 1500.0)
        set(v) { KVUtils.putDouble(KEY_NORMAL_LEVEL, v) }

    // ── Per-utterance RMS accumulation ──
    @Volatile private var sumSq = 0.0
    @Volatile private var sampleCount = 0L
    @Volatile private var peak = 0.0

    /** Reset accumulators at the start of a command utterance. */
    fun beginUtterance() {
        sumSq = 0.0; sampleCount = 0L; peak = 0.0
    }

    /**
     * Feed a PCM16 buffer (mono, 16kHz). Computes RMS over the buffer and
     * accumulates it. Cheap — just sums of squares.
     */
    fun feed(buffer: ShortArray, len: Int) {
        if (len <= 0) return
        var sq = 0.0
        for (i in 0 until len) {
            val s = buffer[i].toDouble()
            sq += s * s
        }
        sumSq += sq
        sampleCount += len
        val rms = sqrt(sq / len)
        if (rms > peak) peak = rms
    }

    /**
     * Decide whether the just-captured utterance was whispered, and update the
     * adaptive normal-level estimate. Pure given the accumulated state.
     */
    fun endUtteranceWasWhisper(): Boolean {
        if (!enabled || sampleCount == 0L) return false
        val meanRms = sqrt(sumSq / sampleCount)
        // Whisper if the utterance is well below the learned normal level AND
        // the peak never reached normal-talking energy.
        val normal = normalLevel
        val whisper = meanRms < normal * 0.45 && peak < normal * 0.9
        if (!whisper) {
            // Adapt the normal-level estimate toward this (normal) utterance.
            normalLevel = (normal * 0.8 + meanRms * 0.2).coerceIn(400.0, 12000.0)
            KVUtils.sync()
        }
        return whisper
    }

    /** Convenience for callers that just want to compute RMS of a buffer. */
    fun rmsOf(buffer: ShortArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sq = 0.0
        for (i in 0 until len) { val s = buffer[i].toDouble(); sq += s * s }
        return sqrt(sq / len)
    }
}
