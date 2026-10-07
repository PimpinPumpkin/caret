// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.keyboard.emoji.EmojiDictionaryDownload
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiDictionaryDownloadTest {
    @Test fun picksTheRegionsDictionaryWhenThereIsOneElseTheLanguages() {
        assertEquals("en", EmojiDictionaryDownload.nameFor(Locale.US))
        assertEquals("en_gb", EmojiDictionaryDownload.nameFor(Locale.UK))
        assertEquals("de", EmojiDictionaryDownload.nameFor(Locale.GERMANY))
        assertEquals("de_ch", EmojiDictionaryDownload.nameFor(Locale.forLanguageTag("de-CH")))
        assertEquals("zh_hant", EmojiDictionaryDownload.nameFor(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("zh", EmojiDictionaryDownload.nameFor(Locale.forLanguageTag("zh-CN")))
        assertEquals(null, EmojiDictionaryDownload.nameFor(Locale.forLanguageTag("tlh")))
    }
}
