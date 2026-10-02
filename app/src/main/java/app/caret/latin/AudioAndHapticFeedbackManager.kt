/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

import app.caret.event.HapticEvent
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.common.Constants
import app.caret.latin.settings.SettingsValues

/**
 * This class gathers audio feedback and haptic feedback functions.
 * <p>
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
class AudioAndHapticFeedbackManager private constructor() {
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null

    private var settingsValues: SettingsValues? = null
    private var soundOn = false
    private var doNotDisturb = false

    init {
        // Intentional empty constructor for singleton.
    }

    private fun initInternal(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    }

    fun performHapticAndAudioFeedback(
        code: Int,
        viewToPerformHapticFeedbackOn: View?,
        hapticEvent: HapticEvent
    ) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, hapticEvent)
        performAudioFeedback(code, hapticEvent)
    }

    fun hasVibrator(): Boolean {
        val vib = vibrator
        return vib != null && vib.hasVibrator()
    }

    fun vibrate(milliseconds: Long) {
        val vib = vibrator
        if (vib == null || milliseconds <= 0) {
            return
        }
        vib.vibrate(milliseconds)
    }

    private fun reevaluateIfSoundIsOn(): Boolean {
        val values = settingsValues
        val audio = audioManager
        if (values == null || !values.soundOn || audio == null || doNotDisturb) {
            return false
        }
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    fun performAudioFeedback(code: Int, hapticEvent: HapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        val audio = audioManager
        if (audio == null) {
            return
        }
        if (!soundOn) {
            return
        }
        if (hapticEvent != HapticEvent.KEY_PRESS) {
            return
        }
        val sound = when (code) {
            KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
            Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio.playSoundEffect(sound, settingsValues!!.keypressSoundVolume)
    }

    fun performHapticFeedback(viewToPerformHapticFeedbackOn: View?, hapticEvent: HapticEvent) {
        val values = settingsValues!!
        if (!values.vibrateOn || (doNotDisturb && !values.vibrateInDndMode)) {
            return
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return
        }
        if (hapticEvent.allowCustomDuration && values.keypressVibrationDuration >= 0) {
            vibrate(values.keypressVibrationDuration.toLong())
            return
        }
        // Go ahead with the system default
        if (viewToPerformHapticFeedbackOn != null) {
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    hapticEvent.feedbackConstant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
    }

    fun onSettingsChanged(settingsValues: SettingsValues?) {
        this.settingsValues = settingsValues
        soundOn = reevaluateIfSoundIsOn()
    }

    fun onRingerModeChanged(doNotDisturb: Boolean) {
        this.doNotDisturb = doNotDisturb
        soundOn = reevaluateIfSoundIsOn()
    }

    companion object {
        private val instance = AudioAndHapticFeedbackManager()

        @JvmStatic
        fun getInstance(): AudioAndHapticFeedbackManager {
            return instance
        }

        @JvmStatic
        fun init(context: Context) {
            instance.initInternal(context)
        }
    }
}
