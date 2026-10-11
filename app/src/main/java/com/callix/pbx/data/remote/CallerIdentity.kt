package com.callix.pbx.data.remote

/**
 * Turns the identity carried by an inbound SIP INVITE into something a person can
 * actually read.
 *
 * Linphone hands the app two raw strings, and on this PBX they look like:
 *
 *     remoteAddress -> "sip:101%4009699800222@sip.callix.com.bd"   (the SIP address)
 *     remoteName    -> "101%4009699800222"                          (the internal id)
 *
 * Neither belongs on the incoming-call screen unexamined. One is transport
 * plumbing; the other is an internal account id.
 *
 * WHAT A PERSON SHOULD SEE
 *
 *   * a call from an EXTENSION  ->  the extension's name AND its extension number
 *   * a call from a real number ->  the number (and a name when the network sent one)
 *
 * The earlier version of this file refused anything under six digits as "an
 * internal id", which had the perverse result that the one identity the user most
 * wants to see -- the colleague at extension 101 -- was thrown away: the screen
 * showed the name, hid the number, and fell back to "Unknown number" whenever the
 * name was absent. An extension is not junk. It is an address on this PBX and it
 * is shown as one, together with the name that belongs to it.
 *
 * WHAT IS STILL REFUSED
 *
 * The raw `<extension>%40<main number>` identity is transport plumbing, never a
 * name and never a number. So is a full SIP URI. [fromInbound] takes the
 * EXTENSION out of that identity and shows only that.
 *
 * Every function here is IDEMPOTENT, which matters because the same values travel
 * through several layers: a service resolves them to pass to an Activity, and the
 * Activity resolves them again. Feeding already-resolved values back in must be a
 * no-op, and it is: a plain name stays a plain name, a plain number stays a plain
 * number, and an extension stays an extension.
 */
object CallerIdentity {

    /**
     * At or above this many digits, a run of digits is a phone number a person
     * could dial. Below it, it is an extension on this PBX.
     */
    private const val MIN_NUMBER_DIGITS = 6

    /** Shortest and longest extension this PBX can allocate. */
    private const val MIN_EXTENSION_DIGITS = 2
    private const val MAX_EXTENSION_DIGITS = 5

    /**
     * [primary] is the large line on a call screen; [secondary] is the smaller one,
     * and is null whenever it would merely repeat what [primary] already shows.
     *
     * A null [primary] means the network gave no usable identity -- callers render
     * their own "unknown" copy in that case rather than showing an empty line.
     *
     * [isExtension] lets a screen label the number as an extension ("Ext 101")
     * instead of leaving a bare three-digit number that reads like a truncated
     * phone number. It is deliberately carried rather than baked into [number], so
     * the value stays machine-readable and formatting stays in one place.
     */
    data class Display(
        val name: String?,
        val number: String?,
        val isExtension: Boolean = false
    ) {
        val primary: String? get() = name ?: number
        val secondary: String? get() = if (name != null) number else null
    }

    /**
     * Resolve the pair for an INBOUND call.
     *
     * [rawAddress] may be a full SIP URI or a bare user part, depending on which
     * layer is asking; both are understood.
     */
    fun fromInbound(rawName: String?, rawAddress: String?): Display {
        val user = sipUserPart(rawAddress)
        val identified = identify(user)
        return Display(
            name = displayName(rawName, rawAddress, user),
            number = identified.number,
            isExtension = identified.isExtension
        )
    }

    /**
     * Fill in a missing name from the PBX extension directory.
     *
     * The SIP INVITE carries the caller's name on this PBX, so this is the
     * fallback path, not the normal one: it exists because a call placed through
     * the webphone or by an older client can arrive with no display name at all,
     * and "Ext 101" is a worse answer than the name that the PBX already knows.
     * [nameFor] is expected to answer from a cache -- it must not block the ring.
     */
    fun withDirectoryName(display: Display, nameFor: (String) -> String?): Display {
        if (display.name != null || !display.isExtension) return display
        val number = display.number ?: return display
        val known = nameFor(number)?.takeIf { it.isNotBlank() } ?: return display
        return display.copy(name = known)
    }

    /** The result of reading one user part: it is a number, an extension, or neither. */
    private class Identified(val number: String?, val isExtension: Boolean)

