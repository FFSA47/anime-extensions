package eu.kanade.tachiyomi.animeextension.es.animeneon

import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Animeneon :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val id = 6957694006954649297L
    override val lang = "es"
    override val supportsLatest = true

    // Cliente personalizado con User-Agent y Accept-Language mediante interceptor
    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    private val preferences by getPreferencesLazy()
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private val prefServerKey = "preferred_server"
    private val prefQualityKey = "preferred_quality"

    private val defaultServer = "Mp4upload"
    private val defaultQuality = "1080p"

    private val serverList = arrayOf(
        "Mp4upload",
        "Voe",
        "Mixdrop",
        "Streamtape",
        "Lulu",
    )

    private val qualityList = arrayOf(
        "1080p",
        "720p",
        "480p",
    )

    private val qualityRegex = Regex("""(\d+)p""")

    private val voeExtractor by lazy {
        VoeExtractor(client, headers)
    }

    private val mixdropExtractor by lazy {
        MixDropExtractor(client)
    }

    private val streamTapeExtractor by lazy {
        StreamTapeExtractor(client)
    }

    private val mp4uploadExtractor by lazy {
        Mp4uploadExtractor(client)
    }

    private val luluExtractor by lazy {
        LuluExtractor(client, headers)
    }

    private val universalExtractor by lazy {
        UniversalExtractor(client)
    }

    private val serverConventions = listOf(
        "mp4upload" to listOf(
            "mp4upload",
            "mp4upload.com",
        ),
        "voe" to listOf(
            "voe",
            "voe.com",
            "voe.sx",
        ),
        "mixdrop" to listOf(
            "mixdrop",
            "mixdrop.co",
            "mixdrop.ag",
            "mixdrop.top",
        ),
        "streamtape" to listOf(
            "streamtape",
            "streamtape.com",
        ),
        "lulu" to listOf(
            "lulu",
            "lulustream",
            "lulustream.com",
            "luluvdo",
        ),
    )

    // ====================== POPULAR ======================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/nuevo-anime?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.grid a[href^=/anime/]")
        val animeList = elements.mapNotNull { parseAnimeCard(it) }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ====================== ÚLTIMOS EPISODIOS ======================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/episodios?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val episodeItems = document.select("div.grid a[href^=/ver/]")
        val animeList = episodeItems.mapNotNull { link ->
            val episodeUrl = link.attr("abs:href")
            val episodeSlug = episodeUrl.substringAfter("/ver/")
            val animeSlug = episodeSlug.replace(Regex("-\\d+\\.[A-Za-z0-9]+$"), "")
            val animeUrl = "/anime/$animeSlug"
            val titleElement = link.selectFirst(".text-\\[13px\\].font-semibold, .line-clamp-1")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val img = link.selectFirst("img")
            val imageUrl = img?.attr("abs:src")
            SAnime.create().apply {
                url = animeUrl
                this.title = title
                thumbnail_url = imageUrl
                description = "Último episodio disponible"
            }
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ====================== BÚSQUEDA ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ====================== DETALLE ======================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".a2-title")?.text()?.trim()
                ?: document.selectFirst("h1, h2.text-2xl")?.text()?.trim()
                ?: "Sin título"
            thumbnail_url = document.selectFirst(".a2-poster-img")?.attr("abs:src")
                ?: document.selectFirst("img[src*=/uploads/anime/]")?.attr("abs:src")
            description = document.selectFirst(".a2-desc-text")?.text()?.trim()
                ?: document.selectFirst("p.text-sm.leading-relaxed, .sinopsis")?.text()?.trim()
            genre = document.select(".a2-genre-tag").joinToString(", ") { it.text().trim() }
            val statusElement = document.selectFirst(".a2-badge-status")
            val statusText = statusElement?.text()?.trim()
            status = when {
                statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
                statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ====================== EPISODIOS ======================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        var totalEpisodes = 0
        val paginationInfo = document.selectFirst(".a2-pagination-info")?.text()
        if (paginationInfo != null) {
            val match = Regex("de\\s+(\\d+)").find(paginationInfo)
            totalEpisodes = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        if (totalEpisodes == 0) {
            val infoRows = document.select(".a2-info-row")
            for (row in infoRows) {
                val label = row.selectFirst(".a2-info-row-label")?.text()
                if (label?.contains("Episodios", ignoreCase = true) == true) {
                    val value = row.selectFirst(".a2-info-row-val")?.text()
                    totalEpisodes = value?.toIntOrNull() ?: 0
                    break
                }
            }
        }

        val path = response.request.url.pathSegments
        val lastSegment = path.lastOrNull() ?: return emptyList()
        val animeSlug = lastSegment.substringBefore(".")
        val nanoid = lastSegment.substringAfter(".")

        if (totalEpisodes > 0) {
            for (i in totalEpisodes downTo 1) {
                SEpisode.create().apply {
                    url = "/ver/$animeSlug-$i.$nanoid"
                    name = "Episodio $i"
                    episode_number = i.toFloat()
                }.also { episodes.add(it) }
            }
            return episodes
        }

        val episodeElements = document.select(".a2-ep-list a.a2-ep-card")
        return episodeElements.mapNotNull { element ->
            val href = element.attr("href")
            val number = extractEpisodeNumber(href) ?: return@mapNotNull null
            val title = element.selectFirst(".a2-ep-title")?.text()?.trim() ?: "Episodio $number"
            SEpisode.create().apply {
                url = href
                name = title
                episode_number = number
            }
        }.sortedByDescending { it.episode_number }
    }

    // ====================== VIDEOS ======================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val allVideos = mutableListOf<Video>()
        val seenUrls = mutableSetOf<String>()

        // Método 1: Script 'groups'
        val script = document.select("script:containsData(groups)").firstOrNull()
        if (script != null) {
            val scriptData = script.data()
            val groupsArray = extractArrayAfterKey(scriptData, "groups")
            if (groupsArray != null) {
                var searchPosition = 0
                while (searchPosition < groupsArray.length) {
                    val serversStart = findKey(groupsArray, "servers", searchPosition)
                    if (serversStart == -1) break
                    val serversArray = extractArrayAt(groupsArray, serversStart)
                    if (serversArray == null) {
                        searchPosition = serversStart + 7
                        continue
                    }
                    val serverObjects = extractObjects(serversArray)
                    for (serverObj in serverObjects) {
                        val link = extractStringProperty(serverObj, "link") ?: continue
                        val hostKey = extractStringProperty(serverObj, "hostKey") ?: continue
                        if (hostKey.contains("mega.nz", ignoreCase = true) ||
                            link.contains("mega.nz", ignoreCase = true)
                        ) {
                            searchPosition = serversStart + 7
                            continue
                        }
                        val videos = resolveServer(link, hostKey)
                        for (video in videos) {
                            if (seenUrls.add(video.url)) {
                                allVideos.add(video)
                            }
                        }
                    }
                    searchPosition = serversStart + serversArray.length
                }
            }
        }

        // Método 2: Iframe (reproductor intermedio)
        if (allVideos.isEmpty()) {
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("abs:src")
                val videos = resolveIframe(iframeUrl)
                for (video in videos) {
                    if (seenUrls.add(video.url)) {
                        allVideos.add(video)
                    }
                }
            }
        }

        return allVideos
    }

    // ====================== RESOLVER SERVIDOR ======================

    private fun resolveServer(url: String, hostKey: String): List<Video> {
        val normalizedHost = hostKey.lowercase()
        val matchedServer = serverConventions.firstOrNull { (_, aliases) ->
            aliases.any { alias -> normalizedHost.contains(alias.lowercase()) }
        }?.first
        val fallbackServer = matchedServer ?: serverConventions.firstOrNull { (_, aliases) ->
            aliases.any { alias -> url.contains(alias, ignoreCase = true) }
        }?.first

        return when (fallbackServer) {
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers = headers, prefix = "Mp4upload - ")
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "Voe - ")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url, prefix = "Mixdrop - ", lang = "es")
            "streamtape" -> {
                val video = streamTapeExtractor.videoFromUrl(url, quality = "Streamtape")
                if (video != null) listOf(video) else emptyList()
            }
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "Lulu - ")
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    // ====================== RESOLVER IFRAME ======================

    private fun resolveIframe(url: String): List<Video> {
        return when {
            url.contains("multiserver.sbs") -> {
                decryptMultiserver(url)
            }
            else -> {
                universalExtractor.videosFromUrl(url, headers)
            }
        }
    }

    private fun decryptMultiserver(iframeUrl: String): List<Video> {
        try {
            val embedId = iframeUrl.substringAfterLast("/")
            if (embedId.isEmpty()) return emptyList()

            val iframeResponse = client.newCall(GET(iframeUrl, headers)).execute()
            iframeResponse.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val document = resp.asJsoup()
                val playerItems = document.select("li[onclick^=go_to_player]")
                if (playerItems.isEmpty()) return emptyList()

                val encryptedStrings = playerItems.mapNotNull { element ->
                    val onclick = element.attr("onclick")
                    val regex = Regex("""go_to_player\s*\(\s*['"]([^'"]+)['"]\s*\)""")
                    regex.find(onclick)?.groupValues?.get(1)
                }
                if (encryptedStrings.isEmpty()) return emptyList()

                val allVideos = mutableListOf<Video>()
                val seenUrls = mutableSetOf<String>()

                for (encrypted in encryptedStrings) {
                    try {
                        val apiUrl = "https://multiserver.sbs/embed/api/decrypt-stream"
                        val requestBody = """{"encrypted":"$encrypted"}"""
                        // Construir headers específicos para la API (el User-Agent ya lo pone el interceptor)
                        val apiHeaders = Headers.Builder()
                            .set("Content-Type", "application/json")
                            .set("Referer", "https://multiserver.sbs/")
                            .set("Origin", "https://multiserver.sbs")
                            .build()
                        val apiRequest = POST(
                            apiUrl,
                            headers = apiHeaders,
                            body = requestBody.toRequestBody("application/json".toMediaType()),
                        )
                        val apiResponse = client.newCall(apiRequest).execute()
                        apiResponse.use { apiResp ->
                            if (apiResp.isSuccessful) {
                                val jsonString = apiResp.body?.string() ?: ""
                                val videoUrl = run {
                                    val dataUrlRegex = Regex(""""data"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)""")
                                    dataUrlRegex.find(jsonString)?.groupValues?.get(1)
                                        ?: Regex(""""url"\s*:\s*"([^"]+)""").find(jsonString)?.groupValues?.get(1)
                                        ?: Regex(""""url"\s*:\s*'([^']+)'""").find(jsonString)?.groupValues?.get(1)
                                }
                                if (videoUrl != null && seenUrls.add(videoUrl)) {
                                    val videos = resolveServer(videoUrl, detectHostKey(videoUrl))
                                    allVideos.addAll(videos)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continuar con el siguiente
                    }
                }
                return allVideos
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun detectHostKey(url: String): String {
        return when {
            url.contains("byseqekaho.com") -> "byseqekaho.com"
            url.contains("voe.sx") -> "voe.sx"
            url.contains("mp4upload.com") -> "mp4upload.com"
            url.contains("dsvplay.com") -> "dsvplay.com"
            url.contains("vidnest.io") -> "vidnest.io"
            url.contains("mixdrop") -> "mixdrop.top"
            else -> "unknown"
        }
    }

    // ====================== PARSER JAVASCRIPT ======================

    private fun extractArrayAfterKey(text: String, key: String): String? {
        val position = findKey(text, key, 0)
        if (position == -1) return null
        return extractArrayAt(text, position)
    }

    private fun findKey(text: String, key: String, start: Int): Int {
        val pattern = Regex("""(?:"|')?${Regex.escape(key)}(?:"|')?\s*:""")
        return pattern.find(text, start)?.range?.first ?: -1
    }

    private fun extractArrayAt(text: String, keyPosition: Int): String? {
        val colon = text.indexOf(':', keyPosition)
        if (colon == -1) return null
        var start = colon + 1
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length || text[start] != '[') return null
        val end = findMatchingBracket(text, start, '[', ']') ?: return null
        return text.substring(start, end + 1)
    }

    private fun findMatchingBracket(text: String, start: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var inString = false
        var stringChar = '\u0000'
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (char == '\\') {
                    escaped = true
                    continue
                }
                if (char == stringChar) {
                    inString = false
                }
                continue
            }
            if (char == '"' || char == '\'') {
                inString = true
                stringChar = char
                continue
            }
            if (char == openChar) {
                depth++
            } else if (char == closeChar) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    private fun extractObjects(arrayText: String): List<String> {
        val objects = mutableListOf<String>()
        var position = 0
        while (position < arrayText.length) {
            val start = arrayText.indexOf('{', position)
            if (start == -1) break
            val end = findMatchingBracket(arrayText, start, '{', '}') ?: break
            objects.add(arrayText.substring(start, end + 1))
            position = end + 1
        }
        return objects
    }

    private fun extractStringProperty(text: String, key: String): String? {
        val regex = Regex("""(?:"|')?${Regex.escape(key)}(?:"|')?\s*:\s*["']([^"']+)["']""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    // ====================== ORDENAMIENTO ======================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, defaultQuality) ?: defaultQuality
        val server = preferences.getString(prefServerKey, defaultServer) ?: defaultServer
        return sortedWith(
            compareBy(
                { !it.quality.contains(server, ignoreCase = true) },
                { !it.quality.contains(quality, ignoreCase = true) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        )
    }

    // ====================== FILTROS ======================

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            Filters.GenreFilter(),
            Filters.ThemeFilter(),
            Filters.DemographicFilter(),
            Filters.YearFilter(),
            Filters.SeasonFilter(),
            Filters.FormatFilter(),
            Filters.StatusFilter(),
            Filters.LanguageFilter(),
            Filters.OrderFilter(),
        )
    }

    // ====================== PREFERENCIAS ======================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = prefServerKey
            title = ""
            summary = ""
            entries = serverList
            entryValues = serverList
            setDefaultValue(defaultServer)
            value = preferences.getString(prefServerKey, defaultServer)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(prefServerKey, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = prefQualityKey
            title = "Preferred Quality"
            summary = "Select the quality to show first"
            entries = qualityList
            entryValues = qualityList
            setDefaultValue(defaultQuality)
            value = preferences.getString(prefQualityKey, defaultQuality)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(prefQualityKey, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ====================== AUXILIARES ======================

    private fun parseAnimeCard(element: Element): SAnime? {
        val href = element.attr("href")
        if (!href.startsWith("/anime/")) return null
        val title = element.selectFirst("h3.text-\\[13px\\].font-semibold")?.text()?.trim()
            ?: element.selectFirst("h3")?.text()?.trim()
            ?: return null
        val img = element.selectFirst("img")
        val imageUrl = img?.attr("abs:src")
        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = imageUrl
        }
    }

    private fun extractEpisodeNumber(url: String): Float? {
        val regex = Regex("-(\\d+)\\.")
        return regex.find(url)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
