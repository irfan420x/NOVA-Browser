package com.nova.browser.adblock

import android.content.Context
import com.nova.browser.data.ErrorLog

/** Load the bundled filter list (assets/adblock.txt) plus the user's custom
 *  rules from Settings, and parse them into an engine. Runs on the calling
 *  thread — the bundled list is ~4 KB, so this is cheap. */
object FilterLists {

    fun load(context: Context, customRules: String): AdBlockEngine {
        val bundled = try {
            context.assets.open("adblock.txt").bufferedReader().readText()
        } catch (e: Exception) {
            ErrorLog.log(context, "FilterLists", "Bundled adblock.txt could not be read", e)
            ""
        }
        val combined = if (customRules.isBlank()) bundled else "$bundled\n$customRules"
        return try {
            AdBlockEngine.parse(combined)
        } catch (e: Exception) {
            ErrorLog.log(context, "FilterLists", "Filter list parse failed", e)
            AdBlockEngine.parse("")
        }
    }
}