    /**
     * Decide what a SIP user part *is*.
     *
     * This PBX names an endpoint `<extension>@<main number>`, and the same account
     * also answers to a `<extension>-<main number>` spelling -- both are the
     * platform's own address form, and both are read here as "extension N", not as
     * a phone number. Everything else is judged by its digits.
     */
    private fun identify(user: String): Identified {
        if (user.isEmpty()) return Identified(null, false)

        // "<extension>@<main number>" -- the endpoint identity. The part before the
        // '@' is the extension; the part after it is the tenant's main number and is
        // not the caller.
        if (user.contains('@')) {
            val head = user.substringBefore('@').trim()
            return shortExtension(head) ?: numbered(head)
        }

        // "<extension>-<main number>" -- the other spelling of the same endpoint.
        // The hyphen is only read as a separator when the tail really does look
        // like one of this platform's main numbers, which are in national format
        // and start with 0. Without that guard a hyphenated E.164 number such as
        // "880-1712345678" would have its country code mistaken for an extension.
        if (user.contains('-')) {
            val head = user.substringBefore('-').trim()
            val tail = user.substringAfter('-').trim()
            val endpointSpelling = digitsOnly(head) &&
                digitsOnly(tail) &&
                tail.length >= MIN_NUMBER_DIGITS &&
                tail.startsWith("0")
            if (endpointSpelling) {
                return shortExtension(head) ?: numbered(head)
            }
        }

        return numbered(user)
    }

    /** A short run of digits is an extension on this PBX. */
    private fun shortExtension(candidate: String): Identified? {
        val digits = digitsOnly(candidate)
        if (!digits) return null
        return if (candidate.length in MIN_EXTENSION_DIGITS..MAX_EXTENSION_DIGITS) {
            Identified(candidate, true)
        } else {
            null
        }
    }

    /** A long enough run of digits is a phone number. */
    private fun numbered(user: String): Identified {
        if (user.isEmpty() || !digitsOnly(user)) return Identified(null, false)
        if (user.length < MIN_NUMBER_DIGITS) return Identified(null, false)
        // Keep a leading '+' because that is what makes the number international;
        // any other '+' is dropped as noise.
        val digits = user.filter { it.isDigit() }
        val plus = if (user.trimStart().startsWith("+")) "+" else ""
        return Identified("$plus$digits", false)
    }

    private fun digitsOnly(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isDigit() }

    /**
     * The user part of a SIP address, with the scheme, the display name, the URI
     * parameters and the host removed.
     *
     *     "Name" <sip:01712345678@1.2.3.4;user=phone>  ->  "01712345678"
     *     sip:101%4009699800222@host                   ->  "101@09699800222"
     */
    private fun sipUserPart(rawAddress: String?): String {
        var text = rawAddress.orEmpty().trim()
        if (text.isEmpty()) return ""

        // "Display Name" <sip:...> -- keep only what is inside the angle brackets.
        val open = text.indexOf('<')
        val close = text.lastIndexOf('>')
        if (open >= 0 && close > open) text = text.substring(open + 1, close)

        // Drop the scheme (sip: / sips:). A bare user part has no colon to find.
        val colon = text.indexOf(':')
        if (colon >= 0) text = text.substring(colon + 1)

        // Drop URI parameters such as ;user=phone.
        val semicolon = text.indexOf(';')
        if (semicolon >= 0) text = text.substring(0, semicolon)

        // The user part is everything before the host. A bare user part that itself
        // contains an '@' -- the "<extension>@<main number>" identity -- has no host
        // to strip, so the '@' is kept and [identify] reads it.
        val at = text.indexOf('@')
        if (at >= 0 && text.indexOf('.', at) >= 0) text = text.substring(0, at)

        return decode(text)
    }

    /**
     * The caller's name, or null when what we hold is not a name at all.
     *
     * Rejected: anything that is really a URI, the raw address echoed back, the
     * raw user part echoed back, a bare string of digits (which is a number), and
     * the platform's own `<extension>@<main number>` identity -- which is what this
     * app used to be sent as its display name.
     */
    private fun displayName(rawName: String?, rawAddress: String?, user: String): String? {
        val name = decode(rawName.orEmpty().trim())
        if (name.isEmpty()) return null

        // A URI, or a mangled "<ext>@<main>" identity, is never a name.
        if (name.contains('@') || name.contains(':')) return null
        if (name.equals(rawAddress?.trim(), ignoreCase = true)) return null
        if (name.equals(user, ignoreCase = true)) return null

        // "<ext>-<main number>" is the same identity in its other spelling.
        if (looksLikeEndpointIdentity(name)) return null

        // "01712345678" is a number. "101" is an extension. "Rakib 2" is a name.
        val withoutSeparators = name.filterNot { it.isWhitespace() || it == '-' || it == '+' }
        if (withoutSeparators.isNotEmpty() && withoutSeparators.all { it.isDigit() }) return null

        return name
    }

    /** `<extension>-<main number>`, all digits on both sides. */
    private fun looksLikeEndpointIdentity(name: String): Boolean {
        if (!name.contains('-')) return false
        val parts = name.split('-')
        if (parts.size != 2) return false
        return parts.all { digitsOnly(it.trim()) }
    }

    /** Minimal percent-decoding, enough for the escapes a SIP URI can carry. */
    private fun decode(raw: String): String {
        if (raw.indexOf('%') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 3 <= raw.length) {
                val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}