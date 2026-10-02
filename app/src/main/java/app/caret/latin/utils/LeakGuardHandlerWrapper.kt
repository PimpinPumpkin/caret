/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import android.os.Handler
import android.os.Looper

import java.lang.ref.WeakReference

open class LeakGuardHandlerWrapper<T> : Handler {
    private val ownerInstanceRef: WeakReference<T>

    constructor(ownerInstance: T) : this(ownerInstance, Looper.myLooper())

    constructor(ownerInstance: T, looper: Looper?) : super(looper!!) {
        ownerInstanceRef = WeakReference(ownerInstance)
    }

    fun getOwnerInstance(): T? {
        return ownerInstanceRef.get()
    }
}
