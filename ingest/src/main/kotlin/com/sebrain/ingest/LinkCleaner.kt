package com.sebrain.ingest

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Removes known tracking parameters without changing the rest of captured text. */
internal object LinkCleaner {
    private val URL = Regex("""https?://[^\s<>\]]+""", RegexOption.IGNORE_CASE)
    private val TRACKING_PARAMETER = Regex(
        """(?:utm_[^=&#]*|gclid|dclid|fbclid|msclkid|mc_cid|mc_eid|_hsenc|_hsmi|hsCtaTracking)""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(text: String): String = URL.replace(text) { match ->
        val rawUrl = match.value
        val split = rawUrl.indexOfLast { it !in ".,;:!?)}" } + 1
        val suffix = rawUrl.substring(split)
        val url = rawUrl.removeSuffix(suffix)
        cleanUrl(url) + suffix
    }

    private fun cleanUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val fragmentStart = url.indexOf('#', queryStart + 1)
        val queryEnd = if (fragmentStart >= 0) fragmentStart else url.length
        val query = url.substring(queryStart + 1, queryEnd)
        val kept = query.split('&').filter { parameter ->
            parameter.isNotEmpty() && !isTracking(parameter.substringBefore('='))
        }
        val base = url.substring(0, queryStart)
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    private fun isTracking(rawName: String): Boolean {
        val name = runCatching {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawName)
        return TRACKING_PARAMETER.matches(name)
    }
}
