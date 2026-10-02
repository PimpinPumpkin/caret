// SPDX-License-Identifier: GPL-3.0-only
package app.caret

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import com.android.inputmethod.keyboard.ProximityInfo
import app.caret.keyboard.Key
import app.caret.keyboard.Key.KeyParams
import app.caret.keyboard.Keyboard
import app.caret.keyboard.KeyboardElement
import app.caret.keyboard.KeyboardId
import app.caret.keyboard.KeyboardLayoutSet
import app.caret.keyboard.internal.KeySpecParser.KeySpecParserError
import app.caret.keyboard.internal.KeyboardBuilder
import app.caret.keyboard.internal.KeyboardParams
import app.caret.keyboard.internal.PopupKeySpec
import app.caret.keyboard.internal.TouchPositionCorrection
import app.caret.keyboard.internal.UniqueKeysCache
import app.caret.keyboard.internal.keyboard_parser.LayoutParser
import app.caret.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import app.caret.keyboard.internal.keyboard_parser.floris.KeyCode
import app.caret.latin.LatinIME
import app.caret.latin.RichInputMethodSubtype
import app.caret.latin.utils.LayoutUtilsCustom
import app.caret.latin.utils.POPUP_KEYS_LAYOUT
import app.caret.latin.utils.SubtypeUtilsAdditional
import app.caret.latin.settings.Settings
import app.caret.latin.utils.prefs
import androidx.core.content.edit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class,
    ShadowProximityInfo::class,
])
class ParserTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val params = KeyboardParams()

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        params.id = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        params.popupKeyOrder.add(POPUP_KEYS_LAYOUT)
        params.popupKeyHintOrder.add(POPUP_KEYS_LAYOUT)
        LocaleKeyboardInfos.addLocaleKeyTextsToParams(latinIME, params, LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
    }

    @Test fun simpleParser() {
        val layoutStrings = listOf(
"""
a
b
c

d
e
f
""", // normal
"""
a
b
c

d
e
f
""", // spaces in the empty line
"""
a
b
c

d
e
f
""".replace("\n", "\r\n"), // windows file endings
"""
a
b
c


d
e
f

""", // too many newlines
"""
a
b x
c v

d
e
f
""", // spaces in the end
"""
a
b
c

d
e
f""", // no newline at the end
        )
        val wantedKeyLabels = listOf(listOf("a", "b", "c"), listOf("d", "e", "f"))
        layoutStrings.forEach { layout ->
            val keyLabels = LayoutParser.parseSimpleString(layout)
                .map { row -> row.map { it.toKeyParams(params).label } }
            assertEquals(wantedKeyLabels, keyLabels)
        }
    }

    @Test fun simpleKey() {
        assertIsExpected("""[[{ "$": "auto_text_key" "label": "a" }]]""", Expected('a'.code, "a"))
        assertIsExpected("""[[{ "$": "text_key" "label": "a" }]]""", Expected('a'.code, "a"))
        assertIsExpected("""[[{ "label": "a" }]]""", Expected('a'.code, "a"))
    }

    @Test fun labelAndExplicitCode() {
        assertIsExpected("""[[{ "$": "text_key" "label": "a", "code": 98 }]]""", Expected('b'.code, "a"))
    }

    @Test fun labelAndImplicitCode() {
        assertIsExpected("""[[{ "$": "text_key" "label": "a|b" }]]""", Expected('b'.code, "a"))
    }

    @Test fun labelAndImplicitText() {
        assertIsExpected("""[[{ "$": "text_key" "label": "a|bb" }]]""", Expected(KeyCode.MULTIPLE_CODE_POINTS, "a", text = "bb"))
        assertIsExpected("""[[{ "$": "text_key" "label": "a|" }]]""", Expected(KeyCode.MULTIPLE_CODE_POINTS, "a", text = ""))
    }

    @Test fun labelAndImplicitAndExplicitCode() { // explicit code overrides implicit code
        assertIsExpected("""[[{ "code": 32, "label": "a|b" }]]""", Expected(' '.code, "a"))
        assertIsExpected("""[[{ "code": 32, "label": "a|!code/key_delete" }]]""", Expected(' '.code, "a"))
        assertIsExpected("""[[{ "code": 32, "label": "a|!code/-1" }]]""", Expected(' '.code, "a"))
        assertIsExpected("""[[{ "code": -1, "label": "a|!code/key_delete" }]]""", Expected(KeyCode.CTRL, "a"))
        assertIsExpected("""[[{ "code": 32, "label": "a|bb" }]]""", Expected(' '.code, "a"))
        assertIsExpected("""[[{ "code": 32, "label": "a|bb", "popup": { "main": { "code": 32, "label": "!icon/undo|!code/key_delete" } } }]]""", Expected(' '.code, "a", popups = listOf(null to ' '.code)))
        assertIsExpected("""[[{ "code": 32, "label": "a|bb", "popup": { "main": { "code": -1, "label": "!icon/undo|!code/key_delete" } } }]]""", Expected(' '.code, "a", popups = listOf(null to KeyCode.CTRL)))
        assertIsExpected("""[[{ "code": 32, "label": "a|bb", "popup": { "main": { "code": 32, "label": "a|!code/key_delete" } } }]]""", Expected(' '.code, "a", popups = listOf("a" to ' '.code)))
        assertIsExpected("""[[{ "code": 32, "label": "a|bb", "popup": { "main": { "code": -1, "label": "a|!code/key_delete" } } }]]""", Expected(' '.code, "a", popups = listOf("a" to KeyCode.CTRL)))
    }

    @Test fun keyWithIconAndExplicitCode() {
        assertIsExpected("""[[{ "label": "!icon/clipboard", "code": 55 }]]""", Expected(55, icon = "clipboard"))
        assertIsExpected("""[[{ "label": "!icon/clipboard", "code": -1 }]]""", Expected(KeyCode.CTRL, icon = "clipboard"))
    }

    @Test fun keyWithIconAndImplicitCode() {
        assertIsExpected("""[[{ "label": "!icon/clipboard_action_key|!code/key_clipboard" }]]""", Expected(KeyCode.CLIPBOARD, icon = "clipboard_action_key"))
        assertIsExpected("""[[{ "label": "!icon/clipboard_action_key|!code/key_clipboard", "popup": { "main": { "label": "!icon/undo|!code/key_delete" } } }]]""", Expected(KeyCode.CLIPBOARD, icon = "clipboard_action_key", popups = listOf(null to KeyCode.DELETE)))
    }

    @Test fun popupKeyWithIconAndExplicitCode() {
        assertIsExpected("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key", "code": 32 }
      ]
    } }]]""", Expected('a'.code, "a", popups = listOf(null to ' '.code)))
    }

    @Test fun popupKeyWithIconAndExplicitAndImplicitCode() {
        assertIsExpected("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|", "code": 32 }
      ]
    } }]]""", Expected('a'.code, "a", popups = listOf(null to ' '.code)))
        assertIsExpected("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|abc", "code": 32 }
      ]
    } }]]""", Expected('a'.code, "a", popups = listOf(null to ' '.code)))
    }

    @Test fun labelAndImplicitCodeForPopup() {
        assertIsExpected("""[[{ "$": "text_key" "label": "a|b", "popup": { "main": { "label": "b|a" } } }]]""", Expected('b'.code, "a", popups = listOf("b" to 'a'.code)))
        assertIsExpected("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|" }
      ]
    } }]]""", Expected('a'.code, "a",
            popups = listOf(null to KeyCode.MULTIPLE_CODE_POINTS))
        )
    }

    @Test fun `| works`() {
        assertIsExpected("""[[{ "label": "|", "popup": { "main": { "label": "|" } } }]]""", Expected('|'.code, "|", popups = listOf("|" to '|'.code)))
    }

    @Test fun currencyKey() {
        assertIsExpected("""[[{ "label": "$$$" }]]""", Expected('$'.code, "$", popups = listOf("£", "¢", "€", "¥", "₱").map { it to it.first().code }))
    }

    @Test fun currencyKeyWithOtherCurrencyCode() {
        assertIsExpected("""[[{ "label": "$$$", code: -805 }]]""", Expected('¥'.code, "$", popups = listOf("£", "¢", "€", "¥", "₱").map { it to it.first().code }))
    }

    @Test fun currencyPopup() {
        assertIsExpected("""[[{ "label": "p", "popup": { "main": { "label": "$$$" } } }]]""", Expected('p'.code, "p", null, null, listOf("$" to '$'.code)))
        assertIsExpected("""[[{ "label": "p", "popup": { "main": { "label": "a", "code": -804 } } }]]""", Expected('p'.code, "p", null, null, listOf("a" to '€'.code)))
        assertIsExpected("""[[{ "label": "p", "popup": { "main": { "label": "!icon/clipboard_action_key", "code": -804 } } }]]""", Expected('p'.code, "p", null, null, listOf(null to '€'.code)))
    }

    @Test fun weirdCurrencyKey() {
        assertIsExpected("""[[{ "code": -801, "label": "currency_slot_1", "popup": {
      "main": { "code": -802, "label": "currency_slot_2" },
      "relevant": [
        { "code": -806, "label": "currency_slot_6" },
        { "code": -803, "label": "currency_slot_3" },
        { "code": -804, "label": "currency_slot_4" },
        { "code": -805, "label": "currency_slot_5" },
        { "code": -804, "label": "$$$4" }
      ]
    } }]]""", Expected('$'.code, "$", popups = listOf("£" to '£'.code, "₱" to '₱'.code, "¢" to '¢'.code, "€" to '€'.code, "¥" to '¥'.code, "¥" to '€'.code)))
    }

    @Test fun caseSelector() {
        assertIsExpected("""[[{ "$": "case_selector",
      "lower": { "code":  105, "label": "i" },
      "upper": { "code":  304, "label": "İ" }
    }]]""", Expected(105, "i"))
    }

    @Test fun caseSelectorWithPopup() {
        assertIsExpected("""[[{ "$": "case_selector",
      "lower": { "code":   59, "label": ";", "popup": {
        "relevant": [
          { "code":   58, "label": ":" }
        ]
      } },
      "upper": { "code":   58, "label": ":", "popup": {
        "relevant": [
          { "code":   59, "label": ";" }
        ]
      } }
    }]]""", Expected(';'.code, ";", popups = listOf(":").map { it to it.first().code }))
    }

    @Test fun shiftSelector() {
        assertIsExpected("""[[{ "$": "shift_state_selector",
      "shiftedManual": { "code":   62, "label": ">", "popup": {
        "relevant": [
          { "code":   46, "label": "." }
        ]
      } },
      "default": { "code":   46, "label": ".", "popup": {
        "relevant": [
          { "code":   62, "label": ">" }
        ]
      } }
    }]]""", Expected('.'.code, ".", popups = listOf(">").map { it to it.first().code }))
    }

    @Test fun nestedSelectors() {
        assertIsExpected("""[[{ "$": "shift_state_selector",
      "shiftedManual": { "code":   34, "label": "\"", "popup": {
        "relevant": [
          { "code":   33, "label": "!" },
          { "code":   39, "label": "'"}
        ]
      } },
      "default": { "$": "variation_selector",
        "email":   { "code":   64, "label": "@" },
        "uri":     { "code":   47, "label": "/" },
        "default": { "code":   39, "label": "'", "popup": {
          "relevant": [
            { "code":   33, "label": "!" },
            { "code":   34, "label": "\"" }
          ]
        } }
      }
    }]]""", Expected('\''.code, "'", popups = listOf("!", "\"").map { it to it.first().code }))
    }

    @Test fun layoutDirectionSelector() {
        assertIsExpected("""[[{ "$": "layout_direction_selector",
      "ltr": { "code":   40, "label": "(", "popup": {
        "main": { "code":   60, "label": "<" },
        "relevant": [
          { "code":   91, "label": "[" },
          { "code":  123, "label": "{" }
        ]
      } },
      "rtl": { "code":   41, "label": "(", "popup": {
        "main": { "code":   62, "label": "<" },
        "relevant": [
          { "code":   93, "label": "[" },
          { "code":  125, "label": "{" }
        ]
      } }
    }]]""", Expected('('.code, "(", popups = listOf("<", "[", "{").map { it to it.first().code }))
    }

    @Test fun autoMultiTextKey() {
        assertIsExpected("""[[{ "label": "্র" }]]""", Expected(KeyCode.MULTIPLE_CODE_POINTS, "্র", text = "্র"))
    }

    @Test fun multiTextKey() { // pointless without codepoints!
        assertIsExpected("""[[{ "$": "multi_text_key", "codePoints": [2509, 2480], "label": "্র" }]]""", Expected(KeyCode.MULTIPLE_CODE_POINTS, "্র", text = "্র"))
        assertIsExpected("""[[{ "$": "multi_text_key", "codePoints": [2509, 2480], "label": "x" }]]""", Expected(KeyCode.MULTIPLE_CODE_POINTS, "x", text = "্র"))
    }

    @Test fun negativeCode() {
        assertIsExpected("""[[{ "code":   -7, "label": "delete" }]]""", Expected(-7, icon = "delete_key", background = Key.BACKGROUND_TYPE_FUNCTIONAL))
    }

    @Test fun keyWithType() {
        assertIsExpected("""[[{ "code":   57, "label": "9", "type": "numeric" }]]""", Expected(57, "9"))
        assertIsExpected("""[[{ "code":   -7, "label": "delete", "type": "enter_editing" }]]""", Expected(-7, icon = "delete_key", background = Key.BACKGROUND_TYPE_ACTION))
        // -207 gets translated to -202 in Int.toKeyEventCode and view_phone2 to symbol
        assertIsExpected("""[[{ "code": -207, "label": "view_phone2", "type": "system_gui" }]]""", Expected(-202, "!?#", background = Key.BACKGROUND_TYPE_FUNCTIONAL))
    }

    @Test fun spaceKey() {
        assertIsExpected("""[[{ "code":   32, "label": "space" }]]""", Expected(32, icon = "space_key", background = Key.BACKGROUND_TYPE_SPACEBAR))
    }

    @Test fun emojiKey() {
        assertIsExpected("""[[{ "label": "emoji" }]]""", Expected(KeyCode.EMOJI, icon = "emoji", background = Key.BACKGROUND_TYPE_FUNCTIONAL))
    }

    @Test fun invalidKeys() {
        assertFailsWith<KeySpecParserError> {
            LayoutParser.parseJsonString("""[[{ "label": "!icon/clipboard_action_key" }]]""")
                .map { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
        }
    }

    @Test fun removeRedundantPopupKeys() {
        val keyParams = LayoutParser.parseJsonString("""[[{"label": "k"}, { "label": "w", "popup": {
          "relevant": [{ "label": "!hasLabels!" }, { "label": "k" }, { "label": "m" }]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
        keyParams[1].absoluteWidth = 1f
        keyParams[1].absoluteHeight = 1f
        val key = keyParams[1].createKey()
        assertEquals(null, key.hintLabel)
        assertEquals(2, key.popupKeys?.size)
        val lettersOnBaseLayout = PopupKeySpec.LettersOnBaseLayout()
        lettersOnBaseLayout.addLetter(keyParams[0])
        val keyWithoutRedundantPopups = Key.removeRedundantPopupKeys(key, lettersOnBaseLayout)
        assertEquals(1, keyWithoutRedundantPopups.popupKeys?.size)
        assertEquals(null, keyWithoutRedundantPopups.hintLabel)
    }

    @Test fun popupWithCodeAndLabel() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "w", "popup": {
          "main": { "code":   55, "label": "!" }
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals("!", key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals('7'.code, key.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals("!", key.toKeyParams(params).hintLabel)
    }

    @Test fun popupWithCodeAndIcon() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "w", "popup": {
          "main": { "code":   55, "label": "!icon/clipboard_action_key" }
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("clipboard_action_key", key.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals('7'.code, key.toKeyParams(params).popupKeys?.first()?.code)
    }

    @Test fun popupToolbarKey() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "x", "popup": {
          "main": { "label": "undo" }
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("undo", key.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(KeyCode.UNDO, key.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals("undo", key.toKeyParams(params).hintIconName)
    }

    @Test fun popupKeyWithIconAndImplicitText() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|aa" }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("go_key", key.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(KeyCode.MULTIPLE_CODE_POINTS, key.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals("aa", key.toKeyParams(params).popupKeys?.first()?.outputText)
        assertEquals("go_key", key.toKeyParams(params).hintIconName)

        val key2 = LayoutParser.parseJsonString("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|" }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key2.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("go_key", key2.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(KeyCode.MULTIPLE_CODE_POINTS, key2.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals("", key2.toKeyParams(params).popupKeys?.first()?.outputText)
    }

    // output text is null here, maybe should be changed?
    @Test fun popupKeyWithIconAndCodeAndImplicitText() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|", "code": 55 }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("go_key", key.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(55, key.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals(null, key.toKeyParams(params).popupKeys?.first()?.outputText)

        val key2 = LayoutParser.parseJsonString("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|a", "code": 55 }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key2.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("go_key", key2.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(55, key2.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals(null, key2.toKeyParams(params).popupKeys?.first()?.outputText)

        val key3 = LayoutParser.parseJsonString("""[[{ "label": "a", "popup": { "relevant": [
       { "label": "!icon/go_key|aa", "code": 55 }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key3.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals("go_key", key3.toKeyParams(params).popupKeys?.first()?.iconName)
        assertEquals(55, key3.toKeyParams(params).popupKeys?.first()?.code)
        assertEquals(null, key3.toKeyParams(params).popupKeys?.first()?.outputText)
    }

    @Test fun invalidPopupKeys() {
        assertFailsWith<KeySpecParserError> {
            LayoutParser.parseJsonString("""[[{ "label": "a", "popup": {
          "main": { "label": "!icon/clipboard_action_key" }
    } }]]""").map { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
        }
    }

    @Test fun popupSymbolAlpha() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "c", "popup": {
          "main": { "code":   -10001, "label": "x" }
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals("x", key.toKeyParams(params).popupKeys?.first()?.label)
        assertEquals(-10001, key.toKeyParams(params).popupKeys?.first()?.code)
    }

    @Test fun canLoadKeyboard() {
        val editorInfo = EditorInfo()
        val subtype = SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(Locale.ENGLISH, "qwerty", true)
        val (kb, keys) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET)
        assertEquals(kb.getSortedKeys().size, keys.sumOf { it.size })
    }

    @Test fun `dvorak has 4 rows`() {
        val editorInfo = EditorInfo()
        val subtype = SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(Locale.ENGLISH, "dvorak", true)
        val (_, keys) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET)
        assertEquals(keys.size, 4)
    }

    @Test fun `de_DE has extra keys`() {
        val editorInfo = EditorInfo()
        val subtype = SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(Locale.GERMANY, "qwertz+", true)
        val (_, keys) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET)
        assertEquals(11, keys[0].size)
        assertEquals(11, keys[1].size)
        assertEquals(10, keys[2].size)
        val (_, keys2) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED)
        assertEquals(11, keys2[0].size)
        assertEquals(11, keys2[1].size)
        assertEquals(10, keys2[2].size)
    }

    @Test fun `popup key count does not depend on shift for (for simple layout)`() {
        val editorInfo = EditorInfo()
        val subtype = SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(Locale.ENGLISH, "qwerty", true)
        val (kb, keys) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET)
        val (kb2, keys2) = buildKeyboard(editorInfo, subtype, KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED)
        assertEquals(kb.getSortedKeys().size, kb2.getSortedKeys().size)
        keys.forEachIndexed { i, kpList -> kpList.forEachIndexed { j, kp ->
            assertEquals(kp.popupKeys?.size, keys2[i][j].popupKeys?.size)
        } }
        kb.getSortedKeys().forEachIndexed { index, key ->
            assertEquals(key.popupKeys?.size, kb2.getSortedKeys()[index].popupKeys?.size)
        }
    }

    @Test fun parseExistingLayouts() {
        val dir = File("src/main/assets/layouts")
        dir.walk().forEach {
            if (it.isDirectory) return@forEach
            val content = it.readText()
            val data = if (it.name.endsWith(".json"))
                LayoutParser.parseJsonString(content)
            else LayoutParser.parseSimpleString(content)
            data.flatten().forEach { key -> key.compute(params)?.toKeyParams(params) }
        }
    }

    @Test fun simpleWithLabelPopupHasCode() {
        val keys = LayoutParser.parseSimpleString("""
            a symbol_alpha
            b esc
            c undo

            d $$$
            e $$$1
            f blah
            tab timestamp
    """).flatMap { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
        assertEquals("?123", keys[0].popupKeys?.first()?.label)
        assertEquals(KeyCode.SYMBOL_ALPHA, keys[0].popupKeys?.first()?.code)
        assertEquals("ESC", keys[1].popupKeys?.first()?.label)
        assertEquals(KeyCode.ESCAPE, keys[1].popupKeys?.first()?.code)
        assertEquals(null, keys[2].popupKeys?.first()?.label)
        assertEquals("undo", keys[2].popupKeys?.first()?.iconName)
        assertEquals(KeyCode.UNDO, keys[2].popupKeys?.first()?.code)
        assertEquals("$", keys[3].popupKeys?.first()?.label)
        assertEquals('$'.code, keys[3].popupKeys?.first()?.code)
        assertEquals("£", keys[4].popupKeys?.first()?.label)
        assertEquals('£'.code, keys[4].popupKeys?.first()?.code)
        assertEquals("blah", keys[5].popupKeys?.first()?.label)
        assertEquals(KeyCode.MULTIPLE_CODE_POINTS, keys[5].popupKeys?.first()?.code)
        assertEquals("tab_key", keys[6].iconName)
        assertEquals(KeyCode.TAB, keys[6].code)
        assertEquals("⌚", keys[6].popupKeys?.first()?.label)
        assertEquals(KeyCode.TIMESTAMP, keys[6].popupKeys?.first()?.code)
    }

    @Test fun keyRepeatPopup() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "c", "popup": {
          "main": { "code":   -11000, "label": "x" }
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key.toKeyParams(params).popupKeys)
        assertEquals(1, key.toKeyParams(params).actionFlags and 0x01 )
        val key2 = LayoutParser.parseSimpleString("a b|!code/-11000").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(null, key2.toKeyParams(params).popupKeys)
        assertEquals(1, key2.toKeyParams(params).actionFlags and 0x01 )
    }

    @Test fun keyRepeatKeyDoesNotWork() {
        assertFails {
            LayoutParser.parseJsonString("""[[{ "label": "c", "code":   -11000 }} }]]""")
                .flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        }
        // but unfortunately the following does work:
        LayoutParser.parseSimpleString("a|!code/-11000").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
    }

    private data class Expected(
        val code: Int,
        val label: String? = null,
        val icon: String? = null,
        val text: String? = null,
        val popups: List<Pair<String?, Int>>? = null,
        val background: Int = Key.BACKGROUND_TYPE_NORMAL
    )

    private fun assertIsExpected(json: String, expected: Expected) {
        assertAreExpected(json, listOf(expected))
    }

    private fun assertAreExpected(json: String, expected: List<Expected>) {
        val keys = LayoutParser.parseJsonString(json)
            .map { row -> row.mapNotNull { it.compute(params) } }.flatten()
        keys.forEachIndexed { index, keyData ->
            println("data: key ${keyData.label}: code ${keyData.code}, popups: ${keyData.popup.getPopupKeyLabels(params)}")
            val keyParams = keyData.toKeyParams(params)
            println("params: key ${keyParams.label}: code ${keyParams.code}, popups: ${keyParams.popupKeys?.toList()}")
            assertEquals(expected[index].label, keyParams.label)
            assertEquals(expected[index].icon, keyParams.iconName)
            assertEquals(expected[index].code, keyParams.code)
            assertEquals(expected[index].popups, keyParams.popupKeys?.mapNotNull { it.label to it.code })
            assertEquals(expected[index].text, keyParams.outputText)
            assertEquals(expected[index].background, keyParams.backgroundType)
            assertTrue(LayoutUtilsCustom.checkKeys(listOf(listOf(keyParams))))
        }
    }

    private fun buildKeyboard(editorInfo: EditorInfo, subtype: InputMethodSubtype, element: KeyboardElement): Pair<Keyboard, List<List<KeyParams>>> {
        val layoutParams = KeyboardLayoutSet.Params()
        val editorInfoField = KeyboardLayoutSet.Params::class.java.getDeclaredField("editorInfo").apply { isAccessible = true }
        editorInfoField.set(layoutParams, editorInfo)
        val subtypeField = KeyboardLayoutSet.Params::class.java.getDeclaredField("subtype").apply { isAccessible = true }
        subtypeField.set(layoutParams, RichInputMethodSubtype.get(subtype))
        val widthField = KeyboardLayoutSet.Params::class.java.getDeclaredField("keyboardWidth").apply { isAccessible = true }
        widthField.setInt(layoutParams, 500)
        val heightField = KeyboardLayoutSet.Params::class.java.getDeclaredField("keyboardHeight").apply { isAccessible = true }
        heightField.setInt(layoutParams, 300)

        val keysInRowsField = KeyboardBuilder::class.java.getDeclaredField("keysInRows").apply { isAccessible = true }

        val id = KeyboardId(element, layoutParams)
        val builder = KeyboardBuilder(latinIME, KeyboardParams(UniqueKeysCache.NO_CACHE))
        builder.load(id)
        @Suppress("UNCHECKED_CAST")
        return builder.build() to keysInRowsField.get(builder) as ArrayList<ArrayList<KeyParams>>
    }

    // The split setting has to reach the keyboard id, or the builder never adds the gap.
    @Test fun `split keyboard setting splits the alphabet layout`() {
        fun gapAfterG(): Int {
            val layoutParams = KeyboardLayoutSet.Params()
            layoutParams.editorInfo = EditorInfo()
            layoutParams.subtype = RichInputMethodSubtype.get(SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(Locale.ENGLISH, "qwerty", true))
            layoutParams.keyboardWidth = 1000
            layoutParams.keyboardHeight = 300
            layoutParams.settingsValues = Settings.values
            val builder = KeyboardBuilder(latinIME, KeyboardParams(UniqueKeysCache.NO_CACHE))
            builder.load(KeyboardId(KeyboardElement.ALPHABET, layoutParams))
            val keys = builder.build().getSortedKeys()
            val g = keys.first { it.label == "g" }
            val h = keys.first { it.label == "h" }
            return h.getX() - (g.getX() + g.width)
        }
        val joined = gapAfterG()
        latinIME.prefs().edit { putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, true) }
        val split = gapAfterG()
        latinIME.prefs().edit { putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, false) }
        assertTrue(split > joined + 50, "gap between g and h was $joined, with split on $split")
    }

    @Test fun placeholderAsOnlyPopup() { // https://github.com/HeliBorg/HeliBoard/issues/1324
        val keyParams = LayoutParser.parseJsonString("""[[{ "label": "ক", "popup": { "relevant": [
       { "type": "placeholder" }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }.single()
        assertEquals(null, keyParams.popupKeys)
        keyParams.absoluteWidth = 1f
        keyParams.absoluteHeight = 1f
        val key = keyParams.createKey()
        assertEquals(false, key.isLongPressEnabled)
    }

    @Test fun placeholderNextToOtherPopup() {
        val key = LayoutParser.parseJsonString("""[[{ "label": "ক", "popup": { "relevant": [
       { "type": "placeholder" }, { "label": "k" }
      ]
    } }]]""").flatMap { row -> row.mapNotNull { it.compute(params) } }.single()
        assertEquals(1, key.toKeyParams(params).popupKeys?.size)
        assertEquals("k", key.toKeyParams(params).popupKeys?.first()?.label)
    }

}

@Implements(ProximityInfo::class)
class ShadowProximityInfo {
    @Implementation
    fun createNativeProximityInfo(tpc: TouchPositionCorrection): Long = 0
}
