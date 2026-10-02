// SPDX-License-Identifier: GPL-3.0-only

package app.caret.latin.common

import app.caret.latin.common.StringUtils.mightBeEmoji
import app.caret.latin.common.StringUtils.newSingleCodePointString
import app.caret.latin.settings.SpacingAndPunctuations
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.SpacedTokens
import app.caret.latin.utils.SpannableStringUtils
import app.caret.latin.utils.TextRange
import java.math.BigInteger
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.text.indexOfFirst
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import java.util.Arrays

object StringUtils {

    const val CAPITALIZE_NONE = 0 // No caps, or mixed case
    const val CAPITALIZE_FIRST = 1 // First only
    const val CAPITALIZE_ALL = 2 // All caps

    private const val CHAR_LINE_FEED = '\u000A'
    private const val CHAR_VERTICAL_TAB = '\u000B'
    private const val CHAR_FORM_FEED = '\u000C'
    private const val CHAR_CARRIAGE_RETURN = '\u000D'
    private const val CHAR_NEXT_LINE = '\u0085'
    private const val CHAR_LINE_SEPARATOR = ' '
    private const val CHAR_PARAGRAPH_SEPARATOR = ' '

    // This utility class is not publicly instantiable.

    // Taken from android.text.TextUtils. We are extensively using this method in many places,
    // some of which don't have the android libraries available.

    /**
     * Returns true if the string is null or 0-length.
     *
     * @param str the string to be examined
     * @return true if str is null or zero length
     */
    @JvmStatic
    fun isEmpty(str: CharSequence?): Boolean {
        return (str == null || str.length == 0)
    }

    @JvmStatic
    fun codePointCount(text: CharSequence?): Int {
        if (text == null || isEmpty(text)) {
            return 0
        }
        return Character.codePointCount(text, 0, text.length)
    }

    @JvmStatic
    fun newSingleCodePointString(codePoint: Int): String {
        if (Character.charCount(codePoint) == 1) {
            // Optimization: avoid creating a temporary array for characters that are
            // represented by a single char value
            return codePoint.toChar().toString()
        }
        // For surrogate pair
        return String(Character.toChars(codePoint))
    }

    @JvmStatic
    fun capitalizeFirstCodePoint(s: String, locale: Locale): String {
        if (s.length <= 1) {
            return s.uppercase(getLocaleUsedForToTitleCase(locale))
        }
        // Please refer to the comment below in
        // {@link #capitalizeFirstAndDowncaseRest(String,Locale)} as this has the same shortcomings
        val cutoff = s.offsetByCodePoints(0, 1)
        return s.substring(0, cutoff).uppercase(getLocaleUsedForToTitleCase(locale)) +
                s.substring(cutoff)
    }

    @JvmStatic
    fun capitalizeFirstAndDowncaseRest(s: String, locale: Locale): String {
        if (s.length <= 1) {
            return s.uppercase(getLocaleUsedForToTitleCase(locale))
        }
        // TODO: fix the bugs below
        // - It does not work for Serbian, because it fails to account for the "lj" character,
        // which should be "Lj" in title case and "LJ" in upper case.
        // - It does not work for Dutch, because it fails to account for the "ij" digraph when it's
        // written as two separate code points. They are two different characters but both should
        // be capitalized as "IJ" as if they were a single letter in most words (not all). If the
        // unicode char for the ligature is used however, it works.
        val cutoff = s.offsetByCodePoints(0, 1)
        return s.substring(0, cutoff).uppercase(getLocaleUsedForToTitleCase(locale)) +
                s.substring(cutoff).lowercase(locale)
    }

    @JvmStatic
    fun toCodePointArray(charSequence: CharSequence): IntArray {
        return toCodePointArray(charSequence, 0, charSequence.length)
    }

    private val EMPTY_CODEPOINTS = intArrayOf()

    /**
     * Converts a range of a string to an array of code points.
     *
     * @param charSequence the source string.
     * @param startIndex   the start index inside the string in java chars, inclusive.
     * @param endIndex     the end index inside the string in java chars, exclusive.
     * @return a new array of code points. At most endIndex - startIndex, but possibly less.
     */
    @JvmStatic
    fun toCodePointArray(charSequence: CharSequence, startIndex: Int, endIndex: Int): IntArray {
        val length = charSequence.length
        if (length <= 0) {
            return EMPTY_CODEPOINTS
        }
        val codePoints = IntArray(Character.codePointCount(charSequence, startIndex, endIndex))
        copyCodePointsAndReturnCodePointCount(codePoints, charSequence, startIndex, endIndex,
                false /* downCase */)
        return codePoints
    }

