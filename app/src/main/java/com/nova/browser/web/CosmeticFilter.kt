package com.nova.browser.web

/**
 * Cosmetic filtering (ad-block layer 2): a small JS snippet that hides the
 * elements matched by the filter list's ## rules. Pure Kotlin, JVM-testable.
 * The snippet is idempotent — it refuses to inject twice on the same page.
 */
object CosmeticFilter {

    /** Build the injection script for [selectors], or null when there is
     *  nothing to hide. Selectors are escaped for a single-quoted JS string. */
    fun buildHideScript(selectors: List<String>): String? {
        if (selectors.isEmpty()) return null
        val css = selectors.joinToString(",") { selector ->
            val safe = selector.replace("\\", "\\\\").replace("'", "\\'")
            "$safe{display:none!important}"
        }
        return "(function(){try{" +
            "if(document.getElementById('nova-cosmetic'))return;" +
            "var s=document.createElement('style');s.id='nova-cosmetic';" +
            "s.textContent='$css';" +
            "(document.head||document.documentElement).appendChild(s);" +
            "}catch(e){}})();"
    }
}
