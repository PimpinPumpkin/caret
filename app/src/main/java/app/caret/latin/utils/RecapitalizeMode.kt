package app.caret.latin.utils

import java.util.Locale

import app.caret.latin.common.StringUtils

enum class RecapitalizeMode {
    ORIGINAL_MIXED_CASE {
        override fun apply(text: String, sortedSeparators: IntArray, locale: Locale): String {
            return text
        }
    },
    ALL_LOWER {
        override fun apply(text: String, sortedSeparators: IntArray, locale: Locale): String {
            return text.lowercase(locale)
        }
    },
    FIRST_WORD_UPPER {
        override fun apply(text: String, sortedSeparators: IntArray, locale: Locale): String {
            return StringUtils.capitalizeEachWord(text, sortedSeparators, locale)
        }
    },
    ALL_UPPER {
        override fun apply(text: String, sortedSeparators: IntArray, locale: Locale): String {
            return text.uppercase(locale)
        }
    };

    fun rotate(skipOriginalMixedCaseMode: Boolean): RecapitalizeMode {
        var position = ordinal + 1
        if (position == carousel.size) {
            position = if (skipOriginalMixedCaseMode) 1 else 0
        }
        return carousel[position]
    }

    abstract fun apply(text: String, sortedSeparators: IntArray, locale: Locale): String

    companion object {
        private var carousel = RecapitalizeMode.values()

        @JvmStatic
        fun of(string: String, sortedSeparators: IntArray): RecapitalizeMode {
            if (StringUtils.isIdenticalAfterUpcase(string)) {
                return ALL_UPPER
            } else if (StringUtils.isIdenticalAfterDowncase(string)) {
                return ALL_LOWER
            } else if (StringUtils.isIdenticalAfterCapitalizeEachWord(string, sortedSeparators)) {
                return FIRST_WORD_UPPER
            } else {
                return ORIGINAL_MIXED_CASE
            }
        }

        @JvmStatic
        fun count(): Int {
            return carousel.size
        }
    }
}