    /**
     * Copies the codepoints in a CharSequence to an int array.
     * <p>
     * This method assumes there is enough space in the array to store the code points. The size
     * can be measured with Character#codePointCount(CharSequence, int, int) before passing to this
     * method. If the int array is too small, an ArrayIndexOutOfBoundsException will be thrown.
     * Also, this method makes no effort to be thread-safe. Do not modify the CharSequence while
     * this method is running, or the behavior is undefined.
     * This method can optionally downcase code points before copying them, but it pays no attention
     * to locale while doing so.
     *
     * @param destination  the int array.
     * @param charSequence the CharSequence.
     * @param startIndex   the start index inside the string in java chars, inclusive.
     * @param endIndex     the end index inside the string in java chars, exclusive.
     * @param downCase     if this is true, code points will be downcased before being copied.
     * @return the number of copied code points.
     */
    @JvmStatic
    fun copyCodePointsAndReturnCodePointCount(destination: IntArray,
                                              charSequence: CharSequence, startIndex: Int, endIndex: Int,
                                              downCase: Boolean): Int {
        var destIndex = 0
        var index = startIndex
        while (index < endIndex) {
            val codePoint = Character.codePointAt(charSequence, index)
            // TODO: stop using this, as it's not aware of the locale and does not always do
            // the right thing.
            destination[destIndex] = if (downCase) Character.toLowerCase(codePoint) else codePoint
            destIndex++
            index = Character.offsetByCodePoints(charSequence, index, 1)
        }
        return destIndex
    }

    @JvmStatic
    fun toSortedCodePointArray(string: String): IntArray {
        val codePoints = toCodePointArray(string)
        Arrays.sort(codePoints)
        return codePoints
    }

    /**
     * Construct a String from a code point array
     *
     * @param codePoints a code point array that is null terminated when its logical length is
     *                   shorter than the array length.
     * @return a string constructed from the code point array.
     */
    @JvmStatic
    fun getStringFromNullTerminatedCodePointArray(codePoints: IntArray): String {
        var stringLength = codePoints.size
        for (i in 0 until codePoints.size) {
            if (codePoints[i] == 0) {
                stringLength = i
                break
            }
        }
        return String(codePoints, 0 /* offset */, stringLength)
    }

    // This method assumes the text is not null. For the empty string, it returns CAPITALIZE_NONE.
    @JvmStatic
    fun getCapitalizationType(text: String): Int {
        // If the first char is not uppercase, then the word is either all lower case or
        // camel case, and in either case we return CAPITALIZE_NONE.
        val len = text.length
        var index = 0
        while (index < len) {
            if (Character.isLetter(text.codePointAt(index))) {
                break
            }
            index = text.offsetByCodePoints(index, 1)
        }
        if (index == len) return CAPITALIZE_NONE
        if (!Character.isUpperCase(text.codePointAt(index))) {
            return CAPITALIZE_NONE
        }
        var capsCount = 1
        var letterCount = 1
        index = text.offsetByCodePoints(index, 1)
        while (index < len) {
            if (1 != capsCount && letterCount != capsCount) break
            val codePoint = text.codePointAt(index)
            if (Character.isUpperCase(codePoint)) {
                ++capsCount
                ++letterCount
            } else if (Character.isLetter(codePoint)) {
                // We need to discount non-letters since they may not be upper-case, but may
                // still be part of a word (e.g. single quote or dash, as in "IT'S" or "FULL-TIME")
                ++letterCount
            }
            index = text.offsetByCodePoints(index, 1)
        }
        // We know the first char is upper case. So we want to test if either every letter other
        // than the first is lower case, or if they are all upper case. If the string is exactly
        // one char long, then we will arrive here with letterCount 1, and this is correct, too.
        if (1 == capsCount) return CAPITALIZE_FIRST
        return (if (letterCount == capsCount) CAPITALIZE_ALL else CAPITALIZE_NONE)
    }

