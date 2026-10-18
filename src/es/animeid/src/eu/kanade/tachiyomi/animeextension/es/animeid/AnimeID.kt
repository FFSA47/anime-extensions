package eu.kanade.tachiyomi.animeextension.es.animeid

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.Normalizer

class AnimeID :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()


    private val safeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublicIp(it) }) {
                throw UnknownHostException("AnimeID: blocked non-public host $hostname")
            }
            return addresses
        }
    }

    private val safeClient: OkHttpClient by lazy {
        client.newBuilder()
            .dns(safeDns)
            .build()
    }


    private val mp4uploadClient: OkHttpClient by lazy {
        safeClient.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .build()
    }

    private val voeExtractor by lazy { VoeExtractor(safeClient, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(mp4uploadClient) }
    private val uqloadExtractor by lazy { UqloadExtractor(safeClient) }
    private val streamWishExtractor by lazy { StreamWishExtractor(safeClient, headers) }
    private val universalExtractor by lazy { UniversalExtractor(safeClient) }


    private val streamWishHosts = listOf(
        "streamwish", "wishembed", "strwish", "playerwish", "wishfast",
    )

    private val urlRegex =
        """(http|ftp|https)://([\w_-]+(?:(?:\.[\w_-]+)+))([\w.,@?^=%&:/~+#-]*[\w@?^=%&/~+#-])""".toRegex()
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
    private val combiningMarksRegex = Regex("""\p{M}+""")

    // ============================== Temporadas (unsupported) ===============================
    override fun seasonListSelector(): String = ""
    override fun seasonFromElement(element: Element): SAnime =
        throw UnsupportedOperationException("Seasons not supported")

    // ============================== Hoster ===============================
    override fun hosterListParse(response: Response): List<Hoster> =
        listOf(Hoster(hosterUrl = response.request.url.toString()))

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.ul.x5 article.li, div.ul article.li"

    override fun popularAnimeRequest(page: Int): Request {
        val urlBuilder = "$baseUrl/series".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("sort", "views")
        if (page > 1) {
            urlBuilder.addQueryParameter("pag", page.toString())
        }
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
            ?: throw Exception("AnimeID: no <a> element found")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid href '${linkElement.attr("href")}'")
        anime.setUrlWithoutDomain(
            httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: ""),
        )
        anime.title = linkElement.selectFirst("span")?.text()?.trim()
            ?: throw Exception("AnimeID: missing title")

        element.selectFirst("figure.i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() }
                ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }?.let { anime.thumbnail_url = it }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pag li a:contains(Siguiente)"

    // ============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "div.ul.hm article.li"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl?pag=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
            ?: throw Exception("AnimeID: no <a> element found")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid href '${linkElement.attr("href")}'")
        val path = httpUrl.encodedPath.removePrefix("/")
        val animeSlug = if ("ver/" in path) {
            path.substringAfter("ver/").substringBeforeLast("-")
        } else {
            path.substringBeforeLast("-")
        }
        if (animeSlug.isBlank()) throw Exception("AnimeID: could not parse slug")
        anime.setUrlWithoutDomain("/$animeSlug")
        anime.title = linkElement.selectFirst("span")?.text()?.trim()
            ?: throw Exception("AnimeID: missing title")

        element.selectFirst("figure.i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() }
                ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }?.let { anime.thumbnail_url = it }
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = "ul.pag li a:contains(Siguiente)"

    // ============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.firstInstanceOrNull<GenreFilter>()
        val yearFilter = filterList.firstInstanceOrNull<YearFilter>()
        val statusFilter = filterList.firstInstanceOrNull<StatusFilter>()
        val orderFilter = filterList.firstInstanceOrNull<OrderFilter>()

        val urlBuilder = "$baseUrl/series".toHttpUrlOrNull()!!.newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("buscar", query)
        } else {
            genreFilter?.takeIf { it.state != 0 }?.let {
                urlBuilder.addQueryParameter("genero", it.toUriPart())
            }
            yearFilter?.takeIf { it.state != 0 }?.let {
                urlBuilder.addQueryParameter("anio", it.toUriPart())
            }
            statusFilter?.takeIf { it.state != 0 }?.let {
                urlBuilder.addQueryParameter("estado", it.toUriPart())
            }
            orderFilter?.takeIf { it.state != 0 }?.let {
                urlBuilder.addQueryParameter("sort", it.toUriPart())
            }
        }

        if (page > 1) {
            urlBuilder.addQueryParameter("pag", page.toString())
        }

        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        OrderFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("Todos", ""),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Fantasía", "fantasia"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "ciencia-ficcion"),
            Pair("Seinen", "seinen"),
            Pair("Shounen", "shounen"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(
            Pair("Todos", ""),
            Pair("2026", "2026"), Pair("2025", "2025"), Pair("2024", "2024"),
            Pair("2023", "2023"), Pair("2022", "2022"), Pair("2021", "2021"),
            Pair("2020", "2020"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("Todos", ""),
            Pair("En emisión", "en-emision"),
            Pair("Finalizado", "finalizado"),
        ),
    )

    // State 0 = "Predeterminado" para que "Recientes" (state 1) SÍ se envíe.
    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Predeterminado", ""),
            Pair("Recientes", "newest"),
            Pair("Popularidad", "views"),
            Pair("A-Z", "asc"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = if (state > 0) vals[state].second else ""
    }

    // ============================== Anime Details ===============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.info-a .c h1")?.text()?.trim()
            ?: throw Exception("AnimeID: title not found")
        anime.thumbnail_url = document.selectFirst("div.info-a .i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() }
                ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }
        anime.description = document.selectFirst("div.info-a .c .tx p")?.text()?.trim()
        anime.genre = document.select("div.info-a .c .gn li a").joinToString { it.text() }


        val statusText = document
            .selectFirst("div.info-b .dv ul li strong.ee, div.info-b .dv ul li strong.ef")
            ?.text()?.trim()?.stripAccents()
        anime.status = when {
            statusText?.contains("emision", ignoreCase = true) == true -> SAnime.ONGOING
            statusText?.contains("finalizada", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    // ============================== Episodes ===============================
    override fun episodeListSelector(): String = "ul.epis li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.selectFirst("a")
            ?: throw Exception("AnimeID: no <a> in episode")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid episode href '${linkElement.attr("href")}'")
        val episodeNumber = httpUrl.encodedPath.substringAfterLast("-").toFloatOrNull() ?: 1f
        episode.setUrlWithoutDomain(
            httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: ""),
        )
        val episodeDisplay = if (episodeNumber == episodeNumber.toInt().toFloat()) {
            episodeNumber.toInt().toString()
        } else {
            episodeNumber.toString()
        }
        episode.name = "Episodio $episodeDisplay"
        episode.episode_number = episodeNumber
        return episode
    }

    override suspend fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val animeId = doc.selectFirst("#dt")?.attr("data-i") ?: return emptyList()
        val animeSlug = doc.selectFirst("#dt")?.attr("data-u") ?: return emptyList()

        val allEpisodes = mutableListOf<SEpisode>()
        val seenUrls = mutableSetOf<String>()
        var currentPage = 1

        while (true) {
            currentCoroutineContext().ensureActive()
            val formBody = FormBody.Builder()
                .add("acc", "episodes")
                .add("i", animeId)
                .add("u", animeSlug)
                .add("p", currentPage.toString())
                .build()

            val pageDoc = postToIdEndpoint(formBody)
            val episodesOnPage = pageDoc.select(episodeListSelector()).map { episodeFromElement(it) }

            if (episodesOnPage.isEmpty()) break


            val pageUrls = episodesOnPage.map { it.url }.toSet()
            if (pageUrls.all { it in seenUrls }) break

            allEpisodes.addAll(episodesOnPage)
            seenUrls.addAll(pageUrls)
            currentPage++
            delay(500)
        }
        return allEpisodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    // ============================== Videos ===============================
    private suspend fun serverVideoResolver(url: String): List<Video> {
        if (!isSafeUrlString(url)) return emptyList()
        val embedUrl = url.lowercase()
        return try {
            when {
                embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url, prefix = "Voe")
                embedUrl.contains("mp4upload") ->
                    mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload")
                embedUrl.contains("uqload") ->
                    uqloadExtractor.videosFromUrl(url, prefix = "Uqload")
                streamWishHosts.any { embedUrl.contains(it) } ->
                    streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                universalExtractor.videosFromUrl(url, headers)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {

                emptyList()
            }
        }
    }

    override suspend fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.selectFirst("ul.opt[data-encrypt]")?.let { optElement ->
            currentCoroutineContext().ensureActive()
            val encryptedId = optElement.attr("data-encrypt")
            val formBody = FormBody.Builder()
                .add("acc", "opt")
                .add("i", encryptedId)
                .build()

            val serversDoc = try {
                postToIdEndpoint(formBody)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (serversDoc != null) {
                for (pageUrl in parseServerUrls(serversDoc)) {
                    currentCoroutineContext().ensureActive()
                    try {
                        videos.addAll(serverVideoResolver(pageUrl))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {

                    }
                }
                if (videos.isNotEmpty()) return orderVideosByPreferences(videos)
            }
        }


        val scripts: Elements = document.select("#partes div.container li.subtab div.parte")
        for (script in scripts) {
            currentCoroutineContext().ensureActive()
            val jsonString = script.attr("data")
            val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
            val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
            if (url.isNotBlank() && isSafeUrlString(url)) {
                try {
                    videos.addAll(serverVideoResolver(url))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {

            }
        }

        return orderVideosByPreferences(videos)
    }

    private suspend fun postToIdEndpoint(formBody: FormBody): Document {
        val request = Request.Builder()
            .url("$baseUrl/id")
            .post(formBody)
            .headers(headers)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$baseUrl/")
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("AnimeID: POST $baseUrl/id returned HTTP ${response.code}")
        }
        return response.asJsoup()
    }

    private fun parseServerUrls(document: Document): List<String> = buildList {
        for (item in document.select("li")) {
            val encryptedUrl = item.attr("encrypt")
            if (encryptedUrl.isEmpty()) continue
            val decodedUrl = try {
                encryptedUrl.decodeHexToString()
            } catch (e: Exception) {

                continue
            }
            if (decodedUrl.isNotEmpty() && isSafeUrlString(decodedUrl)) {
                add(decodedUrl)
            }
        }
    }

    private fun orderVideosByPreferences(videos: List<Video>): List<Video> {
        val preferredServer = preferences.getString("animeid_preferred_server", "Voe")?.lowercase()
        val preferredQuality = preferences.getString("animeid_preferred_quality", "any")?.lowercase()

        fun detectServer(video: Video): String = when {
            video.videoTitle.startsWith("Voe", ignoreCase = true) -> "voe"
            video.videoTitle.startsWith("Mp4upload", ignoreCase = true) -> "mp4upload"
            video.videoTitle.startsWith("Uqload", ignoreCase = true) -> "uqload"
            video.videoTitle.startsWith("StreamWish", ignoreCase = true) -> "streamwish"
            else -> "other"
        }

        fun qualityScore(video: Video): Int {
            if (preferredQuality.isNullOrBlank() ||
                preferredQuality == "any" ||
                preferredQuality == "automatic"
            ) {
                return 0
            }
            val qToken = "${preferredQuality}p"
            return when {
                video.videoTitle.contains(qToken, ignoreCase = true) -> 3
                video.videoTitle.contains(preferredQuality, ignoreCase = true) -> 2
                video.videoUrl.contains("/$qToken", ignoreCase = true) -> 3
                else -> 0
            }
        }

        val grouped = videos.groupBy { detectServer(it) }
        val order = mutableListOf<Video>()

        if (!preferredServer.isNullOrBlank()) {
            grouped[preferredServer]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        for (srv in listOf("voe", "mp4upload", "uqload", "streamwish", "other")) {
            if (srv == preferredServer) continue
            grouped[srv]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        val addedUrls = order.map { it.videoUrl }.toSet()
        videos.filter { it.videoUrl !in addedUrls }.let { leftovers ->
            order.addAll(leftovers.sortedByDescending { qualityScore(it) })
        }

        return order
    }

    // ============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "animeid_preferred_server"
            title = "Preferred server"
            entries = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            entryValues = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish")
            setDefaultValue("Voe")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "animeid_preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Automatic", "480p", "720p", "1080p")
            entryValues = arrayOf("any", "480", "720", "1080")
            setDefaultValue("any")
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Utilities ===============================
    private fun unescapeJava(escaped: String): String =
        unicodeEscapeRegex.replace(escaped) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return urlRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    private fun String.stripAccents(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarksRegex, "")

    // ============================== SSRF protection ===============================
    private fun isSafeUrlString(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return isSafeUrl(parsed)
    }

    private fun isSafeUrl(url: HttpUrl): Boolean {
        if (url.scheme != "http" && url.scheme != "https") return false
        val host = url.host.lowercase()
        if (host.isEmpty()) return false

        if (host == "localhost" || host == "localhost.localdomain") return false
        if (host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.endsWith(".lan") || host.endsWith(".home.arpa")) return false
        if (host == "metadata.google.internal" || host == "metadata") return false

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.isNotEmpty() && addresses.all { isPublicIp(it) }
    }

    private fun isPublicIp(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress) return false
        if (addr.isLoopbackAddress) return false
        if (addr.isLinkLocalAddress) return false
        if (addr.isSiteLocalAddress) return false
        if (addr.isMulticastAddress) return false

        return when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                val b2 = b[2].toInt() and 0xff
                when {
                    b0 == 0 -> false                                              // 0.0.0.0/8
                    b0 == 100 && b1 in 64..127 -> false                            // 100.64.0.0/10 CGNAT
                    b0 == 192 && b1 == 0 && b2 == 0 -> false                       // 192.0.0.0/24
                    b0 == 192 && b1 == 0 && b2 == 2 -> false                       // TEST-NET-1
                    b0 == 198 && (b1 == 18 || b1 == 19) -> false                   // 198.18.0.0/15
                    b0 == 198 && b1 == 51 && b2 == 100 -> false                    // TEST-NET-2
                    b0 == 203 && b1 == 0 && b2 == 113 -> false                     // TEST-NET-3
                    b0 >= 240 -> false                                             // 240/4 + broadcast
                    else -> true
                }
            }
            is Inet6Address -> {
                val b0 = addr.address[0].toInt() and 0xff
                if (b0 and 0xfe == 0xfc) return false   // fc00::/7 unique local
                true
            }
            else -> false
        }
    }
}
