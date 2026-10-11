package com.callix.pbx.data.remote

/**
 * Turns a stored phone number into a form that can be COMPARED.
 *
 * The same subscriber is written many ways across one handset: `+8801712345678`,
 * `01712345678`, `880 171 234-5678`, `008801712345678`. Comparing the raw strings
 * finds none of those equal, which is exactly why a call log and an address book
 * can hold the same person and never match.
 *
 * The canonical form keeps only digits and normalises the Bangladesh country code
 * to the local zero-prefixed form, so every spelling above becomes `01712345678`.
 * Extensions (`101`) and non-BD numbers fall through as their digits, which is
 * still an improvement on comparing raw text.
 *
 * This is NOT a dialling normaliser: what gets dialled is whatever the user saved.
 * It exists only for looking a number up.
 */
object PhoneNumbers {

    /** Bangladesh country code. */
    private const val BD_CC = "880"

    fun canonical(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        // A leading "00" is an international access prefix, not part of the number.
        val trimmed = if (digits.startsWith("00")) digits.substring(2) else digits

        // 8801XXXXXXXXX -> 01XXXXXXXXX, so a number saved with its country code
        // compares equal to the same number saved in local form.
        if (trimmed.startsWith(BD_CC) && trimmed.length > BD_CC.length + 1) {
            val rest = trimmed.substring(BD_CC.length)
            if (rest.startsWith("1")) return "0$rest"
        }
        return trimmed
    }

    /**
     * True when a name is nothing more than the number again.
     *
     * Some rows arrive with the number copied into the name field. Repeating those
     * digits as if they were a name is worse than showing nothing, because it hides
     * the fact that the real contact was never found.
     */
    fun isNameJustTheNumber(name: String?, number: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        return canonical(n) == canonical(number) && canonical(n).isNotEmpty()
    }
}
