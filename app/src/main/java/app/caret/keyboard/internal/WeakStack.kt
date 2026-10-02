// SPDX-License-Identifier: GPL-3.0-only
package app.caret.keyboard.internal

import kotlin.enums.EnumEntries

/// This "weak stack" struct works by packing enum ordinals by half-bytes in a
/// 64-bit integer. Thus, it is not compatible with enums of more than 2⁴ = 16
/// entries.
///
/// Bitwise operations are performed on these half-bytes to create stack-like
/// logic. Because they are stored in a 64-bit integer, the stack has a capacity
/// of 16. When entries are added in excess of this capacity, the oldest entry
/// is forgotten.
///
/// Conversely, the stack has no concept of "item count". Instead, when [#pop()]
/// is called in excess of the entries that were pushed and remembered, the
/// enum's first entry is returned as a fallback value. Thus, the order of
/// constants in the enum definition is significant.
///
/// This struct is intended for use where exact recall is not critical and
/// there's a reasonable default to fall back to when entries are forgotten or
/// absent. This provides a very efficient short-term memory that doesn't crash
/// on over or underflow.
class WeakStack<E : Enum<E>>(entries: EnumEntries<E>) {
    private val entries: EnumEntries<E>
    private var stack: Long = 0x0

    init {
        if (entries.size > 0x10) {
            throw IllegalArgumentException("Enum is too large")
        } // we can be certain the ordinals will fit into half-bytes

        this.entries = entries
    }

    fun push(e: E) {
        stack = stack shl 0x4
        stack = stack or e.ordinal.toLong()
    }

    fun pop(): E {
        val ord = (stack and 0xF).toInt()
        stack = stack ushr 0x4
        return entries.get(ord)
    }

    fun wipe() {
        stack = 0x0
    }
}
