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
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpLegacySource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeID :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // Precompiled regexes (evita rebuild por llamada)
    private val urlRegex =
        """(http|ftp|https)://([\w_-]+(?:(?:\.[\w_-]+)+))([\w.,@?^=%&:/~+#-]*[\w@?^=%&/~+#-])""".toRegex()
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.ul.x5 article.li, div.ul article.li"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series?pag=$page"
        return GET(url, headers)
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
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
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

    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
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
        val statusText = document.selectFirst("div.info-b .dv ul li strong.ee, div.info-b .dv ul li strong.ef")
            ?.text()?.trim()
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

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val doc = response.asJsoup()
        val animeId = doc.selectFirst("#dt")?.attr("data-i")
            ?: return@runBlocking emptyList()
        val animeSlug = doc.selectFirst("#dt")?.attr("data-u")
            ?: return@runBlocking emptyList()

        val allEpisodes = mutableListOf<SEpisode>()
        var currentPage = 1
        val maxPages = 200

        while (currentPage <= maxPages) {
            val formBody = FormBody.Builder()
                .add("acc", "episodes")
                .add("i", animeId)
                .add("u", animeSlug)
                .add("p", currentPage.toString())
                .build()
            val pageDoc = postToIdEndpoint(formBody) ?: break
            val episodesOnPage = pageDoc.select(episodeListSelector()).map { episodeFromElement(it) }
            if (episodesOnPage.isEmpty()) break
            allEpisodes.addAll(episodesOnPage)
            currentPage++
            delay(100)
        }
        allEpisodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    // ============================== Videos ===============================
    private suspend fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return try {
            when {
                embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url, prefix = "Voe")
                embedUrl.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload")
                embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload")
                embedUrl.contains("streamwish") -> streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.selectFirst("ul.opt[data-encrypt]")?.let { optElement ->
            val encryptedId = optElement.attr("data-encrypt")
            val formBody = FormBody.Builder()
                .add("acc", "opt")
                .add("i", encryptedId)
                .build()
            postToIdEndpoint(formBody)?.let { serversDoc ->
                for (pageUrl in parseServerUrls(serversDoc)) {
                    videos.addAll(serverVideoResolver(pageUrl))
                }
                if (videos.isNotEmpty()) return@runBlocking orderVideosByPreferences(videos)
            }
        }

        // Fallback: parse server URLs from tab data (siempre se ejecuta si no hubo early-return)
        document.select("#partes div.container li.subtab div.parte").forEach { script ->
            val jsonString = script.attr("data")
            val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
            val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
            if (url.isNotBlank()) {
                videos.addAll(serverVideoResolver(url))
            }
        }

        orderVideosByPreferences(videos)
    }

    /**
     * Helper único para los POST a `$baseUrl/id`. Devuelve null en IO error o respuesta
     * no-2xx; `CancellationException` se re-lanza para no romper la cancelación de coroutines.
     */
    private fun postToIdEndpoint(formBody: FormBody): Document? {
        val request = Request.Builder()
            .url("$baseUrl/id")
            .post(formBody)
            .headers(headers)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$baseUrl/")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.asJsoup()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun parseServerUrls(document: Document): List<String> = buildList {
        for (item in document.select("li")) {
            val encryptedUrl = item.attr("encrypt")
            if (encryptedUrl.isNotEmpty()) {
                val decodedUrl = encryptedUrl.decodeHexToString()
                if (decodedUrl.isNotEmpty()) add(decodedUrl)
            }
        }
    }

    private fun orderVideosByPreferences(videos: List<Video>): List<Video> {
        val preferredServer = preferences.getString("animeid_preferred_server", "Voe")?.lowercase()
        val preferredQuality = preferences.getString("animeid_preferred_quality", "any")?.lowercase()

        // Usa el prefijo que ya puso serverVideoResolver (Voe/Mp4upload/Uqload/StreamWish)
        // para no re-matchear hosts.
        fun detectServer(video: Video): String = when {
            video.quality.startsWith("Voe", ignoreCase = true) -> "voe"
            video.quality.startsWith("Mp4upload", ignoreCase = true) -> "mp4upload"
            video.quality.startsWith("Uqload", ignoreCase = true) -> "uqload"
            video.quality.startsWith("StreamWish", ignoreCase = true) -> "streamwish"
            else -> "other"
        }

        fun qualityScore(video: Video): Int {
            // "automatic" queda por compat. con prefs guardadas viejas.
            if (preferredQuality.isNullOrBlank() ||
                preferredQuality == "any" ||
                preferredQuality == "automatic"
            ) {
                return 0
            }
            val qToken = "${preferredQuality}p"
            return when {
                video.quality.contains(qToken, ignoreCase = true) -> 3
                video.quality.contains(preferredQuality, ignoreCase = true) -> 2
                video.url.contains("/$qToken", ignoreCase = true) -> 3
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

        val addedUrls = order.map { it.url }.toSet()
        videos.filter { it.url !in addedUrls }.let { leftovers ->
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

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
