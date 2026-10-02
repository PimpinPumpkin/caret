// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import android.os.Handler
import android.os.Message
import android.text.InputType
import android.view.inputmethod.*
import androidx.core.content.edit
import app.caret.ShadowInputMethodManager2
import app.caret.ShadowInputMethodService
import app.caret.ShadowInputMethodService.Companion.composingEnd
import app.caret.ShadowInputMethodService.Companion.composingStart
import app.caret.ShadowInputMethodService.Companion.selectedText
import app.caret.ShadowInputMethodService.Companion.selectionEnd
import app.caret.ShadowInputMethodService.Companion.selectionStart
import app.caret.ShadowLocaleManagerCompat
import app.caret.event.Event
import app.caret.keyboard.KeyboardSwitcher
import app.caret.keyboard.MainKeyboardView
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.ShadowFacilitator2.Companion.lastAddedWord
import app.caret.latin.PunctuationSuggestions
import app.caret.latin.SuggestedWords.SuggestedWordInfo
import app.caret.latin.common.Constants
import app.caret.latin.common.LocaleUtils.constructLocale
import app.caret.latin.common.StringUtils
import app.caret.latin.inputlogic.InputLogic
import app.caret.latin.inputlogic.SpaceState
import app.caret.latin.settings.Settings
import app.caret.latin.utils.ScriptUtils
import app.caret.latin.utils.SubtypeSettings
import app.caret.latin.utils.getTimestampFormatter
import app.caret.latin.utils.prefs
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.util.*
import kotlin.math.min
import kotlin.streams.asSequence
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class InputLogicTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val settingsValues get() = Settings.values
    private val inputLogic get() = latinIME.inputLogic
    private val connection: RichInputConnection get() = inputLogic.connection
    private val composerReader = InputLogic::class.java.getDeclaredField("wordComposer").apply { isAccessible = true }
    private val composer get() = composerReader.get(inputLogic) as WordComposer
    private val spaceStateReader = InputLogic::class.java.getDeclaredField("spaceState").apply { isAccessible = true }
    private val spaceState get() = spaceStateReader.get(inputLogic) as Int
    private val beforeComposingReader = RichInputConnection::class.java.getDeclaredField("committedTextBeforeComposingText").apply { isAccessible = true }
    private val connectionTextBeforeComposingText get() = (beforeComposingReader.get(connection) as CharSequence).toString()
    private val composingReader = RichInputConnection::class.java.getDeclaredField("composingText").apply { isAccessible = true }
    private val connectionComposingText get() = (composingReader.get(connection) as CharSequence).toString()

    private val textBeforeCursor get() = ShadowInputMethodService.textBeforeCursor
    private val textAfterCursor get() = ShadowInputMethodService.textAfterCursor
    private val composingText get() = ShadowInputMethodService.composingText
    private val text get() = ShadowInputMethodService.text
    private val cursor get() = ShadowInputMethodService.cursor
    private var currentInputType get() = ShadowInputMethodService.currentInputType
        set(value) { ShadowInputMethodService.currentInputType = value }

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Test fun inputCode() {
        input('c')
        assertEquals("c", textBeforeCursor)
        assertEquals("c", getTextFromConnection())
        assertEquals("", textAfterCursor)
        assertEquals("c", composingText)
        latinIME.handler.onFinishInput()
        assertEquals("", composingText)
    }

    @Test fun delete() {
        setText("hello there ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there", text)
        assertEquals("there", composingText)
    }

    @Test fun deleteMultiCodepointText() {
        setText("hello there \uD83E\uDF00")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteCombinedText() {
        setText("hello there э́")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)

        setText("hello there H̵̛͕̞̦̰̜͍̰̥̟͆̏͂̌͑́ͅ")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello there ", text)
    }

    @Test fun deleteInsideWord() {
        setText("hello you there")
        setCursorPosition(8) // after o in you
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello yu there", text)
        assertEquals("yu", composingText)
    }

    @Test fun insertLetterIntoWord() {
        setText("hello")
        setCursorPosition(3) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
        assertEquals("", composingText)
    }

    @Test fun insertLetterIntoWordWithWeirdEditor() {
        currentInputType = 180225 // should not change much, but just to be sure
        setText("hello")
        setCursorPosition(3, weirdTextField = true) // after first l
        input('i')
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getTextFromConnection())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoOneOfSeveralWords() {
        setText("hello my friend")
        setCursorPosition(7) // between m and y
        input('a')
        assertEquals("may", getWordAtCursor())
        assertEquals("hello may friend", getTextFromConnection())
        assertEquals(8, getCursorPosition())
        assertEquals(8, cursor)
    }

    @Test fun combineHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        chainInput("ㅂㄱㅑ")
        assertEquals("ㅂ갸", text)
    }

    @Test fun emojiHangul() {
        val ko = SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first()
        latinIME.switchToSubtype(ko)
        input(0x1F970)
        assertEquals("\uD83E\uDD70", text)
    }

    // todo: make it work, but it might not be that simple because adding is done in combiner
    //  https://github.com/HeliBorg/HeliBoard/issues/214
    @Test fun insertLetterIntoWordHangulFails() {
        if (System.getenv("CARET_KNOWN_FAILURES") == null) return // documents a known bug, fails on purpose
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛㅎㄹㅎㅕㅛ")
        setCursorPosition(3)
        input('ㄲ') // fails, as expected from the hangul issue when processing the event in onCodeInput
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getWordAtCursor())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", getTextFromConnection())
        assertEquals("ㅛㅎㄹㄲ혀ㅛ", textBeforeCursor + textAfterCursor)
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    // see issue 1447
    @Test fun separatorAfterHangul() {
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        chainInput("ㅛ.")
        assertEquals("ㅛ.", text)
    }

    @Test fun deleteHangulInDebugMode() { // issue 1551, later only happened on phone
        latinIME.switchToSubtype(SubtypeSettings.getResourceSubtypesForLocale("ko".constructLocale()).first())
        setText("ㅛㅛ ")
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
    }

    @Test fun separatorUnselectsWord() {
        setText("hello")
        assertEquals("hello", composingText)
        input('.')
        assertEquals("", composingText)
    }

    @Test fun autospace() {
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello")
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
    }

    @Test fun autospaceButWithTextAfter() {
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello.a", textBeforeCursor)
        assertEquals("hello.a there", text)
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setText("hello there")
        setCursorPosition(5) // after hello
        input('.')
        input('a')
        assertEquals("hello. a", textBeforeCursor)
        assertEquals("hello. a there", text)
    }

    @Test fun noAutospaceInUrlField() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("example.net")
        assertEquals("example. net", text)
        lastAddedWord = ""
        setText("")
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("example.net")
        assertEquals("", lastAddedWord)
        assertEquals("example.net", text)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceInUrlFieldWhenPickingSuggestion() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("exam")
        pickSuggestion("example")
        assertEquals("example", text)
        input('.')
        assertEquals("example.", text)
    }

    // #2611: a word starting with a digit has its leading digit committed outside the composing
    // region (digits don't start composition), so picking a suggestion that includes it used to
    // duplicate the digit ("2u2j" + pick "2u2j@addres.com" -> "22u2j@addres.com").
    @Test fun pickSuggestionForWordWithLeadingDigit() {
        chainInput("2u2j")
        pickSuggestion("2u2j@addres.com")
        assertEquals("2u2j@addres.com", text)
    }

    @Test fun pickSuggestionForWordWithLeadingDigitPartiallyTyped() {
        chainInput("2u")
        pickSuggestion("2u2j@addres.com")
        assertEquals("2u2j@addres.com", text)
    }

    @Test fun noAutospaceForDetectedUrl() { // "light" version, should work without url detection
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("http://example.net")
        assertEquals("http://example.net", text)
        assertEquals("http", lastAddedWord)
        assertEquals("example.net", composingText)
    }

    @Test fun noAutospaceForDetectedEmail() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("mail@example.com")
        assertEquals("mail@example.com", text)
        assertEquals("mail@example", lastAddedWord) // todo: do we want this? not really nice, but don't want to be too aggressive with URL detection disabled
        assertEquals("com", composingText) // todo: maybe this should still see the whole address as a single word? or don't be too aggressive?
        setText("")
        lastAddedWord = ""
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("mail@example.com")
        assertEquals("", lastAddedWord)
        assertEquals("mail@example.com", composingText)
    }

    @Test fun urlDetectionThings() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("...h")
        assertEquals("...h", text)
        assertEquals("h", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("bla..", text)
        assertEquals("", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.c")
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHIFT_REMOVES_AUTOSPACE, true) }
        input("bla")
        input('.')
        functionalKeyPress(KeyCode.SHIFT) // should remove the phantom space (in addition to normal effect)
        input('c')
        assertEquals("bla.c", text)
        assertEquals("bla.c", composingText)
    }

    @Test fun stripSeparatorsBeforeAddingToHistoryWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("example.com.")
        assertEquals("example.com.", composingText)
        input(' ')
        assertEquals("example.com", lastAddedWord)
    }

    @Test fun dontSelectConsecutiveSeparatorsWithURLDetection() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla..")
        assertEquals("", composingText)
        assertEquals("bla..", text)
    }

    @Test fun selectDoesSelect() {
        setText("this is some text")
        setCursorPosition(3, 8)
        assertEquals("s is ", text.substring(3, 8))
    }

    @Test fun noComposingForPasswordFields() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input('a')
        input('b')
        assertEquals("", composingText)
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        input('.')
        input('c')
        assertEquals("", composingText)
    }

    @Test fun `don't select whole thing as composing word if URL detection disabled`() {
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and selecting`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com")
        setCursorPosition(13) // between l and e
        assertEquals("example.com", composingText)
        setText("http://bla.com http://example.com ")
        setCursorPosition(29) // between l and e
        assertEquals("example.com", composingText)
    }

    @Test fun `select whole thing except http(s) as composing word if URL detection enabled and typing`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://example.com")
        assertEquals("example.com", composingText)
    }

    @Test fun `don't add partial URL to history`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http:/") // just so lastAddedWord isn't set to http
        chainInput("/bla.com")
        assertEquals("", lastAddedWord)
    }

    @Test fun urlProperlySelected() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete com
        // todo: do we really want no composing text?
        //  probably not... try not to break composing
        assertEquals("", composingText)
        chainInput("net")
        assertEquals("example.net", composingText)
    }

    @Test fun urlProperlySelectedWhenNotDeletingFullTld() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setText("http://example.com/here")
        setCursorPosition(18) // after .com
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE) // delete om
        // todo: this is a weird difference to deleting the full TLD (see urlProperlySelected)
        //  what do we want here? (probably consistency)
        assertEquals("example.c/here", composingText)
        chainInput("z")
        assertEquals("", composingText) // todo: this is a weird difference to deleting the full TLD
