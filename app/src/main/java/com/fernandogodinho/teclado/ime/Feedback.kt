package com.fernandogodinho.teclado.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.fernandogodinho.teclado.ime.layout.KeyAction

/** Key press haptics and click sounds. */
class Feedback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun keyDown(view: View, action: KeyAction?, haptics: Boolean, sound: Boolean) {
        if (haptics) {
            val done = view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                @Suppress("DEPRECATION") HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
            if (!done) tick()
        }
        if (sound) {
            val effect = when (action) {
                KeyAction.Backspace -> AudioManager.FX_KEYPRESS_DELETE
                KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyAction.Enter -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(effect, 0.35f)
        }
    }

    fun longPress(view: View, haptics: Boolean) {
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
