/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin

import android.text.TextUtils

import app.caret.event.Event
import app.caret.latin.common.InputPointers
import app.caret.latin.define.DecoderSpecificConstants

import java.util.ArrayList

/**
 * This class encapsulates data about a word previously composed, but that has been
 * committed already. This is used for resuming suggestion, and cancel auto-correction.
 */
class LastComposedWord {
    @JvmField val events: ArrayList<Event>
    @JvmField val typedWord: String
    @JvmField val committedWord: CharSequence
    @JvmField val separatorString: String
    @JvmField val ngramContext: NgramContext?
    @JvmField val capitalizedMode: CapsMode?
    @JvmField val inputPointers =
            InputPointers(DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH)

    private var active: Boolean

    // Warning: this is using the passed objects as is and fully expects them to be
    // immutable. Do not fiddle with their contents after you passed them to this constructor.
    constructor(events: ArrayList<Event>, inputPointers: InputPointers?, typedWord: String,
            committedWord: CharSequence, separatorString: String, ngramContext: NgramContext?, capitalizedMode: CapsMode?) {
        if (inputPointers != null) {
            this.inputPointers.copy(inputPointers)
        }
        this.typedWord = typedWord
        this.events = ArrayList(events)
        this.committedWord = committedWord
        this.separatorString = separatorString
        active = true
        this.ngramContext = ngramContext
        this.capitalizedMode = capitalizedMode
    }

    fun deactivate() {
        active = false
    }

    fun canRevertCommit(): Boolean {
        return active && !TextUtils.isEmpty(committedWord) && !didCommitTypedWord()
    }

    private fun didCommitTypedWord(): Boolean {
        return TextUtils.equals(typedWord, committedWord)
    }

    companion object {
        // COMMIT_TYPE_USER_TYPED_WORD is used when the word committed is the exact typed word, with
        // no hinting from the IME. It happens when some external event happens (rotating the device,
        // for example) or when auto-correction is off by settings or editor attributes.
        const val COMMIT_TYPE_USER_TYPED_WORD = 0
        // COMMIT_TYPE_MANUAL_PICK is used when the user pressed a field in the suggestion strip.
        const val COMMIT_TYPE_MANUAL_PICK = 1
        // COMMIT_TYPE_DECIDED_WORD is used when the IME commits the word it decided was best
        // for the current user input. It may be different from what the user typed (true auto-correct)
        // or it may be exactly what the user typed if it's in the dictionary or the IME does not have
        // enough confidence in any suggestion to auto-correct (auto-correct to typed word).
        const val COMMIT_TYPE_DECIDED_WORD = 2
        // COMMIT_TYPE_CANCEL_AUTO_CORRECT is used upon committing back the old word upon cancelling
        // an auto-correction.
        const val COMMIT_TYPE_CANCEL_AUTO_CORRECT = 3

        const val NOT_A_SEPARATOR = ""

        @JvmField
        val NOT_A_COMPOSED_WORD =
                LastComposedWord(ArrayList<Event>(), null, "", "",
                NOT_A_SEPARATOR, null, CapsMode.OFF)
    }
}
