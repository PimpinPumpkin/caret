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
    private var mAudioManager: AudioManager? = null
    private var mVibrator: Vibrator? = null

    private var mSettingsValues: SettingsValues? = null
    private var soundOn = false
    private var mDoNotDisturb = false

    init {
        // Intentional empty constructor for singleton.
    }

    private fun initInternal(context: Context) {
        mAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        mVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
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
        val vibrator = mVibrator
        return vibrator != null && vibrator.hasVibrator()
    }

    fun vibrate(milliseconds: Long) {
        val vibrator = mVibrator
        if (vibrator == null || milliseconds <= 0) {
            return
        }
        vibrator.vibrate(milliseconds)
    }

    private fun reevaluateIfSoundIsOn(): Boolean {
        val settingsValues = mSettingsValues
        val audioManager = mAudioManager
        if (settingsValues == null || !settingsValues.soundOn || audioManager == null || mDoNotDisturb) {
            return false
        }
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    fun performAudioFeedback(code: Int, hapticEvent: HapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        val audioManager = mAudioManager
        if (audioManager == null) {
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
        audioManager.playSoundEffect(sound, mSettingsValues!!.keypressSoundVolume)
    }

    fun performHapticFeedback(viewToPerformHapticFeedbackOn: View?, hapticEvent: HapticEvent) {
        val settingsValues = mSettingsValues!!
        if (!settingsValues.vibrateOn || (mDoNotDisturb && !settingsValues.vibrateInDndMode)) {
            return
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return
        }
        if (hapticEvent.allowCustomDuration && settingsValues.keypressVibrationDuration >= 0) {
            vibrate(settingsValues.keypressVibrationDuration.toLong())
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
        mSettingsValues = settingsValues
        soundOn = reevaluateIfSoundIsOn()
    }

    fun onRingerModeChanged(doNotDisturb: Boolean) {
        mDoNotDisturb = doNotDisturb
        soundOn = reevaluateIfSoundIsOn()
    }

    companion object {
        private val sInstance = AudioAndHapticFeedbackManager()

        @JvmStatic
        fun getInstance(): AudioAndHapticFeedbackManager {
            return sInstance
        }

        @JvmStatic
        fun init(context: Context) {
            sInstance.initInternal(context)
        }
    }
}
