package com.callix.pbx.ui.call

import android.content.Context
import com.callix.pbx.R
import com.callix.pbx.data.remote.CallerIdentity

/**
 * The ONE place a resolved caller identity is turned into the two lines a person
 * reads.
 *
 * There are three surfaces that show a caller -- the incoming-call screen, the
 * in-call screen, and the missed-call notification -- and before this they each
 * formatted the pair themselves. They drifted: one hid the number line when the
 * name was absent, another printed the dialled number twice. Formatting in one
 * function means the surfaces cannot disagree, and it keeps the "Ext 101" label
 * out of [CallerIdentity], which stays a pure value object with no Context.
 */
object CallerText {

    /**
     * The large line: the caller's name when we have one, otherwise the best
     * available identity.
     */
    fun primary(context: Context, display: CallerIdentity.Display): String {
        val name = display.name
        if (name != null) return name

        val number = display.number
        if (number != null) {
            return if (display.isExtension) extensionLabel(context, number) else number
        }

        return context.getString(R.string.incoming_unknown_caller)
    }

    /**
     * The small line under [primary], or null when it would add nothing.
     *
     * An extension is shown here ON PURPOSE even when its name is already above:
     * the user asked to see the extension number of the colleague calling, and a
     * name alone does not tell them which extension to dial back.
     */
    fun secondary(context: Context, display: CallerIdentity.Display): String? {
        val number = display.number ?: return null
        // Nothing extra to say when the primary line already is this identity.
        if (display.name == null) return null
        return if (display.isExtension) extensionLabel(context, number) else number
    }

    private fun extensionLabel(context: Context, extension: String): String =
        context.getString(R.string.extension_number, extension)
}