//        assertEquals("example.cz", composingText) // fails, but probably would be better than above
    }

    @Test fun dontCommitPartialUrlBeforeFirstPeriod() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        // type http://bla. -> bla not selected, but clearly url, also means http://bla is committed which we probably don't want
        chainInput("http://bla.")
        assertEquals("bla.", composingText)
    }

    @Test fun `intermediate commits in text field without protocol`() {
        chainInput("bla.")
        assertEquals("bla", lastAddedWord)
        chainInput("com/")
        assertEquals("com", lastAddedWord)
        chainInput("img.jpg")
        assertEquals("img", lastAddedWord)
        assertEquals("jpg", composingText)
    }

    @Test fun `intermediate commit in text field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("bla.com/img.jpg")
        assertEquals("bla", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `only protocol commit in text field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field with protocol and URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("http://bla.com/img.jpg")
        assertEquals("http", lastAddedWord) // todo: somehow avoid?
        assertEquals("http://bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `no intermediate commit in URL field without protocol and with URL detection`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        chainInput("bla.com/img.jpg")
        assertEquals("", lastAddedWord)
        assertEquals("bla.com/img.jpg", text)
        assertEquals("bla.com/img.jpg", composingText)
    }

    @Test fun `don't accidentally detect some other text fields as URI`() {
        // see comment in InputLogic.textBeforeCursorMayBeUrlOrSimilar
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        chainInput("Hey,why")
        assertEquals("Hey, why", text)
    }

    @Test fun `URL detection does not trigger on non-words`() {
        // first make sure it works without URL detection
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
        // then with URL detection
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        chainInput("15:50-17")
        assertEquals("15:50-17", text)
        assertEquals("", composingText)
    }

    @Test fun `autospace after selecting a suggestion`() {
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    @Test fun `autospace works in URL field when input isn't URL`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        input('b')
        assertEquals("this b", text)
        assertEquals("b", composingText)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/215
    // https://github.com/HeliBorg/HeliBoard/issues/229
    @Test fun `autospace works in URL field when input isn't URL, also for multiple suggestions`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pickSuggestion("this")
        pickSuggestion("is")
        assertEquals("this is", text)
        pickSuggestion("not")
        assertEquals("this is not", text)
        input('c')
        assertEquals("this is not c", text)
        assertEquals("c", composingText)
    }

    @Test fun `emoji is added to dictionary`() {
        // check both text and codepoint input
        chainInput("hello ")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        reset()
        chainInput("hello ")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)

        reset()
        chainInput("hello ")
        input("why 🤗 ") // not added because it's not only emoji (input can come from pasting)
        assertEquals("hello", lastAddedWord)
    }

    @Test fun `emoji uses phantom space`() {
        // check both text and codepoint input
        pickSuggestion("hi")
        input("🤗")
        assertEquals("\uD83E\uDD17", lastAddedWord)
        assertEquals("hi \uD83E\uDD17", text)
        reset()
        pickSuggestion("hi")
        input(0x1F36D)
        assertEquals(StringUtils.newSingleCodePointString(0x1F36D), lastAddedWord)
        assertEquals("hi ${StringUtils.newSingleCodePointString(0x1F36D)}", text)
    }

    // https://github.com/HeliBorg/HeliBoard/issues/230
    @Test fun `no autospace after opening quotes`() {
        chainInput("\"Hi\" \"h")
        assertEquals("\"Hi\" \"h", text)
        assertEquals("h", composingText)
        reset()
        chainInput("\"Hi\", \"h")
        assertEquals("\"Hi\", \"h", text)
        assertEquals("h", composingText)
    }

    @Test fun `autospace works in URL field when starting with quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_URL_DETECTION, true) }
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input("\"")
        pickSuggestion("this")
        input("i")
        assertEquals("\"this i", text)
    }

    @Test fun `double space results in period and space, and delete removes the period`() {
        chainInput("hello")
        input(' ')
        input(' ')
        assertEquals("hello. ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello ", text)
    }

    @Test fun `no weird space inside multi-"`() {
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)

        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"\"\"")
        assertEquals("\"\"\"", text)
    }

    @Test fun `autospace still happens after "`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if next word is in quotes`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\"\"you\"")
        assertEquals("\"hello\" \"you\"", text)
    }

    @Test fun `autospace propagates over "`() {
        input('"')
        pickSuggestion("hello")
        assertEquals(spaceState, SpaceState.PHANTOM) // picking a suggestion sets phantom space state
        chainInput("\"you")
        assertEquals("\"hello\" you", text)
    }

    @Test fun `autospace still happens after " if nex word is in " and after comma`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("\"hello\",\"you\"")
        assertEquals("\"hello\", \"you\"", text)
    }

    @Test fun `autospace in json editor`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, true) }
        chainInput("{\"label\":\"")
        assertEquals("{\"label\": \"", text)
        input('c')
        assertEquals("{\"label\": \"c", text)
    }

    @Test fun `text input and delete`() {
        input("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)

        reset()
        input("hello ")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `emoji text input and delete`() {
        input("🕵🏼")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        reset()
        input("\uD83D\uDD75\uD83C\uDFFC")
        input(' ')
        assertEquals("🕵🏼 ", text)
        functionalKeyPress(KeyCode.DELETE)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)
    }

    // emoRegex update to unicode 16.0 was required, https://github.com/HeliBorg/HeliBoard/issues/1760
    @Test fun `emojis deleted one by one`() {
        chainInput("\uD83E\uDEC6\uD83E\uDEC6\uD83E\uDEC6")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("\uD83E\uDEC6\uD83E\uDEC6", text)
    }

    @Test fun `revert autocorrect on delete`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        assertEquals("hello ", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hullo", text)

        reset()
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, false) }
        chainInput("hullo")
        getAutocorrectedWithSpaceAfter("hello", "hullo")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
    }

    @Test fun `remove glide typing word on delete`() {
        glideTypingInput("hello")
        assertEquals("hello", text)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("", text)

        // todo: now we want some way to disable delete-all on backspace, either per setting or something else
        //  need to avoid getting into the mWordComposer.isBatchMode() part of handleBackspaceEvent
    }

    @Test fun timestamp() {
        chainInput("hello")
        functionalKeyPress(KeyCode.TIMESTAMP)
        assertEquals(Calendar.getInstance().time.time.toDouble(),
            getTimestampFormatter(latinIME).parse(text.substring(5))!!.time.toDouble(), 1000.0)
    }

    @Test fun inlineEmojiSearchStart() {
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, ' '.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch(' '.code, ':'.code, ' '.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '.'.code, settingsValues))
        assertEquals(true, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, "🌍".codePoints().asSequence().last(), settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, 't'.code, settingsValues))
        assertEquals(false, InputLogic.isStartOfInlineEmojiSearch('t'.code, ':'.code, '3'.code, settingsValues))
    }

    @Test fun inlineEmojiSearchString() {
        assertEquals("test", InputLogic.getInlineEmojiSearchString(":test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(" :test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("t:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString("6:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString("🌍:test"))
        assertEquals("test", InputLogic.getInlineEmojiSearchString(",:test"))
        assertEquals(null, InputLogic.getInlineEmojiSearchString(":test\nt"))
        assertEquals("/48", InputLogic.getInlineEmojiSearchString("2606:127.0.0.1::/48")) // do we want this?
    }

    @Test fun moveCursorHorizontally() {
        chainInput("hello")
        assertEquals(5, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(-2)
        assertEquals(3, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(-5)
        assertEquals(0, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(-1)
        assertEquals(0, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(3, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(3)
        assertEquals(5, cursor)
        latinIME.keyboardActionListener.onHorizontalSpaceSwipe(1)
        assertEquals(5, cursor)
    }

    // ------- helper functions ---------

    // should be called before every test, so the same state is guaranteed
    @BeforeTest
    fun reset() {
        // reset input connection & facilitator
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        lastAddedWord = ""

        // reset settings
        latinIME.prefs().edit { clear() }

        setText("") // (re)sets selection and composing word
    }

    // ------- picking a suggestion, branches that had no test (Caret) -------

    @Test fun `a picked suggestion replaces the word being typed`() {
        chainInput("hel")
        pickSuggestion("hello")
        assertEquals("hello", text)
        assertEquals("", composingText)
    }

    @Test fun `a picked suggestion replaces only the word being typed`() {
        setText("well ")
        chainInput("hel")
        pickSuggestion("hello")
        assertEquals("well hello", text)
    }

    @Test fun `two picked suggestions get a space between them`() {
        pickSuggestion("hello")
        pickSuggestion("there")
        assertEquals("hello there", text)
    }

    @Test fun `no space after a picked suggestion when autospace is off`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_SUGGESTION, false) }
        pickSuggestion("hello")
        input('t')
        assertEquals("hellot", text)
    }

    @Test fun `a period after a picked suggestion sits right against it`() {
        pickSuggestion("hello")
        input('.')
        assertEquals("hello.", text)
    }

    @Test fun `a picked suggestion replaces a swiped word without adding a space`() {
        setText("well ")
        glideTypingInput("hello")
        pickSuggestion("help")
        assertEquals("well help", text)
    }

    @Test fun `picking a suggestion cannot be undone by backspace`() {
        chainInput("hel")
        pickSuggestion("hello")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)
    }

    @Test fun `a picked punctuation suggestion goes in like a typed one`() {
        setText("hello")
        val suggestions = PunctuationSuggestions.newPunctuationSuggestions(arrayOf("!", "?"))
        latinIME.inputLogic.setSuggestedWords(suggestions)
        val info = suggestions.getInfo(0)
        latinIME.pickSuggestionManually(info)
        handleMessages()
        assertEquals("hello!", text)
    }

    // ------- plain character input, branches that had no test (Caret) -------

    @Test fun `an opening bracket typed over a selection wraps it`() {
        setText("hello there")
        setCursorPosition(6, 11)
        rawType("(") // not input(): its checks assume the character lands right before the cursor
        assertEquals("hello (there)", text)
    }

    @Test fun `a letter typed in front of a word is added to it`() {
        setText("hello")
        setCursorPosition(0)
        rawType("x")
        assertEquals("xhello", text)
        assertEquals(1, cursor)
    }

    @Test fun `digits in a number field arrive`() {
        setInputType(InputType.TYPE_CLASS_NUMBER)
        rawType("123")
        assertEquals("123", text)
    }

    @Test fun `a digit typed after letters stays in the same word`() {
        chainInput("abc1")
        assertEquals("abc1", text)
        assertEquals("abc1", composingText)
    }

    @Test fun `a letter typed after a swiped word starts a new word`() {
        glideTypingInput("hello")
        rawType("s")
        assertEquals("hello s", text)
    }

    @Test fun `a letter typed after a swiped word continues it when autospace after swiping is off`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, false) }
        glideTypingInput("hello")
        rawType("s")
        assertEquals("hellos", text)
    }

    @Test fun `an apostrophe inside a word keeps it one word`() {
        chainInput("don't")
        assertEquals("don't", text)
        assertEquals("don't", composingText)
    }

    @Test fun `with suggestions off nothing is composed`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHOW_SUGGESTIONS, false) }
        setText("")
        chainInput("hi there")
        assertEquals("hi there", text)
        assertEquals("", composingText)
    }

    // ------- swipe input from its start, which had no test (Caret) -------

    /** A whole swipe: the start, which commits what was being typed, then the recognized word. */
    private fun swipe(word: String) {
        latinIME.inputLogic.onStartBatchInput(settingsValues, KeyboardSwitcher.getInstance(), latinIME.handler)
        handleMessages()
        glideTypingInput(word)
        handleMessages()
    }

    @Test fun `a swipe after a typed word keeps the typed word and adds a space`() {
        chainInput("hello")
        swipe("there")
        assertEquals("hello there", text)
    }

    @Test fun `two swipes in a row get a space between them`() {
        swipe("hello")
        swipe("there")
        assertEquals("hello there", text)
    }

    @Test fun `a swipe after a period gets a space`() {
        setText("Hello.")
        swipe("there")
        assertEquals("Hello. there", text)
    }

    @Test fun `no space before a swipe when autospace before swiping is off`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, false) }
        chainInput("hello")
        swipe("there")
        assertEquals("hellothere", text)
    }

    @Test fun `a swipe over a selection replaces it`() {
        setText("hello there")
        setCursorPosition(6, 11)
        swipe("world")
        assertEquals("hello world", text)
    }

    @Test fun `a swipe after a space adds no second space`() {
        setText("hello ")
        swipe("there")
        assertEquals("hello there", text)
    }

    // ------- function keys, which had no test (Caret) -------

    /**
     * For keys that move the selection through the text field: a real field then reports the new
     * selection to the keyboard, the fake one does not, so the test does it.
     */
    private fun selectionKeyPress(keyCode: Int) {
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        rawKey(keyCode)
        latinIME.onUpdateSelection(oldStart, oldEnd, selectionStart, selectionEnd, composingStart, composingEnd)
        handleMessages()
        checkConnectionConsistency()
    }

    @Test fun `select all selects the whole text`() {
        setText("hello there")
        selectionKeyPress(KeyCode.CLIPBOARD_SELECT_ALL)
        assertEquals(0, selectionStart)
        assertEquals(11, selectionEnd)
    }

    @Test fun `select word selects the word at the cursor`() {
        setText("hello there")
        selectionKeyPress(KeyCode.CLIPBOARD_SELECT_WORD)
        assertEquals("there", selectedText)
    }

    @Test fun `cut removes the selection`() {
        setText("hello there")
        setCursorPosition(6, 11)
        functionalKeyPress(KeyCode.CLIPBOARD_CUT)
        assertEquals("hello ", text)
    }

    @Test fun `cut without a selection changes nothing`() {
        setText("hello there")
        functionalKeyPress(KeyCode.CLIPBOARD_CUT)
        assertEquals("hello there", text)
    }

    // In a field that ignores the key event for this (Compose text fields do), the keyboard moves
    // the cursor itself. It used to keep composing the old word, so the next letter landed in that
    // word instead of at the cursor.
    @Test fun `typing after move to start of page goes to the start`() {
        setText("hello there")
        assertEquals("there", composingText)
        selectionKeyPress(KeyCode.MOVE_START_OF_PAGE)
        assertEquals(0, cursor)
        rawType("x")
        assertEquals("xhello there", text)
    }

    @Test fun `typing after move to end of page goes to the end`() {
        setText("hello there")
        setCursorPosition(3)
        selectionKeyPress(KeyCode.MOVE_END_OF_PAGE)
        assertEquals(11, cursor)
        rawType("x")
        assertEquals("hello therex", text)
    }

    @Test fun `shift and enter adds a line break`() {
        chainInput("hello")
        functionalKeyPress(KeyCode.SHIFT_ENTER)
        assertEquals("hello\n", text)
    }

    // ------- undoing an autocorrection and cursor moves, branches that had no test (Caret) -------

    private fun typeAndAutocorrect(typed: String, corrected: String, separator: Char) {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        chainInput(typed)
        val info = SuggestedWordInfo(corrected, "", 0, 0, null, 0, 0)
        val typedInfo = SuggestedWordInfo(typed, "", 0, 0, null, 0, 0)
        latinIME.inputLogic.setSuggestedWords(SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0))
        rawType(separator.toString())
    }

    @Test fun `backspace after an autocorrection ended by a period brings the typed word back with the period`() {
        typeAndAutocorrect("hullo", "hello", '.')
        assertEquals("hello.", text)
        rawKey(KeyCode.DELETE)
        assertEquals("hullo.", text)
    }

    @Test fun `typing after undoing an autocorrection starts a new word`() {
        typeAndAutocorrect("hullo", "hello", ' ')
        rawKey(KeyCode.DELETE)
        assertEquals("hullo", text)
        rawType("x")
        assertEquals("hullo x", text)
    }

    @Test fun `a second backspace after undoing an autocorrection deletes a letter`() {
        typeAndAutocorrect("hullo", "hello", ' ')
        rawKey(KeyCode.DELETE)
        rawKey(KeyCode.DELETE)
        assertEquals("hull", text)
    }

    @Test fun `moving the cursor inside the word being typed keeps typing in that word`() {
        chainInput("hello")
        setCursorPosition(3)
        rawType("x")
        assertEquals("helxlo", text)
    }

    @Test fun `selecting the word being typed and typing replaces it`() {
        chainInput("hello")
        setCursorPosition(0, 5)
        assertEquals("", composingText)
        rawType("x")
        assertEquals("x", text)
    }

    @Test fun `moving the cursor to another word and typing changes that word`() {
        setText("hello there")
        setCursorPosition(5)
        rawType("s")
        assertEquals("hellos there", text)
    }

    // ------- separators, branches that had no test (Caret) -------

    private fun pickPunctuation(mark: String) {
        val suggestions = PunctuationSuggestions.newPunctuationSuggestions(arrayOf(mark))
        latinIME.inputLogic.setSuggestedWords(suggestions)
        latinIME.pickSuggestionManually(suggestions.getInfo(0))
        handleMessages()
    }

    @Test fun `a typed comma after a typed space stays after the space`() {
        chainInput("hello ")
        input(',')
        assertEquals("hello ,", text)
    }

    @Test fun `punctuation picked from the strip after a space moves in front of the space`() {
        chainInput("hello ")
        pickPunctuation("!")
        assertEquals("hello! ", text)
    }

    @Test fun `backspace after swapped punctuation puts the space back in front`() {
        chainInput("hello ")
        pickPunctuation("!")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello !", text)
    }

    @Test fun `a space typed inside a word splits it`() {
        setText("hello")
        setCursorPosition(2)
        input(' ')
        assertEquals("he llo", text)
        assertEquals(3, cursor)
    }

    @Test fun `a period ends the word being typed`() {
        chainInput("hello")
        input('.')
        assertEquals("hello.", text)
        assertEquals("", composingText)
    }

    // ------- backspace branches that had no test (Caret) -------

    private fun repeatDelete(times: Int) = repeat(times) {
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, true))
        handleMessages()
    }

    @Test fun `backspace removes the selection`() {
        setText("hello there friend")
        setCursorPosition(6, 11) // "there"
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello  friend", text)
        assertEquals(6, cursor)
    }

    @Test fun `backspace at the start of the text changes nothing`() {
        setText("hello")
        setCursorPosition(0)
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hello", text)
        assertEquals(0, cursor)
    }

    @Test fun `backspace in a field without an input type sends a key event`() {
        setInputType(InputType.TYPE_NULL)
        setText("hello")
        functionalKeyPress(KeyCode.DELETE)
        assertEquals("hell", text)
    }

    @Test fun `holding backspace with word delete off removes characters and speeds up`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_HOLD_DELETE_BY_WORD, false) }
        setText("one two three four five six seven eight nine ten")
        repeatDelete(30)
        val left = text
        assert("one two three four five six seven eight nine ten".startsWith(left)) { "not a prefix: $left" }
        assert(left.length in 5..17) { "expected 30 to 42 characters gone, left: '$left'" }
    }

    @Test fun `holding backspace with word delete on ends on a word boundary`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_HOLD_DELETE_BY_WORD, true) }
        setText("one two three four five six seven eight nine ten")
        repeatDelete(30)
        val left = text
        assert("one two three four five six seven eight nine ten".startsWith(left)) { "not a prefix: $left" }
        assert(left.isEmpty() || left.endsWith(" ")) { "stopped inside a word: '$left'" }
        assert(left.length < 30) { "too little deleted for 30 repeats: '$left'" }
    }

    @Test fun `a short hold of backspace only removes characters`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_HOLD_DELETE_BY_WORD, true) }
        setText("one two three four five")
        repeatDelete(5)
        assertEquals("one two three four", text)
    }

    // ------- text fields that do not behave (Caret) -------
    // These type without the consistency checks of input(), because here the field and the
    // keyboard's picture of it are expected to drift apart. What matters is what the user ends up with.

    private fun rawType(text: String) = text.forEach {
        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(it.code))
        handleMessages()
    }

    private fun rawKey(keyCode: Int) {
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        handleMessages()
    }

    @Test fun `typing works when the field hides its text`() {
        ShadowInputMethodService.hidesText = true
        rawType("hello there")
        assertEquals("hello there", text)
    }

    @Test fun `backspace deletes when the field hides its text`() {
        ShadowInputMethodService.hidesText = true
        rawType("hello")
        rawKey(KeyCode.DELETE)
        assertEquals("hell", text)
    }

    // An address bar that completes what was typed: after each edit it puts a completion after
    // the cursor. HeliBoard issue 2465 reports backspace repeating text in such fields.
    private fun completeLikeAnAddressBar(full: String) {
        ShadowInputMethodService.afterEdit = {
            val typed = ShadowInputMethodService.textBeforeCursor
            if (typed.isNotEmpty() && full.startsWith(typed) && ShadowInputMethodService.selectionStart == ShadowInputMethodService.selectionEnd)
                ShadowInputMethodService.text = typed + full.substring(typed.length)
        }
    }

    @Test fun `typing into a completing address bar does not double letters`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        completeLikeAnAddressBar("example.com")
        rawType("exa")
        assertEquals("exa", textBeforeCursor)
        assertEquals("example.com", text)
    }

    @Test fun `backspace in a completing address bar removes one letter`() {
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        completeLikeAnAddressBar("example.com")
        rawType("exam")
        rawKey(KeyCode.DELETE)
        assertEquals("exa", textBeforeCursor)
        assert(!text.contains("exaexa") && !text.contains("examexam")) { "text was repeated: $text" }
    }

    // A chat app empties its field when a message is sent and tells the keyboard the cursor moved.
    @Test fun `typing after the app cleared the field does not bring the old word back`() {
        rawType("hello")
        ShadowInputMethodService.reset()
        latinIME.onUpdateSelection(5, 5, 0, 0, -1, -1)
        handleMessages()
        rawType("yo")
        assertEquals("yo", text)
    }

    // The same, but the app never tells the keyboard. The keyboard still believes "hello" is being
    // composed and writes it back. No keyboard can know better here; this documents the limit.
    @Test fun `known limit - a field cleared without notice gets the old word back`() {
        if (System.getenv("CARET_KNOWN_FAILURES") == null) return
        rawType("hello")
        ShadowInputMethodService.reset()
        rawType("yo")
        assertEquals("yo", text)
    }

    // ------- the keyboard's picture of the text field, when the app changes things (Caret) -------

    private fun appReportsSelection(oldStart: Int, oldEnd: Int = oldStart) {
        latinIME.onUpdateSelection(oldStart, oldEnd, selectionStart, selectionEnd, composingStart, composingEnd)
        handleMessages()
    }

    // HeliBoard issue 1141: the app ends the composition on its own
    @Test fun `typing goes on after the app ended the composition itself`() {
        rawType("hello")
        composingStart = -1
        composingEnd = -1
        appReportsSelection(5)
        rawType("s there")
        assertEquals("hellos there", text)
    }

    @Test fun `late reports of the keyboard's own typing change nothing`() {
        rawType("hel")
        // the app only now tells about each letter
        latinIME.onUpdateSelection(0, 0, 1, 1, 0, 1); handleMessages()
        latinIME.onUpdateSelection(1, 1, 2, 2, 0, 2); handleMessages()
        latinIME.onUpdateSelection(2, 2, 3, 3, 0, 3); handleMessages()
        assertEquals("hel", composingText)
        rawType("lo")
        assertEquals("hello", text)
        assertEquals("hello", composingText)
    }

    @Test fun `a field that reformats what is typed does not get letters twice`() {
        ShadowInputMethodService.afterEdit = { ShadowInputMethodService.text = ShadowInputMethodService.text.uppercase() }
        rawType("hello there")
        assertEquals("HELLO THERE", text.uppercase())
        assertEquals(11, text.length)
    }

    // like a card number field that adds a dash after four digits and moves the cursor past it
    @Test fun `typing goes on after the app added a character and moved the cursor`() {
        setInputType(InputType.TYPE_CLASS_NUMBER)
        rawType("1234")
        ShadowInputMethodService.text = "1234-"
        selectionStart = 5
        selectionEnd = 5
        appReportsSelection(4)
        rawType("56")
        assertEquals("1234-56", text)
    }

    private fun startInputAtEndOf(newText: String) {
        // not setText(): its checks compare the whole text with the keyboard's cache, which only holds the last part
        ShadowInputMethodService.text = newText
        selectionStart = newText.length
        selectionEnd = newText.length
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = newText.length
        ei.initialSelEnd = newText.length
        latinIME.handler.onStartInput(ei, false)
        latinIME.handler.onStartInputView(ei, false)
        handleMessages()
    }

    @Test fun `typing and deleting at the end of a text longer than the cache`() {
        val long = "word ".repeat(700) // 3500 characters
        startInputAtEndOf(long)
        rawType("end")
        assertEquals(long + "end", text)
        rawKey(KeyCode.DELETE)
        assertEquals(long + "en", text)
        rawType(" more")
        assertEquals(long + "en more", text)
    }

    // the app says the cursor is at 0 although the field has text and the cursor is at its end
    @Test fun `typing lands at the real cursor when the app reported a wrong position at start`() {
        ShadowInputMethodService.text = "hello "
        selectionStart = 6
        selectionEnd = 6
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = 0
        ei.initialSelEnd = 0
        latinIME.handler.onStartInput(ei, false)
        latinIME.handler.onStartInputView(ei, false)
        handleMessages()
        rawType("there")
        assertEquals("hello there", text)
    }

    // ------- starting input in a field (Caret) -------

    private fun startInput(restarting: Boolean) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = selectionStart
        ei.initialSelEnd = selectionEnd
        latinIME.handler.onStartInput(ei, restarting)
        latinIME.handler.onStartInputView(ei, restarting)
        handleMessages()
    }

    // a chat app sends the message, empties its field and restarts input in the same field
    @Test fun `after the app emptied the field and restarted input the old word is gone`() {
        rawType("hello")
        ShadowInputMethodService.text = ""
        selectionStart = 0
        selectionEnd = 0
        composingStart = -1
        composingEnd = -1
        startInput(restarting = true)
        rawType("yo")
        assertEquals("yo", text)
    }

    @Test fun `restarting input in a field with text picks up the word at the cursor`() {
        ShadowInputMethodService.text = "hello there"
        selectionStart = 11
        selectionEnd = 11
        startInput(restarting = true)
        assertEquals("there", composingText)
        rawType("s")
        assertEquals("hello theres", text)
    }

    @Test fun `moving to a field of another type starts clean`() {
        rawType("hello")
        ShadowInputMethodService.text = ""
        selectionStart = 0
        selectionEnd = 0
        composingStart = -1
        composingEnd = -1
        currentInputType = InputType.TYPE_CLASS_NUMBER
        startInput(restarting = false)
        rawType("12")
        assertEquals("12", text)
    }

    private fun chainInput(text: String) = text.forEach { input(it.code) }

    private fun input(char: Char) = input(char.code)

    private fun input(codePoint: Int) {
        require(codePoint > 0) { "not a codePoint: $codePoint" }
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val insert = StringUtils.newSingleCodePointString(codePoint)
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""
        val oldIsAtEnd = !composer.isCursorFrontOrMiddleOfComposingWord()

        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
        handleMessages()

        if (!latinIME.prefs().getString(Settings.PREF_SELECTED_SUBTYPE, "")!!.contains("CombiningRules") // check fails if combiner merges symbols
            && !(codePoint == Constants.CODE_SPACE && oldBefore.lastOrNull() == ' ') // check fails when 2 spaces are converted into a period
            && !latinIME.inputLogic.suggestedWords.willAutoCorrect // autocorrect obviously creates inconsistencies
            ) {
            if (phantomSpaceToInsert.isEmpty())
                assertEquals(oldBefore + insert, textBeforeCursor)
            else // in some cases autospace might be suppressed
                assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        }
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        if (composer.isComposingWord) // if we're not composing any more cursor is always at the end
            assertEquals(oldIsAtEnd, !composer.isCursorFrontOrMiddleOfComposingWord())
        checkConnectionConsistency()
    }

    private fun functionalKeyPress(keyCode: Int) {
        require(keyCode < 0) { "not a functional key code: $keyCode" }
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, keyCode, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        handleMessages()
        checkConnectionConsistency()
    }

    // almost the same as codePoint input, but calls different latinIME function
    private fun input(insert: String) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val phantomSpaceToInsert = if (spaceState == SpaceState.PHANTOM) " " else ""

        latinIME.onTextInput(insert)
        handleMessages()

        if (phantomSpaceToInsert.isEmpty())
            assertEquals(oldBefore + insert, textBeforeCursor)
        else // in some cases autospace might be suppressed
            assert(oldBefore + phantomSpaceToInsert + insert == textBeforeCursor || oldBefore + insert == textBeforeCursor)
        assert(oldBefore + insert == textBeforeCursor || "$oldBefore $insert" == textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getTextFromConnection())
        checkConnectionConsistency()
    }

    private fun getWordAtCursor() = connection.getWordRangeAtCursor(settingsValues.spacingAndPunctuations, currentScript)?.word

    private fun setCursorPosition(start: Int, end: Int = start, weirdTextField: Boolean = false) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = start
        ei.initialSelEnd = end
        // imeOptions should not matter

        // adjust text in inputConnection first, otherwise fixLyingCursorPosition will move cursor
        // to the end of the text
        val fullText = textBeforeCursor + selectedText + textAfterCursor
        assertEquals(fullText, getTextFromConnection())

        // need to update ic before, otherwise when reloading text cache from ic, ric will load wrong text before cursor
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        selectionStart = start
        selectionEnd = end
        assertEquals(fullText, textBeforeCursor + selectedText + textAfterCursor)

        latinIME.onUpdateSelection(oldStart, oldEnd, start, end, composingStart, composingEnd)
        handleMessages()

        if (weirdTextField) {
            latinIME.handler.onStartInput(ei, true) // essentially does nothing
            latinIME.handler.onStartInputView(ei, true) // does the thing
            handleMessages()
        }

        assertEquals(fullText, getTextFromConnection())
        assertEquals(start, selectionStart)
        assertEquals(end, selectionEnd)
        checkConnectionConsistency()
    }

    // assumes we have nothing selected
    private fun getCursorPosition(): Int {
        assertEquals(cursor, connection.expectedSelectionStart)
        assertEquals(cursor, connection.expectedSelectionEnd)
        return cursor
    }

    // just sets the text and starts input so connection it set up correctly
    private fun setText(newText: String) {
        ShadowInputMethodService.text = newText
        selectionStart = newText.length
        selectionEnd = selectionStart
        composingStart = -1
        composingStart = -1

        // we need to start input to notify that something changed
        // restarting is false, so this is seen as a new text field
        val ei = EditorInfo()
        ei.inputType = currentInputType
        latinIME.handler.onStartInput(ei, false)
        latinIME.handler.onStartInputView(ei, false)
        handleMessages() // this is important so the composing span is set correctly
        checkConnectionConsistency()
    }

    // like selecting a suggestion from strip
    private fun pickSuggestion(suggestion: String) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        latinIME.pickSuggestionManually(info)
        checkConnectionConsistency()
    }

    // only works when autocorrect is on, separator after word is required
    private fun getAutocorrectedWithSpaceAfter(suggestion: String, typedWord: String?) {
        val info = SuggestedWordInfo(suggestion, "", 0, 0, null, 0, 0)
        val typedInfo = SuggestedWordInfo(typedWord!!, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0)
        latinIME.inputLogic.setSuggestedWords(sw) // this prepares for autocorrect
        input(' ')
        checkConnectionConsistency()
    }

    private fun glideTypingInput(word: String) {
        val info = SuggestedWordInfo(word, "", 0, 0, null, 0, 0)
        val sw = SuggestedWords(ArrayList(listOf(info)), null, info, true, false, false, 0, 0)
        latinIME.inputLogic.onUpdateTailBatchInputCompleted(settingsValues, sw, KeyboardSwitcher.getInstance())
    }

    private fun checkConnectionConsistency() {
        // RichInputConnection only has composing text up to cursor, but InputConnection has full composing text
        val expectedConnectionComposingText = if (composingStart == -1 || composingEnd == -1) ""
        else text.substring(composingStart, min(composingEnd, selectionEnd))
        assert(composingText.startsWith(expectedConnectionComposingText))
        // RichInputConnection only returns text up to cursor
        val textBeforeComposingText = if (composingStart == -1) textBeforeCursor else text.substring(0, composingStart)

        println("consistency: $selectionStart, ${connection.expectedSelectionStart}, $selectionEnd, ${connection.expectedSelectionEnd}, $textBeforeComposingText, " +
                "$connectionTextBeforeComposingText, $composingText, $connectionComposingText, $textBeforeCursor, ${connection.getTextBeforeCursor(textBeforeCursor.length, 0)}" +
                ", $textAfterCursor, ${connection.getTextAfterCursor(textAfterCursor.length, 0)}")
        assertEquals(selectionStart, connection.expectedSelectionStart)
        assertEquals(selectionEnd, connection.expectedSelectionEnd)
        assertEquals(textBeforeComposingText, connectionTextBeforeComposingText)
        assertEquals(expectedConnectionComposingText, connectionComposingText)
        assertEquals(textBeforeCursor, connection.getTextBeforeCursor(textBeforeCursor.length, 0).toString())
        assertEquals(textAfterCursor, connection.getTextAfterCursor(textAfterCursor.length, 0).toString())
    }

    private fun getTextFromConnection() =
        connection.getTextBeforeCursor(100, 0).toString() + (connection.getSelectedText(0) ?: "") + connection.getTextAfterCursor(100, 0)

    private fun setInputType(inputType: Int) {
        // set text to actually apply input type
        currentInputType = inputType
        setText(text)
    }

    // always need to handle messages for proper simulation
    private fun handleMessages() {
        while (messages.isNotEmpty()) {
            latinIME.handler.handleMessage(messages.first())
            messages.removeAt(0)
        }
        while (delayedMessages.isNotEmpty()) {
            val msg = delayedMessages.first()
            if (msg.what != 2) // MSG_UPDATE_SUGGESTION_STRIP, we want to ignore it because it's irrelevant and has a 500 ms timeout
                latinIME.handler.handleMessage(delayedMessages.first())
            delayedMessages.removeAt(0)
            // delayed messages may post further messages, handle before next delayed message
            while (messages.isNotEmpty()) {
                latinIME.handler.handleMessage(messages.first())
                messages.removeAt(0)
            }
        }
        assertEquals(0, messages.size)
        assertEquals(0, delayedMessages.size)
    }

}

