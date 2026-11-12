package com.nova.browser.web

import android.content.Context
import com.nova.browser.data.ErrorLog

/** Loads the YouTube Ad Skipper content script (assets/youtube-skipper.js).
 *  Loaded once and cached — the script is ~10 KB. A missing asset means no
 *  injection (fail open: YouTube just plays its ads); the failure is logged
 *  so it shows up in the in-app Error log instead of failing silently. */
object YoutubeSkipper {

    private var cached: String? = null
    private var tried = false

    fun load(context: Context): String? {
        if (tried) return cached
        tried = true
        cached = try {
            context.assets.open("youtube-skipper.js").bufferedReader().readText()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            ErrorLog.log(context, "YoutubeSkipper", "youtube-skipper.js could not be read", e)
            null
        }
        return cached
    }
}
