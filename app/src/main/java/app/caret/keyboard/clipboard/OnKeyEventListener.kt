// SPDX-License-Identifier: GPL-3.0-only

package app.caret.keyboard.clipboard

interface OnKeyEventListener {

    fun onKeyDown(clipId: Long)

    fun onKeyUp(clipId: Long)

}