    @JvmStatic
    fun isIdenticalAfterUpcase(text: String): Boolean {
        val length = text.length
        var i = 0
        while (i < length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetter(codePoint) && !Character.isUpperCase(codePoint)) {
                return false
            }
            i += Character.charCount(codePoint)
        }
        return true
    }

    @JvmStatic
    fun isIdenticalAfterDowncase(text: String): Boolean {
        val length = text.length
        var i = 0
        while (i < length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetter(codePoint) && !Character.isLowerCase(codePoint)) {
                return false
            }
            i += Character.charCount(codePoint)
        }
        return true
    }

    @JvmStatic
    fun isIdenticalAfterCapitalizeEachWord(text: String, sortedSeparators: IntArray): Boolean {
        var needsCapsNext = true
        val len = text.length
        var i = 0
        while (i < len) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetter(codePoint)) {
                if ((needsCapsNext && !Character.isUpperCase(codePoint))
                        || (!needsCapsNext && !Character.isLowerCase(codePoint))) {
                    return false
                }
            }
            // We need a capital letter next if this is a separator.
            needsCapsNext = (Arrays.binarySearch(sortedSeparators, codePoint) >= 0)
            i = text.offsetByCodePoints(i, 1)
        }
        return true
    }

    // TODO: like capitalizeFirst*, this does not work perfectly for Dutch because of the IJ digraph
    // which should be capitalized together in *some* cases.
    @JvmStatic
    fun capitalizeEachWord(text: String, sortedSeparators: IntArray, locale: Locale): String {
        val builder = StringBuilder()
        var needsCapsNext = true
        val len = text.length
        var i = 0
        while (i < len) {
            val nextChar = text.substring(i, text.offsetByCodePoints(i, 1))
            if (needsCapsNext) {
                builder.append(nextChar.uppercase(locale))
            } else {
                builder.append(nextChar.lowercase(locale))
            }
            // We need a capital letter next if this is a separator.
            needsCapsNext = (Arrays.binarySearch(sortedSeparators, nextChar.codePointAt(0)) >= 0)
            i = text.offsetByCodePoints(i, 1)
        }
        return builder.toString()
    }

    /**
     * Approximates whether the text before the cursor looks like a URL.
     * <p>
     * This is not foolproof, but it should work well in the practice.
     * Essentially it walks backward from the cursor until it finds something that's not a letter,
     * digit, or common URL symbol like underscore. If it hasn't found a period yet, then it
     * does not look like a URL.
     * If the text:
     * - starts with www and contains a period
     * - starts with a slash preceded by either a slash, whitespace, or start-of-string
     * Then it looks like a URL and we return true. Otherwise, we return false.
     * <p>
     * Note: this method is called quite often, and should be fast.
     * <p>
     * TODO: This will return that "abc./def" and ".abc/def" look like URLs to keep down the
     * code complexity, but ideally it should not. It's acceptable for now.
     */
    @JvmStatic
    fun lastPartLooksLikeURL(text: CharSequence): Boolean {
        var i = text.length
        if (0 == i) {
            return false
        }
        var wCount = 0
        var slashCount = 0
        var hasSlash = false
        var hasPeriod = false
        var codePoint = 0
        while (i > 0) {
            codePoint = Character.codePointBefore(text, i)
            if (codePoint < Constants.CODE_PERIOD || codePoint > 'z'.code) {
                // Handwavy heuristic to see if that's a URL character. Anything between period
                // and z. This includes all lower- and upper-case ascii letters, period,
                // underscore, arrobase, question mark, equal sign. It excludes spaces, exclamation
                // marks, double quotes...
                // Anything that's not a URL-like character causes us to break from here and
                // evaluate normally.
                break
            }
            if (Constants.CODE_PERIOD == codePoint) {
                hasPeriod = true
            }
            if (Constants.CODE_SLASH == codePoint) {
                hasSlash = true
                if (2 == ++slashCount) {
                    return true
                }
            } else {
                slashCount = 0
            }
            if ('w'.code == codePoint) {
                ++wCount
            } else {
                wCount = 0
            }
            i = Character.offsetByCodePoints(text, i, -1)
        }
        // End of the text run.
        // If it starts with www and includes a period, then it looks like a URL.
        if (wCount >= 3 && hasPeriod) {
            return true
        }
        // If it starts with a slash, and the code point before is whitespace, it looks like an URL.
        if (1 == slashCount && (0 == i || Character.isWhitespace(codePoint))) {
            return true
        }
        // If it has both a period and a slash, it looks like an URL.
        return hasPeriod && hasSlash
        // Otherwise, it doesn't look like an URL.
    }

    /**
     * Examines the string and returns whether we're inside a double quote.
     * <p>
     * This is used to decide whether we should put an automatic space before or after a double
     * quote character. If we're inside a quotation, then we want to close it, so we want a space
     * after and not before. Otherwise, we want to open the quotation, so we want a space before
     * and not after. Exception: after a digit, we never want a space because the "inch" or
     * "minutes" use cases is dominant after digits.
     * In the practice, we determine whether we are in a quotation or not by finding the previous
     * double quote character, and looking at whether it's followed by whitespace. If so, that
     * was a closing quotation mark, so we're not inside a double quote. If it's not followed
     * by whitespace, then it was an opening quotation mark, and we're inside a quotation.
     * However, on the way to the double quote we can determine, some double quotes might be
     * ignored, e.g. because they are followed by punctuation. These double quotes are counted and
     * taken into account.
     *
     * @param text the text to examine.
     * @return whether we're inside a double quote.
     */
    @JvmStatic
    fun isInsideDoubleQuoteOrAfterDigit(text: CharSequence): Boolean {
        var i = text.length
        if (0 == i) {
            return false
        }
        var codePoint = Character.codePointBefore(text, i)
        if (Character.isDigit(codePoint)) {
            return true
        }
        var prevCodePoint = 0
        var ignoredDoubleQuoteCount = 0
        while (i > 0) {
            codePoint = Character.codePointBefore(text, i)
            if (Constants.CODE_DOUBLE_QUOTE == codePoint) {
                // If we see a double quote followed by whitespace, then that
                // was a closing quote.
                if (Character.isWhitespace(prevCodePoint)) {
                    return ignoredDoubleQuoteCount % 2 == 1
                }
            }
            if (Character.isWhitespace(codePoint) && Constants.CODE_DOUBLE_QUOTE == prevCodePoint) {
                // If we see a double quote preceded by whitespace, then that
                // was an opening quote. No need to continue seeking.
                return ignoredDoubleQuoteCount % 2 == 0
            }
            if (Constants.CODE_DOUBLE_QUOTE == prevCodePoint) {
                ignoredDoubleQuoteCount++
            }
            i -= Character.charCount(codePoint)
            prevCodePoint = codePoint
        }
        // We reached the start of text. If the first char is a double quote, then we're inside
        // a double quote. Otherwise we're not.
        if (ignoredDoubleQuoteCount % 2 == 0)
            return Constants.CODE_DOUBLE_QUOTE == codePoint
        else
            return Constants.CODE_DOUBLE_QUOTE != codePoint
    }

    @JvmStatic
    fun isEmptyStringOrWhiteSpaces(s: String?): Boolean {
        if (s == null) return true
        val N = codePointCount(s)
        for (i in 0 until N) {
            if (!Character.isWhitespace(s.codePointAt(i))) {
                return false
            }
        }
        return true
    }

    private const val LANGUAGE_GREEK = "el"

    private fun getLocaleUsedForToTitleCase(locale: Locale): Locale {
        // In Greek locale {@link String#toUpperCase(Locale)} eliminates accents from its result.
        // In order to get accented upper case letter, {@link Locale#ROOT} should be used.
        if (LANGUAGE_GREEK == locale.language) {
            return Locale.ROOT
        }
        return locale
    }

    @JvmStatic
    fun toTitleCaseOfKeyLabel(label: String?, locale: Locale): String? {
        if (label == null || !ScriptUtils.scriptSupportsUppercase(locale)) {
            return label
        }
        if (label == "ß")
            return "ẞ" // upcasing of standalone ß, SS is not useful as s is on the keyboard anyway

        return label.uppercase(getLocaleUsedForToTitleCase(locale))
    }

    @JvmStatic
    fun toTitleCaseOfKeyCode(code: Int, locale: Locale): Int {
        if (!Constants.isLetterCode(code)) {
            return code
        }
        val label = newSingleCodePointString(code)
        val titleCaseLabel = toTitleCaseOfKeyLabel(label, locale)
        return if (titleCaseLabel != null && codePointCount(titleCaseLabel) == 1)
            titleCaseLabel.codePointAt(0) else KeyCode.NOT_SPECIFIED
    }

    @JvmStatic
    fun getTrailingSingleQuotesCount(charSequence: CharSequence): Int {
        val lastIndex = charSequence.length - 1
        var i = lastIndex
        while (i >= 0 && charSequence[i].code == Constants.CODE_SINGLE_QUOTE) {
            --i
        }
        return lastIndex - i
    }

    /**
     * Returns whether the last composed word contains line-breaking character (e.g. CR or LF).
     *
     * @param text the text to be examined.
     * @return `true` if the last composed word contains line-breaking separator.
     */
    @JvmStatic
    fun hasLineBreakCharacter(text: String?): Boolean {
        if (text == null || isEmpty(text)) {
            return false
        }
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            when (c) {
                CHAR_LINE_FEED, CHAR_VERTICAL_TAB, CHAR_FORM_FEED, CHAR_CARRIAGE_RETURN,
                CHAR_NEXT_LINE, CHAR_LINE_SEPARATOR, CHAR_PARAGRAPH_SEPARATOR -> return true
            }
        }
        return false
    }

    // unicode blocks that contain emojis
    // very fast check, but there are very few blocks that exclusively contain emojis,
    @JvmStatic
    fun mightBeEmoji(c: Int): Boolean {
        return (0x200D <= c && c <= 0x2BFF) // unicode blocks from General Punctuation to Miscellaneous Symbols and Arrows
                || (0x1F000 <= c && c <= 0x1FAFF) // unicode blocks from Mahjong Tiles to Symbols and Pictographs Extended-A
                || (0xE0000 <= c && c <= 0xE007F) // unicode block Tags
                || c == 0xFE0F // variation selector emoji with color
    }

    @JvmStatic
    fun isLowerCaseAscii(s: String): Boolean {
        val length = s.length
        for (i in 0 until length) {
            val c = s[i].code
            if (c < 97 || c > 122) return false
        }
        return true
    }

    @JvmStatic
    fun charIndexOfFirstWhitespace(s: CharSequence): Int {
        var i = 0
        while (i < s.length - 1) {
            val c = s[i]
            if (Character.isWhitespace(c)) {
                return i + 1
            }
            i++
        }
        return -1
    }

    @JvmStatic
    fun charIndexOfLastWhitespace(s: CharSequence): Int {
        for (i in s.length - 1 downTo 0) {
            val c = s[i]
            if (Character.isWhitespace(c)) {
                return i + 1
            }
        }
        return -1
    }
}