private var currentScript = ScriptUtils.SCRIPT_LATIN
private val messages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
private val delayedMessages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService

// Shadows are handled by Robolectric. @Implementation overrides built-in functionality.
// This is used for avoiding crashes (LocaleManagerCompat, InputMethodManager, KeyboardSwitcher)
// and for simulating system stuff (InputMethodService for controlling the InputConnection, which
// more or less is the contents of the text field), and for setting the current script in
// KeyboardSwitcher without having to care about InputMethodSubtypes

@Implements(Handler::class)
class ShadowHandler {
    @Implementation
    fun sendMessage(message: Message) {
        messages.add(message)
    }
    @Implementation
    fun sendMessageDelayed(message: Message, delay: Long) {
        delayedMessages.add(message)
    }
}

@Implements(KeyboardSwitcher::class)
class ShadowKeyboardSwitcher {
    @Implementation
    // basically only needed for null check
    fun getMainKeyboardView(): MainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
    @Implementation
    // only affects view
    fun setKeyboard(keyboardId: Int, toggleState: KeyboardSwitcher.KeyboardSwitchState) = Unit
    @Implementation
    // only affects view
    fun setOneHandedModeEnabled(enabled: Boolean, force: Boolean) = Unit
    @Implementation
    fun getCurrentKeyboardScript() = currentScript
}

@Implements(DictionaryFacilitatorImpl::class)
class ShadowFacilitator2 {
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean,
                         ngramContext: NgramContext, timeStampInSeconds: Long,
                         blockPotentiallyOffensive: Boolean) {
        lastAddedWord = suggestion
    }
    companion object {
        var lastAddedWord = ""
    }
}
