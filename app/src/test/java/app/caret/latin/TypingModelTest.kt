// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import app.caret.latin.lm.TypingModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingModelTest {
    @Test fun contextIsTheCurrentLineWithoutTheSpaceAtTheEnd() {
        assertEquals("see you at", TypingModel.context("ok\nsee you at "))
        assertEquals("", TypingModel.context(null))
        assertEquals("", TypingModel.context("done.\n"))
    }

    @Test fun longContextIsCutAtAWord() {
        val context = TypingModel.context("word ".repeat(200) + "halfway there")
        assertTrue(context.length <= 320)
        assertTrue(context.startsWith("word "))
        assertTrue(context.endsWith("halfway there"))
    }

    @Test fun oneVeryLongWordIsKept() {
        assertEquals(320, TypingModel.context("x".repeat(500)).length)
    }

    @Test fun recognizesGgufFiles() {
        val file = File.createTempFile("model", ".gguf")
        try {
            file.writeBytes("GGUF\u0003rest".toByteArray())
            assertTrue(TypingModel.isGguf(file))
            file.writeText("PK not a model")
            assertFalse(TypingModel.isGguf(file))
            file.writeText("GG")
            assertFalse(TypingModel.isGguf(file))
        } finally {
            file.delete()
        }
    }

    @Test fun typedKeysGoBetweenBarsAfterTheText() {
        assertEquals("see you at | t eh h |", TypingModel.typingPrompt("see you at", listOf("t", "eh", "h")))
        assertEquals("| h i |", TypingModel.typingPrompt("", listOf("h", "i")))
        assertEquals(null, TypingModel.scoreTyped("see you at", listOf("t", "e", "h"), listOf("the")))
    }

    @Test fun answersNullWithoutAModel() {
        assertEquals(null, TypingModel.scoreWords("i am", listOf("here")))
        assertEquals(null, TypingModel.predictNext("i am", 3))
    }
}