fun CharSequence.codePointAt(offset: Int) = Character.codePointAt(this, offset)
fun CharSequence.codePointBefore(offset: Int) = Character.codePointBefore(this, offset)

/** Loops over the codepoints in [text]. Exits when [loop] returns true */
inline fun loopOverCodePoints(text: CharSequence, loop: (cp: Int, charCount: Int) -> Boolean) {
    var offset = 0
    while (offset < text.length) {
        val cp = text.codePointAt(offset)
        val charCount = Character.charCount(cp)
        if (loop(cp, charCount)) return
        offset += charCount
    }
}

/** Loops backwards over the codepoints in [text]. Exits when [loop] returns true */
inline fun loopOverCodePointsBackwards(text: CharSequence, loop: (cp: Int, charCount: Int) -> Boolean) {
    var offset = text.length
    while (offset > 0) {
        val cp = text.codePointBefore(offset)
        val charCount = Character.charCount(cp)
        if (loop(cp, charCount)) return
        offset -= charCount
    }
}

fun nonWordCodePointAndNoSpaceBeforeCursor(text: CharSequence, spacingAndPunctuations: SpacingAndPunctuations): Boolean {
    var space = false
    var nonWordCodePoint = false
    loopOverCodePointsBackwards(text) { cp, _ ->
        if (!space && Character.isWhitespace(cp)) space = true
        // treat double quote like a word codepoint for this function (not great, maybe clarify name or extend list of chars?)
        if (!nonWordCodePoint && !spacingAndPunctuations.isWordCodePoint(cp) && cp != '"'.code) {
            nonWordCodePoint = true
        }
        space && nonWordCodePoint // stop if both are found
    }
    return nonWordCodePoint && !space // return true if a non-word codepoint and no space was found
}

