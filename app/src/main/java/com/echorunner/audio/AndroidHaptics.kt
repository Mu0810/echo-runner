package com.echorunner.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.echorunner.core.HapticEvent
import com.echorunner.core.Haptics

/**
 * Vibration for the four moments that earn one.
 *
 * Resolution is version-split: `VibratorManager` on API 31+, the deprecated
 * `Context.VIBRATOR_SERVICE` lookup below it. Every effect is built once in the constructor —
 * `VibrationEffect` is immutable, and building one inside [fire] would allocate on a gameplay
 * event.
 *
 * Nothing here may throw. A device with no vibrator, a user who revoked the pattern, an OEM
 * that rejects an amplitude — all of them degrade to silence, never to a crash.
 */
class AndroidHaptics(context: Context) : Haptics {

    private companion object {
        const val TAG = "AndroidHaptics"

        /** No repeat: every one of our patterns is a one-shot. */
        const val NO_REPEAT = -1
    }

    private val vibrator: Vibrator? = resolveVibrator(context)

    /**
     * Amplitude-controlled patterns are only honoured on hardware that supports them; elsewhere
     * the same timings run at the device default so the *rhythm* still carries the meaning.
     */
    private val hasAmplitudeControl: Boolean = try {
        vibrator?.hasAmplitudeControl() == true
    } catch (e: RuntimeException) {
        false
    }

    /** Barely-there tick for UI and light feedback. */
    private val tapEffect: VibrationEffect? = oneShot(12L, 90)

    /** Two beats — the take you just recorded, and the copy that will now replay it. */
    private val echoCreatedEffect: VibrationEffect? =
        waveform(longArrayOf(0L, 18L, 70L, 30L), intArrayOf(0, 110, 0, 200))

    /** One sharp buzz: blunt on purpose. */
    private val deathEffect: VibrationEffect? = oneShot(55L, 255)

    /** Two pulses, the second longer and stronger, so it reads as rising. */
    private val levelCompleteEffect: VibrationEffect? =
        waveform(longArrayOf(0L, 22L, 60L, 46L), intArrayOf(0, 120, 0, 240))

    @Volatile private var enabled = true

    override fun fire(event: HapticEvent) {
        if (!enabled) return
        val device = vibrator ?: return
        val effect = when (event) {
            HapticEvent.LIGHT_TAP -> tapEffect
            HapticEvent.ECHO_CREATED -> echoCreatedEffect
            HapticEvent.DEATH -> deathEffect
            HapticEvent.LEVEL_COMPLETE -> levelCompleteEffect
        } ?: return
        try {
            @Suppress("DEPRECATION") // the VibrationAttributes overload is API 33+
            device.vibrate(effect)
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibrate failed for $event", e)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            // Cancel in flight too, or turning haptics off mid-pattern still buzzes.
            try {
                vibrator?.cancel()
            } catch (e: RuntimeException) {
                Log.w(TAG, "cancel failed", e)
            }
        }
    }

    private fun oneShot(millis: Long, amplitude: Int): VibrationEffect? = try {
        VibrationEffect.createOneShot(
            millis,
            if (hasAmplitudeControl) amplitude else VibrationEffect.DEFAULT_AMPLITUDE,
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not build a one-shot effect", e)
        null
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect? = try {
        if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(timings, NO_REPEAT)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not build a waveform effect", e)
        null
    }

    /** @return the default vibrator, or null when the device has none at all. */
    private fun resolveVibrator(context: Context): Vibrator? = try {
        val app = context.applicationContext
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (device != null && device.hasVibrator()) device else null
    } catch (e: RuntimeException) {
        Log.w(TAG, "no usable vibrator on this device", e)
        null
    }
}
