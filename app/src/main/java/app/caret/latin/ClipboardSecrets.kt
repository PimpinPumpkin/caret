// SPDX-License-Identifier: GPL-3.0-only
package app.caret.latin

/**
 * Decides whether copied text looks like a secret that should not be readable on the screen:
 * a payment card number, a login token, a private key or a wallet seed phrase. The rules look
 * at the whole text, not at words inside it, so a message that merely mentions a password or
 * contains a number is left alone. One-time codes are not hidden: people read them to check
 * they are pasting the right one.
 */
object ClipboardSecrets {
    private const val MAX_LENGTH = 5000
    private val cardShape = Regex("""\d(?:[ -]?\d){12,18}""")
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*""")
    private val longHex = Regex("""(?:0x)?[0-9a-fA-F]{32,}""")
    // one unbroken run of base64 or token characters that mixes letters and digits
    private val token = Regex("""[A-Za-z0-9+/_=-]{40,}""")
    private val prefixedKey = Regex("""(?:sk|pk|rk|ghp|gho|ghs|github_pat|xox[abprs]|AKIA|AIza|glpat)[-_][A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}""")
    private val seedWord = Regex("""[a-z]{3,8}""")
    private val seedPhraseLengths = setOf(12, 15, 18, 21, 24)

    fun looksSecret(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty() || text.length > MAX_LENGTH) return false
        val trimmed = text.trim()
        if (trimmed.length < 12) return false
        if ("-----BEGIN" in trimmed && "PRIVATE KEY-----" in trimmed) return true
        if (cardShape.matches(trimmed) && passesLuhn(trimmed)) return true
        if (jwt.matches(trimmed) || longHex.matches(trimmed) || prefixedKey.matches(trimmed)) return true
        if (token.matches(trimmed) && trimmed.any { it.isDigit() } && trimmed.any { it.isLetter() }) return true
        val words = trimmed.split(' ', '\n')
        return words.size in seedPhraseLengths && words.all { seedWord.matches(it) }
    }

    /** The text with everything but its first and last two characters replaced by dots, at most [length] long. */
    fun mask(text: CharSequence, length: Int = 16): String {
        val trimmed = text.trim()
        if (trimmed.length < 8) return "•".repeat(trimmed.length)
        return trimmed.take(2).toString() + "•".repeat((trimmed.length - 4).coerceAtMost(length - 4)) + trimmed.takeLast(2)
    }

    /** The check digit rule of payment card numbers, which keeps phone and order numbers out. */
    private fun passesLuhn(text: CharSequence): Boolean {
        var sum = 0
        var double = false
        for (i in text.indices.reversed()) {
            val c = text[i]
            if (!c.isDigit()) continue
            var digit = c - '0'
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return sum % 10 == 0
    }
}