fun hasLetterBeforeLastSpaceBeforeCursor(text: CharSequence): Boolean {
    loopOverCodePointsBackwards(text) { cp, _ ->
        if (Character.isWhitespace(cp)) return false
        else if (Character.isLetter(cp)) return true
        false // continue
    }
    return false
}

/** get the complete emoji at end of [text], considering that emojis can be joined with ZWJ resulting in different emojis */
// todo: this is now only used for tests, do we actually need it?
fun getFullEmojiAtEnd(text: CharSequence): String {
    val lastGrapheme = text.toString().lastGrapheme
    return if (isEmoji(lastGrapheme)) lastGrapheme else ""
}

/**
 *  Returns whether the [text] ends with word codepoint, ignoring all word connectors.
 *  If the [text] is empty (after ignoring word connectors), the method returns false.
 */
fun endsWithWordCodepoint(text: String, spacingAndPunctuations: SpacingAndPunctuations): Boolean {
    if (text.isEmpty()) return false
    var codePoint = Constants.NOT_A_CODE
    loopOverCodePointsBackwards(text) { cp, _ ->
        val isNotWordConnector = !spacingAndPunctuations.isWordConnector(cp)
        if (isNotWordConnector)
            codePoint = cp
        isNotWordConnector
    }
    return codePoint != Constants.NOT_A_CODE && spacingAndPunctuations.isWordCodePoint(codePoint)
}

