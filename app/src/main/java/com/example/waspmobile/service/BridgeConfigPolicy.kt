package com.example.waspmobile.service

import java.net.URI

/** Pure policy shared by the connection editor and migration tests. */
internal object BridgeConfigPolicy {
    const val DEFAULT_ORIGIN = "https://waspmobile.129.146.39.132.sslip.io"
    private val tokenPattern = Regex("[A-Za-z0-9_-]{32,128}")
    fun normalizeOrigin(raw: String): String? = runCatching {
        val u = URI(raw.trim())
        require(u.scheme.equals("https", true) && !u.host.isNullOrBlank())
        require(u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null)
        require(u.rawPath.isNullOrEmpty() || u.rawPath == "/")
        require(u.port == -1 || u.port in 1..65535)
        URI("https", null, u.host.lowercase(), if (u.port == 443) -1 else u.port, null, null, null).toASCIIString()
    }.getOrNull()
    fun validToken(value: String) = tokenPattern.matches(value)
    fun validDevice(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,64}"))
    fun mayReuseToken(savedOrigin: String, enteredOrigin: String): Boolean =
        normalizeOrigin(savedOrigin)?.let { it == normalizeOrigin(enteredOrigin) } == true
}

