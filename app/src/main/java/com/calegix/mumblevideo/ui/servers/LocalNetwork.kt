package com.calegix.mumblevideo.ui.servers

/**
 * Whether a server address is on the local network, which Android 17 only lets apps reach with
 * ACCESS_LOCAL_NETWORK. Judged from the text alone (private and link-local literals, and the
 * usual local suffixes), so it can be answered before connecting; a LAN host with a public-looking
 * name is missed, and the connection error says what to do then.
 */
internal object LocalNetwork {
    private val suffixes = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")

    fun isLocal(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if ('.' !in h && ':' !in h) return true // single-label names only resolve locally
        if (suffixes.any { h.endsWith(it) }) return true
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
        if (v4 != null) {
            val (a, b) = v4
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    }
}