// todo: simplify... maybe compare with original code?
// todo: this breaks at e.g. э́, but should not
fun getTouchedWordRange(before: CharSequence, after: CharSequence, script: String, spacingAndPunctuations: SpacingAndPunctuations): TextRange {
    // Going backward, find the first breaking point (separator)
    var startIndexInBefore = before.length
    var endIndexInAfter = -1 // todo: clarify why might we want to set it when checking before
    loopOverCodePointsBackwards(before) { codePoint, cpLength ->
        if (!isPartOfCompositionForScript(codePoint, spacingAndPunctuations, script)) {
            if (Character.isWhitespace(codePoint) || !spacingAndPunctuations.currentLanguageHasSpaces)
                return@loopOverCodePointsBackwards true
            // continue to the next whitespace and see whether this contains a sometimesWordConnector
            for (i in startIndexInBefore - 1 downTo 0) {
                val c = before[i]
                if (spacingAndPunctuations.isSometimesWordConnector(c.code)) {
                    // if yes -> whitespace is the index
                    startIndexInBefore = max(StringUtils.charIndexOfLastWhitespace(before).toDouble(), 0.0).toInt()
                    val firstSpaceAfter = StringUtils.charIndexOfFirstWhitespace(after)
                    endIndexInAfter = if (firstSpaceAfter == -1) after.length else firstSpaceAfter - 1
                    return@loopOverCodePointsBackwards true
                } else if (Character.isWhitespace(c)) {
                    // if no, just break normally
                    return@loopOverCodePointsBackwards true
                }
            }
            return@loopOverCodePointsBackwards true
        }
        startIndexInBefore -= cpLength
        false
    }

    // Find last word separator after the cursor
    if (endIndexInAfter == -1) {
        endIndexInAfter = 0
        loopOverCodePoints(after) { codePoint, cpLength ->
            if (!isPartOfCompositionForScript(codePoint, spacingAndPunctuations, script)) {
                if (Character.isWhitespace(codePoint) || !spacingAndPunctuations.currentLanguageHasSpaces)
                    return@loopOverCodePoints true
                // continue to the next whitespace and see whether this contains a sometimesWordConnector
                for (i in endIndexInAfter..<after.length) {
                    val c = after[i]
                    if (spacingAndPunctuations.isSometimesWordConnector(c.code)) {
                        // if yes -> whitespace is next to the index
                        startIndexInBefore = max(StringUtils.charIndexOfLastWhitespace(before), 0)
                        val firstSpaceAfter = StringUtils.charIndexOfFirstWhitespace(after)
                        endIndexInAfter = if (firstSpaceAfter == -1) after.length else firstSpaceAfter - 1
                        return@loopOverCodePoints true
                    } else if (Character.isWhitespace(c)) {
                        // if no, just break normally
                        return@loopOverCodePoints true
                    }
                }
                return@loopOverCodePoints true
            }
            endIndexInAfter += cpLength
            false
        }
    }

    // strip text before "//" (i.e. ignore http and other protocols)
    val beforeConsideringStart = before.substring(startIndexInBefore, before.length)
    val protocolEnd = beforeConsideringStart.lastIndexOf("//")
    if (protocolEnd != -1) startIndexInBefore += protocolEnd + 1

    // we don't want the end characters to be word separators
    while (endIndexInAfter > 0 && spacingAndPunctuations.isWordSeparator(after[endIndexInAfter - 1].code)) {
        --endIndexInAfter
    }
    while (startIndexInBefore < before.length && spacingAndPunctuations.isWordSeparator(before[startIndexInBefore].code)) {
        ++startIndexInBefore
    }

    val hasUrlSpans = SpannableStringUtils.hasUrlSpans(before, startIndexInBefore, before.length)
        || SpannableStringUtils.hasUrlSpans(after, 0, endIndexInAfter)

    // We don't use TextUtils#concat because it copies all spans without respect to their
    // nature. If the text includes a PARAGRAPH span and it has been split, then
    // TextUtils#concat will crash when it tries to concat both sides of it.
    return TextRange(
        SpannableStringUtils.concatWithNonParagraphSuggestionSpansOnly(before, after),
        startIndexInBefore, before.length + endIndexInAfter, before.length,
        hasUrlSpans
    )
}

