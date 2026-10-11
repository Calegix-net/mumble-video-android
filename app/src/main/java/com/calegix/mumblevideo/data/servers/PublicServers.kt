package com.calegix.mumblevideo.data.servers

/** An entry from the public server directory. */
data class PublicServer(
    val name: String,
    val host: String,
    val port: Int,
    val country: String,
    val countryCode: String,
    val region: String,
)

/**
 * The public server directory. Stubbed with a fixed list: the real one is the XML feed at
 * publist.mumble.info, which wants a client-identifying POST and is a separate piece of work.
 */
interface PublicServerSource {
    suspend fun fetch(): List<PublicServer>
}

class StubPublicServerSource : PublicServerSource {
    override suspend fun fetch(): List<PublicServer> = listOf(
        PublicServer("Example Gaming Hub", "voice.example.net", 64738, "Germany", "DE", "Europe"),
        PublicServer("Late Night Radio", "radio.example.org", 64738, "Canada", "CA", "North America"),
        PublicServer("Open Source Devs", "mumble.example.dev", 64739, "Netherlands", "NL", "Europe"),
        PublicServer("Tabletop Tuesdays", "dice.example.com", 64738, "United States", "US", "North America"),
        PublicServer("Pacific Pals", "pacific.example.jp", 64738, "Japan", "JP", "Asia"),
        PublicServer("Movie Night (video)", "cinema.example.org", 64738, "Sweden", "SE", "Europe"),
    )
}
