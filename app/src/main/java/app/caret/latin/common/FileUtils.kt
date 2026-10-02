/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.common

import android.content.Context
import android.net.Uri
import app.caret.latin.utils.ExecutorUtils
import java.io.File
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * A simple class to help with removing directories recursively.
 */
class FileUtils {

    companion object {
        @JvmStatic
        fun deleteRecursively(path: File): Boolean {
            if (path.isDirectory()) {
                val files = path.listFiles()
                if (files != null) {
                    for (child in files) {
                        deleteRecursively(child)
                    }
                }
            }
            return path.delete()
        }

        @JvmStatic
        fun deleteFilteredFiles(dir: File, fileNameFilter: FilenameFilter?): Boolean {
            if (!dir.isDirectory()) {
                return false
            }
            val files = dir.listFiles(fileNameFilter)
            if (files == null) {
                return false
            }
            var hasDeletedAllFiles = true
            for (file in files) {
                if (!deleteRecursively(file)) {
                    hasDeletedAllFiles = false
                }
            }
            return hasDeletedAllFiles
        }

        /**
         *  copy data to file on different thread to avoid NetworkOnMainThreadException
         *  still effectively blocking, as we only use small files which are mostly stored locally
         */
        @JvmStatic
        @Throws(IOException::class)
        fun copyContentUriToNewFile(uri: Uri, context: Context, outfile: File) {
            val allOk = booleanArrayOf(true)
            val wait = CountDownLatch(1)
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                try {
                    copyStreamToNewFile(context.getContentResolver().openInputStream(uri), outfile)
                } catch (e: IOException) {
                    allOk[0] = false
                } finally {
                    wait.countDown()
                }
            }
            try {
                wait.await()
            } catch (e: InterruptedException) {
                allOk[0] = false
            }
            if (!allOk[0])
                throw IOException("could not copy from uri")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copyStreamToNewFile(inputStream: InputStream?, outfile: File) {
            val parentFile = outfile.getParentFile()
            if (parentFile == null || (!parentFile.exists() && !parentFile.mkdirs())) {
                throw IOException("could not create parent folder")
            }
            val out = FileOutputStream(outfile)
            copyStreamToOtherStream(inputStream, out)
            out.close()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copyStreamToOtherStream(inputStream: InputStream?, out: OutputStream) {
            val buf = ByteArray(1024)
            var len: Int
            while (inputStream!!.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            out.flush()
        }
    }

}
