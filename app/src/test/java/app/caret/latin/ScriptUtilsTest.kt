// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.common.LocaleUtils.constructLocale
import app.caret.latin.utils.ScriptUtils.SCRIPT_CYRILLIC
import app.caret.latin.utils.ScriptUtils.SCRIPT_DEVANAGARI
import app.caret.latin.utils.ScriptUtils.SCRIPT_LATIN
import app.caret.latin.utils.ScriptUtils.script
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptUtilsTest {
    @Test fun defaultScript() {
        assertEquals(SCRIPT_LATIN, "en".constructLocale().script())
        assertEquals(SCRIPT_DEVANAGARI, "hi".constructLocale().script())
        assertEquals(SCRIPT_LATIN, "hi_zz".constructLocale().script())
        assertEquals(SCRIPT_LATIN, "sr-Latn".constructLocale().script())
        assertEquals(SCRIPT_CYRILLIC, "mk".constructLocale().script())
        assertEquals(SCRIPT_CYRILLIC, "fr-Cyrl".constructLocale().script())
    }
}
