// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard.internal

import app.caret.latin.utils.CapsModeUtils
import app.caret.latin.utils.RecapitalizeMode

sealed interface LayoutDirective {
    fun mode(): KeyboardState.Mode

    data class Alphabet(
        val shiftMode: ShiftMode,
        val autoCapsFlags: Int,
        val recapitalizeMode: RecapitalizeMode?
    ) : LayoutDirective {
        override fun mode(): KeyboardState.Mode {
            return KeyboardState.Mode.ALPHABET
        }

        override fun toString(): String {
            return "Alphabet{shiftMode=" + shiftMode +
                 ", autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags) +
                 ", recapitalizeMode=" + recapitalizeMode +
                 '}'
        }
    }

    enum class Utility(private val mMode: KeyboardState.Mode) : LayoutDirective {
        SYMBOLS(KeyboardState.Mode.SYMBOLS),
        SYMBOLS_SHIFTED(KeyboardState.Mode.SYMBOLS_SHIFTED),
        EMOJI(KeyboardState.Mode.EMOJI),
        CLIPBOARD(KeyboardState.Mode.CLIPBOARD),
        NUMPAD(KeyboardState.Mode.NUMPAD),
        DPAD(KeyboardState.Mode.DPAD),
    ;

        override fun mode(): KeyboardState.Mode {
            return mMode
        }
    }
}
