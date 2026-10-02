// SPDX-License-Identifier: GPL-3.0-only
package app.caret

import androidx.test.core.app.ApplicationProvider
import app.caret.accessibility.KeyboardAccessibilityDelegate
import app.caret.keyboard.KeyDetector
import app.caret.keyboard.MainKeyboardView
import app.caret.latin.App
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class AccessibilityDelegateTest {
    // The provider keeps which key has accessibility focus and which is hovered. A new provider
    // on every request, as it was before, forgets both between two calls from the system.
    @Test fun `the node provider is created once and reused`() {
        ApplicationProvider.getApplicationContext<App>()
        val view = Mockito.mock(MainKeyboardView::class.java)
        val delegate = KeyboardAccessibilityDelegate(view, KeyDetector())
        val first = delegate.getAccessibilityNodeProvider(view)
        assertSame(first, delegate.getAccessibilityNodeProvider(view))
    }
}
