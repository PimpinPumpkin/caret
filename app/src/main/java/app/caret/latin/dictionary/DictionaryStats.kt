/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.dictionary

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class DictionaryStats {
    @JvmField
    val locale: Locale?
    @JvmField
    val dictType: String
    @JvmField
    val dictFileName: String?
    @JvmField
    val dictFileSize: Long
    @JvmField
    val contentVersion: Int
    @JvmField
    val wordCount: Int

    constructor(
            locale: Locale?,
            dictType: String,
            dictFileName: String?,
            dictFile: File?,
            contentVersion: Int) {
        this.locale = locale
        this.dictType = dictType
        dictFileSize = if (dictFile == null || !dictFile.exists()) 0L else dictFile.length()
        this.dictFileName = dictFileName
        this.contentVersion = contentVersion
        this.wordCount = -1
    }

    constructor(
            locale: Locale?,
            dictType: String,
            wordCount: Int) {
        this.locale = locale
        this.dictType = dictType
        dictFileSize = wordCount.toLong()
        this.dictFileName = null
        this.contentVersion = 0
        this.wordCount = wordCount
    }

    fun getFileSizeString(): String {
        val bytes = BigDecimal(dictFileSize)
        val kb = bytes.divide(BigDecimal(1024), 2, RoundingMode.HALF_UP)
        if (kb.toLong() == 0L) {
            return bytes.toString() + " bytes"
        }
        val mb = kb.divide(BigDecimal(1024), 2, RoundingMode.HALF_UP)
        if (mb.toLong() == 0L) {
            return kb.toString() + " kb"
        }
        return mb.toString() + " Mb"
    }

    override fun toString(): String {
        val builder = StringBuilder(dictType)
        if (dictType == Dictionary.TYPE_MAIN) {
            builder.append(" (")
            builder.append(contentVersion)
            builder.append(")")
        }
        builder.append(": ")
        if (wordCount > -1) {
            builder.append(wordCount)
            builder.append(" words")
        } else {
            builder.append(dictFileName)
            builder.append(" / ")
            builder.append(getFileSizeString())
        }
        return builder.toString()
    }

    companion object {
        @JvmStatic
        fun toString(stats: Iterable<DictionaryStats>): String {
            val builder = StringBuilder("LM Stats")
            for (stat in stats) {
                builder.append("\n    ")
                builder.append(stat.toString())
            }
            return builder.toString()
        }
    }
}
