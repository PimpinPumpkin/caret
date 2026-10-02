/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This class is a holder of the result of an asynchronous computation.
 *
 * @param <E> the type of the result.
 */
class AsyncResultHolder<E>(tag: String) {

    private val lock = Any()

    private var result: E? = null
    private val tag: String
    private val latch: CountDownLatch

    init {
        this.tag = tag
        latch = CountDownLatch(1)
    }

    /**
     * Sets the result value of this holder.
     *
     * @param result the value to set.
     */
    fun set(result: E?) {
        synchronized(lock) {
            if (latch.count > 0) {
                this.result = result
                latch.countDown()
            }
        }
    }

    /**
     * Gets the result value held in this holder.
     * Causes the current thread to wait unless the value is set or the specified time is elapsed.
     *
     * @param defaultValue the default value.
     * @param timeOut the maximum time to wait.
     * @return if the result is set before the time limit then the result, otherwise defaultValue.
     */
    fun get(defaultValue: E?, timeOut: Long): E? {
        try {
            return if (latch.await(timeOut, TimeUnit.MILLISECONDS)) result else defaultValue
        } catch (e: InterruptedException) {
            Log.w(tag, "get() : Interrupted after " + timeOut + " ms")
            return defaultValue
        }
    }
}