// actually this should not be in STRING Utils, but only used for getTouchedWordRange
private fun isPartOfCompositionForScript(codePoint: Int, spacingAndPunctuations: SpacingAndPunctuations, script: String) =
    spacingAndPunctuations.isWordConnector(codePoint) // We always consider word connectors part of compositions.
        // Otherwise, it's part of composition if it's part of script and not a separator.
        || (!spacingAndPunctuations.isWordSeparator(codePoint) && ScriptUtils.isLetterPartOfScript(codePoint, script))

/** split the string on the first of consecutive space only, further consecutive spaces are added to the next split */
fun String.splitOnFirstSpacesOnly(): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var previousSpace = false
    for (c in this) {
        if (c != ' ') {
            sb.append(c)
            previousSpace = false
            continue
        }
        if (!previousSpace) {
            out.add(sb.toString())
            sb.clear()
            previousSpace = true
        } else {
            sb.append(c)
        }
    }
    if (sb.isNotBlank()) out.add(sb.toString())
    return out
}

fun CharSequence.isValidNumber(): Boolean {
    return this.toString().toDoubleOrNull() != null
}

fun String.decapitalize(locale: Locale): String {
    if (isEmpty() || !this[0].isUpperCase()) return this
    return replaceFirstChar { it.lowercase(locale) }
}

fun encodeBase36(string: String): String = BigInteger(string.toByteArray()).toString(36)

fun decodeBase36(string: String) = BigInteger(string, 36).toByteArray().decodeToString()

fun containsValueWhenSplit(string: String?, value: String, split: String): Boolean {
    if (string == null) return false
    return string.split(split).contains(value)
}

/** converts comma-separates character pairs into a list of codepoint-pair-arrays (sorted by first codepoint) */
fun toSortedCodepointArrays(string: String): List<IntArray> {
    val split = string.split(",")
    val list = split.mapTo(mutableListOf()) {
        intArrayOf(it[0].code, it[1].code)
    }
    list.sortBy { it[0] }
    return list
}

/** returns the paired codepoint in the sorted list of pairs, or Constants.NOT_A_CODE */
fun getSecondInSymbolPair(pairs: List<IntArray>, codePoint: Int): Int {
    val i = pairs.binarySearch { it[0].compareTo(codePoint) }
    if (i >= 0) return pairs[i][1]
    return Constants.NOT_A_CODE
}

/** returns whether the text contains a codepoint that might be an emoji */
fun mightBeEmoji(text: CharSequence): Boolean {
    loopOverCodePoints(text) { cp, _ ->
        if (mightBeEmoji(cp)) return true
        false
    }
    return false
}

fun isEmoji(c: Int): Boolean = mightBeEmoji(c) && isEmoji(newSingleCodePointString(c))

/** returns whether the text is a single emoji or a single regional indicator */
fun isEmoji(text: CharSequence): Boolean = text.isSingleRegionalIndicator // not recognized by singleEmojiRegex
        || (text.toString().isSingleGrapheme && mightBeEmoji(text) && text.matches(singleEmojiRegex))

private val CharSequence.isSingleRegionalIndicator: Boolean
    get() = length == 2 && Character.codePointAt(this, 0) in 0x1F1E6..0x1F1FF

// from https://github.com/chattymin/Pebble/blob/main/pebble/src/main/java/com/chattymin/pebble/LocalBreakIterator.kt, Apache-2.0 license
// there is more potentially useful code like String.graphemeLength (should be graphemeCount though)
private val LocalBreakIterator = ThreadLocal<BreakIterator>().apply {
    // this is always Locale.ROOT, but (Android Studio) testing on all available key labels showed no difference by locale
    // maybe this works better with android.icu.text.BreakIterator, but that one requires API24
    set(BreakIterator.getCharacterInstance(Locale.ROOT))
}

private val localBreakIterator: BreakIterator = LocalBreakIterator.get() ?: initBreakIterator()

private fun initBreakIterator() = BreakIterator.getCharacterInstance(Locale.ROOT).also {
    LocalBreakIterator.set(it)
}

