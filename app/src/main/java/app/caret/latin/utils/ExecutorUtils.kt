/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.utils

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Utilities to manage executors.
 */
class ExecutorUtils {

    private class ExecutorFactory(name: String) : ThreadFactory {
        private val name: String

        init {
            this.name = name
        }

        override fun newThread(runnable: Runnable): Thread {
            val thread = Thread(runnable, TAG)
            thread.setUncaughtExceptionHandler { thread1, ex ->
                    Log.w(name, runnable.javaClass.getSimpleName(), ex) }
            return thread
        }
    }

    // The constructor was private in Java; Kotlin does not let the outer class call a nested
    // class's private constructor, so it is public here.
    class RunnableChain(vararg runnables: Runnable) : Runnable {
        val runnables: Array<out Runnable>

        init {
            if (runnables.size == 0) {
                throw IllegalArgumentException("Attempting to construct an empty chain")
            }
            this.runnables = runnables
        }

        override fun run() {
            for (runnable in runnables) {
                if (Thread.interrupted()) {
                    return
                }
                runnable.run()
            }
        }
    }

    companion object {
        private const val TAG = "ExecutorUtils"

        const val KEYBOARD = "Keyboard"
        const val SPELLING = "Spelling"

        private var keyboardExecutorService: ScheduledExecutorService = newExecutorService(KEYBOARD)
        private var spellingExecutorService: ScheduledExecutorService = newExecutorService(SPELLING)

        private fun newExecutorService(name: String): ScheduledExecutorService {
            // use more than a single thread, to reduce the occasional wait (mostly relevant when using multiple languages)
            // limit number to cores / 2 to never interfere with whatever some other app is doing
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = Math.max(cores / 2, 1)
            return Executors.newScheduledThreadPool(threads, ExecutorFactory(name))
        }

        private var executorServiceForTests: ScheduledExecutorService? = null

        @JvmStatic
        fun setExecutorServiceForTests(
                executorServiceForTests: ScheduledExecutorService?) {
            this.executorServiceForTests = executorServiceForTests
        }

        //
        // Public methods used to schedule a runnable for execution.
        //

        /**
         * @param name Executor's name.
         * @return scheduled executor service used to run background tasks
         */
        @JvmStatic
        fun getBackgroundExecutor(name: String): ScheduledExecutorService {
            executorServiceForTests?.let { return it }
            return when (name) {
                KEYBOARD -> keyboardExecutorService
                SPELLING -> spellingExecutorService
                else -> throw IllegalArgumentException("Invalid executor: " + name)
            }
        }

        @JvmStatic
        fun killTasks(name: String) {
            val executorService = getBackgroundExecutor(name)
            executorService.shutdownNow()
            try {
                //noinspection ResultOfMethodCallIgnored
                executorService.awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.wtf(TAG, "Failed to shut down: " + name)
            }
            if (executorService === executorServiceForTests) {
                // Don't do anything to the test service.
                return
            }
            when (name) {
                KEYBOARD ->
                    keyboardExecutorService = newExecutorService(KEYBOARD)
                SPELLING ->
                    spellingExecutorService = newExecutorService(SPELLING)
                else ->
                    throw IllegalArgumentException("Invalid executor: " + name)
            }
        }

        @JvmStatic
        fun chain(vararg runnables: Runnable): Runnable {
            return RunnableChain(*runnables)
        }
    }
}
