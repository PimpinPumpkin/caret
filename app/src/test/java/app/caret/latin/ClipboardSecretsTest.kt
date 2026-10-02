// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardSecretsTest {
    private fun secret(text: String) = assertTrue(ClipboardSecrets.looksSecret(text), text)
    private fun plain(text: String) = assertFalse(ClipboardSecrets.looksSecret(text), text)

    @Test fun `card numbers are secret, other long numbers are not`() {
        secret("4111 1111 1111 1111")
        secret("4111-1111-1111-1111")
        secret("5500005555555559")
        plain("4111 1111 1111 1112") // fails the check digit
        plain("+49 170 1234567890")
        plain("1234567890123")
    }

    @Test fun `tokens and keys are secret`() {
        secret("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U")
        secret("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        secret("ghp_16C7e42F292c6912E7710c838347Ae178B4a")
        secret("AKIAIOSFODNN7EXAMPLE")
        secret("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----")
        secret("dGhpcyBpcyBhIHNlY3JldCB0b2tlbiB3aXRoIDEyMyBudW1iZXJz")
    }

    @Test fun `a seed phrase is secret, an ordinary sentence is not`() {
        secret("legal winner thank year wave sausage worth useful legal winner thank yellow")
        plain("legal winner thank year wave sausage worth useful legal winner thank")
        plain("Please send me the password for the wifi when you get a chance today")
    }

    @Test fun `ordinary copied text is left alone`() {
        plain("483920") // a one-time code
        plain("my password is hunter2")
        plain("https://example.com/some/long/path/that/goes/on/and/on/index.html")
        plain("the quick brown fox jumps over the lazy dog")
        plain("")
        plain("Zusammengehoerigkeitsgefuehlsbekundungen und so weiter")
    }

    @Test fun `masking keeps the ends`() {
        assertEquals("41••••••••••••11", ClipboardSecrets.mask("4111 1111 1111 1111"))
        assertEquals("••••••", ClipboardSecrets.mask("secret"))
    }
}