val String.isSingleGrapheme: Boolean get() {
    if (isEmpty()) return false
    if (length == 1) return true

    runCatching {
        val iterator = localBreakIterator
        iterator.setText(this)
        iterator.next()
        if (iterator.next() != BreakIterator.DONE) return false
        // we have a single grapheme, but " 🏼" is detected as single grapheme which we don't want
        return if (isSingleRegionalIndicator) true // standalone regional indicator (🇦–🇿): one code point
        else if ('\uD83C' !in this) true // does not contain skin tone
        else singleEmojiRegex.matches(this) // single grapheme only if it's a single emoji
    }
    // got IllegalArgumentException: Invalid index on iterator.next()
    return false
}

val String.lastGrapheme: String get() {
    if (length <= 1) return this

    val iterator = localBreakIterator
    iterator.setText(this)
    val res = substring(iterator.preceding(length))
    val tone = res.indexOfFirst { it == '\uD83C' }
    return if (tone == -1 || singleEmojiRegex.matches(res)) res
    else res.substring(tone) // " 🏼" is detected as single grapheme, but we don't want this
}

/** translates a move of [steps] graphemes in [text] to character count */
fun moveStepsToCharCount(text: CharSequence, steps: Int): Int {
    if (steps == 0) return 0
    val iterator = localBreakIterator
    iterator.setText(text.toString())
    if (steps > 0) {
        repeat(steps) { iterator.next() }
        return if (iterator.current() == BreakIterator.DONE) text.length
        else iterator.current()
    } else {
        iterator.last()
        repeat(-steps) { iterator.previous() }
        return if (iterator.current() == BreakIterator.DONE) -text.length
        else iterator.current() - text.length
    }
}

/** translates a move of [steps] words in [text] to character count, skipping whitespace/punctuation tokens */
fun moveWordStepsToCharCount(text: CharSequence, steps: Int): Int {
    if (steps == 0) return 0
    val str = text.toString()
    val iterator = BreakIterator.getWordInstance(Locale.ROOT)
    iterator.setText(str)
    if (steps > 0) {
        var wordsLeft = steps
        var segStart = 0
        var segEnd = iterator.next()
        while (segEnd != BreakIterator.DONE) {
            if (str.substring(segStart, segEnd).any { it.isLetterOrDigit() }) {
                wordsLeft--
                if (wordsLeft <= 0) return segEnd
            }
            segStart = segEnd
            segEnd = iterator.next()
        }
        return str.length
    } else {
        var wordsLeft = -steps
        var segEnd = str.length
        iterator.last()
        var segStart = iterator.previous()
        while (segStart != BreakIterator.DONE) {
            if (str.substring(segStart, segEnd).any { it.isLetterOrDigit() }) {
                wordsLeft--
                if (wordsLeft <= 0) return segStart - str.length
            }
            segEnd = segStart
            segStart = iterator.previous()
        }
        return -str.length
    }
}

/**
 * How many characters to delete to remove the word before the cursor: the separators and spaces
 * right before the cursor, then the word in front of them. Uses the keyboard's own idea of a word
 * separator, so it follows the current language.
 */
fun lengthOfWordBeforeCursor(before: CharSequence, spacingAndPunctuations: SpacingAndPunctuations): Int =
    lengthOfWordBeforeCursor(before) { spacingAndPunctuations.isWordSeparator(it) }

fun lengthOfWordBeforeCursor(before: CharSequence, isSeparator: (Int) -> Boolean): Int {
    var index = before.length
    fun isGap(codePoint: Int) = Character.isWhitespace(codePoint) || isSeparator(codePoint)
    while (index > 0) {
        val codePoint = Character.codePointBefore(before, index)
        if (!isGap(codePoint)) break
        index -= Character.charCount(codePoint)
    }
    while (index > 0) {
        val codePoint = Character.codePointBefore(before, index)
        if (isGap(codePoint)) break
        index -= Character.charCount(codePoint)
    }
    return before.length - index
}

fun String.splitOnWhitespace() = SpacedTokens(this).toList()

fun stripTrailingSeparatorsAndConnectors(word: String, spacingAndPunctuations: SpacingAndPunctuations): String {
    var end = word.length
    loopOverCodePointsBackwards(word) { cp, l ->
        if (!spacingAndPunctuations.isWordSeparator(cp) && !spacingAndPunctuations.isWordConnector(cp))
            return@loopOverCodePointsBackwards true
        end -= l
        false
    }
    return if (end == word.length) word else word.substring(0, end)
}
