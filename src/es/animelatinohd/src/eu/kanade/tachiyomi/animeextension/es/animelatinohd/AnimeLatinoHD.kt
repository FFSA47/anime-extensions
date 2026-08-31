package eu.kanade.tachiyomi.animeextension.es.animelatinohd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeLatinoHD :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeLatinoHD"
    override val baseUrl = "https://www.animelatinohd.com"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Delta"
        private val SERVER_LIST = arrayOf("Gamma", "Delta", "Epsilon")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[ESP]", "[SUB]")

        // Voe domains (includes random domains like tracylocalschool.com)
        private val VOE_DOMAINS = listOf(
            "voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath",
            "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse",
            "tracylocalschool", "school", "loca", "voe-", "voe.",
        )

        private val FILEMOON_DOMAINS = listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx")
        private val LULU_DOMAINS = listOf("luluvdo", "lulu", "lulustream")
    }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy {
        val luluHeaders = headers.newBuilder()
            .add("Referer", "https://luluvdo.com/")
            .add("Origin", "https://luluvdo.com")
            .build()
        LuluExtractor(client, luluHeaders)
    }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")

    // ====================== Popular ======================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/directorio" else "$baseUrl/directorio?page=$page"
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        document.select("a[href^=\"/anime/\"]").forEach { link ->
            val href = link.attr("href")
            if (href.isBlank()) return@forEach

            val img = link.select("img").first()
            val poster = img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("anime.png") }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: ""

            val titleElement = link.select("h3").first()
            val title = titleElement?.text()?.trim() ?: return@forEach

            val yearElement = link.select("p").first()
            val year = yearElement?.text()?.trim() ?: ""

            val anime = SAnime.create().apply {
                setUrlWithoutDomain(href)
                thumbnail_url = poster
                this.title = title
                description = year
            }
            animeList.add(anime)
        }

        if (animeList.isEmpty()) {
            val dataObj = extractDataObject(document.html())
            val items = dataObj?.get("data")?.jsonArray
            items?.forEach { item ->
                val animeItem = item.jsonObject
                val anime = SAnime.create().apply {
                    setUrlWithoutDomain("/anime/${animeItem["slug"]?.jsonPrimitive?.content ?: return@forEach}")
                    thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]?.jsonPrimitive?.content ?: ""}"
                    title = animeItem["name"]?.jsonPrimitive?.content ?: ""
                }
                animeList.add(anime)
            }
        }

        val nextPage = document.selectFirst("a[href*=\"page=\"]:not([href*=\"page=1\"])") != null
        return AnimesPage(animeList, nextPage)
    }

    // ====================== Latest ======================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeMap = mutableMapOf<String, SAnime>()

        document.select("a[href^=\"/ver/\"]").forEach { link ->
            val href = link.attr("href")
            val parts = href.split("/")
            if (parts.size < 3) return@forEach
            val slug = parts[2]
            if (animeMap.containsKey(slug)) return@forEach

            val img = link.select("img").first()
            val poster = img?.attr("src") ?: ""
            val title = link.select("h3, .title, [class*=\"title\"]").first()?.text() ?: ""

            val anime = SAnime.create().apply {
                setUrlWithoutDomain("/anime/$slug")
                thumbnail_url = poster
                this.title = title
            }
            animeMap[slug] = anime
        }

        if (animeMap.isEmpty()) {
            val dataObj = extractDataObject(document.html())
            val episodes = dataObj?.get("episodes")?.jsonArray
                ?: dataObj?.get("latest_episodes")?.jsonArray

            episodes?.forEach { episodeElement ->
                val episodeObj = episodeElement.jsonObject
                val animeData = episodeObj["anime"]?.jsonObject ?: return@forEach
                val slug = animeData["slug"]?.jsonPrimitive?.content ?: return@forEach
                if (animeMap.containsKey(slug)) return@forEach
                val anime = SAnime.create().apply {
                    setUrlWithoutDomain("/anime/$slug")
                    thumbnail_url = "https://image.tmdb.org/t/p/w200${animeData["poster"]?.jsonPrimitive?.content ?: ""}"
                    title = animeData["name"]?.jsonPrimitive?.content ?: ""
                }
                animeMap[slug] = anime
            }
        }

        return AnimesPage(animeMap.values.toList(), false)
    }

    // ====================== Details ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        // Try JSON-LD schema first
        document.select("script[type=\"application/ld+json\"]").forEach { script ->
            try {
                val jsonLd = json.decodeFromString<JsonObject>(script.data())
                val name = jsonLd["name"]?.jsonPrimitive?.content
                if (!name.isNullOrBlank()) {
                    anime.title = name
                    anime.description = jsonLd["description"]?.jsonPrimitive?.content ?: ""
                    anime.genre = jsonLd["genre"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
                    anime.thumbnail_url = jsonLd["image"]?.jsonPrimitive?.content ?: ""
                }
            } catch (_: Exception) { /* ignore */ }
        }

        // Extract from Next.js streaming data
        val dataObj = extractDataObject(document.html())
        val animeData = dataObj?.get("anime")?.jsonObject

        if (animeData != null) {
            anime.title = animeData["name"]?.jsonPrimitive?.content ?: anime.title
            anime.genre = animeData["genres"]?.jsonPrimitive?.content ?: anime.genre
            anime.description = animeData["overview"]?.jsonPrimitive?.content ?: anime.description
            anime.status = when (animeData["status"]?.jsonPrimitive?.content) {
                "1" -> SAnime.ONGOING
                "0" -> SAnime.COMPLETED
                else -> anime.status
            }
            val poster = animeData["poster"]?.jsonPrimitive?.content
            if (!poster.isNullOrBlank()) {
                anime.thumbnail_url = if (poster.startsWith("http")) poster else "https://image.tmdb.org/t/p/w600_and_h900_bestv2$poster"
            }
        }

        // Fallback to HTML selectors
        if (anime.title.isBlank()) {
            anime.title = document.selectFirst("h1.fs-2, h1, .title, [class*=\"title\"]")?.text()?.trim() ?: "Unknown Title"
        }
        if (anime.thumbnail_url.isNullOrBlank()) {
            anime.thumbnail_url = document.selectFirst("img[src*=\"tmdb\"], img[src*=\"poster\"], .poster img")?.attr("src") ?: ""
        }
        if (anime.description.isNullOrBlank()) {
            anime.description = document.selectFirst("p.description, .description, .sinopsis, [class*=\"sinopsis\"], [class*=\"description\"]")?.text()?.trim() ?: ""
        }
        if (anime.genre.isNullOrBlank()) {
            anime.genre = document.select(".badge.bg-secondary, .genre, [class*=\"genre\"]").joinToString(", ") { it.text() }
        }
        if (anime.status == SAnime.UNKNOWN) {
            val statusText = document.selectFirst(".col:has(.text-muted:contains(Estado)) div.ms-2 div:last-child, [class*=\"status\"]")?.text()
            anime.status = when {
                statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
                statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        return anime
    }

    // ====================== Episodes ======================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val dataObj = extractDataObject(document.html())
        val episodes = dataObj?.get("episodes")?.jsonArray

        episodes?.forEach { item ->
            val episodeObj = item.jsonObject
            val number = episodeObj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@forEach
            val slug = dataObj["anime"]?.jsonObject?.get("slug")?.jsonPrimitive?.content ?: ""
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain("/ver/$slug/$number")
                episode_number = number
                name = "Episodio $number"
            }
            episodeList.add(episode)
        }

        if (episodeList.isEmpty()) {
            document.select("a[href^=\"/ver/\"]").forEach { link ->
                val href = link.attr("href")
                val parts = href.split("/")
                if (parts.size < 3) return@forEach
                val number = parts.getOrNull(3)?.toFloatOrNull() ?: return@forEach
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = number
                    name = "Episodio $number"
                }
                episodeList.add(episode)
            }
        }

        episodeList.sortByDescending { it.episode_number }
        return episodeList
    }

    // ====================== Video List ======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val dataObj = extractDataObject(document.html())
        val players = dataObj?.get("players")?.jsonArray

        players?.forEach { playerElement ->
            val playerObj = playerElement.jsonObject
            val languageCode = playerObj["language"]?.jsonPrimitive?.content ?: "SUB"
            val serverName = playerObj["server_name"]?.jsonPrimitive?.content ?: "Unknown"
            val bridgeUrl = playerObj["bridge_url"]?.jsonPrimitive?.content ?: return@forEach

            val language = when (languageCode) {
                "LAT" -> "[LAT]"
                "ESP" -> "[ESP]"
                "SUB" -> "[SUB]"
                else -> "[SUB]"
            }

            val prefix = "$language $serverName"
            val videos = extractVideosFromUrl(bridgeUrl, prefix)
            videoList.addAll(videos)
        }

        return videoList
    }

    // ====================== Video Extraction ======================

    /**
     * Extracts videos from a bridge URL.
     * Strategy:
     * 1. Fetch bridge with OkHttp (uses session cookies automatically)
     * 2. Parse iframe src from bridge HTML
     * 3. Detect extractor by iframe domain
     * 4. Pass iframe URL to correct extractor
     * 5. Fallback to UniversalExtractor if anything fails
     */
    private fun extractVideosFromUrl(bridgeUrl: String, prefix: String): List<Video> {
        // If not a bridge URL, try to detect extractor directly
        if (!bridgeUrl.contains("website.animelatinohd.com", ignoreCase = true)) {
            return extractWithExtractor(bridgeUrl, prefix)
        }

        try {
            // Step 1: Fetch bridge page with Referer from main site
            val bridgeHeaders = headers.newBuilder()
                .add("Referer", "$baseUrl/")
                .build()

            val request = GET(bridgeUrl, bridgeHeaders)
            val response = client.newCall(request).execute()

            // If we got redirected to a video host, extract from there
            val finalUrl = response.request.url.toString()
            if (!finalUrl.contains("website.animelatinohd.com", ignoreCase = true)) {
                return extractWithExtractor(finalUrl, prefix)
            }

            // Step 2: Parse bridge HTML for iframe
            val bridgeDoc = response.asJsoup()

            val iframeSrc = bridgeDoc.selectFirst("iframe[src]")?.attr("src")
                ?: bridgeDoc.selectFirst("iframe[data-src]")?.attr("data-src")
                ?: bridgeDoc.selectFirst("frame[src]")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val resolvedUrl = resolveUrl(iframeSrc, finalUrl)
                return extractWithExtractor(resolvedUrl, prefix)
            }

            // Step 3: Look for JS redirects in bridge
            val jsRedirects = listOf(
                """(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""".toRegex(),
                """location\.replace\(["']([^"']+)["']\)""".toRegex(),
            )
            val bodyHtml = bridgeDoc.html()
            for (pattern in jsRedirects) {
                pattern.find(bodyHtml)?.groupValues?.get(1)?.let { redirectUrl ->
                    if (!redirectUrl.startsWith("javascript:", ignoreCase = true)) {
                        return extractWithExtractor(resolveUrl(redirectUrl, finalUrl), prefix)
                    }
                }
            }

        } catch (_: Exception) { /* ignore and fallback */ }

        // Fallback: UniversalExtractor (WebView)
        return universalExtractor.videosFromUrl(bridgeUrl, headers, prefix = prefix)
    }

    private fun extractWithExtractor(url: String, prefix: String): List<Video> {
        val host = try {
            url.toHttpUrl().host.lowercase()
        } catch (_: Exception) {
            return emptyList()
        }

        val extractor = when {
            VOE_DOMAINS.any { it in host } -> "voe"
            FILEMOON_DOMAINS.any { it in host } -> "filemoon"
            LULU_DOMAINS.any { it in host } -> "lulu"
            else -> null
        }

        val videos = when (extractor) {
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "$prefix:")
            "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix:", headers = headers)
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "$prefix:")
            else -> universalExtractor.videosFromUrl(url, headers, prefix = prefix)
        }

        return if (videos.isNotEmpty()) videos else universalExtractor.videosFromUrl(url, headers, prefix = prefix)
    }

    private fun resolveUrl(url: String, base: String): String {
        return when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val baseUrl = base.toHttpUrl()
                "${baseUrl.scheme}://${baseUrl.host}$url"
            }
            else -> {
                val baseUrl = base.toHttpUrl()
                val basePath = baseUrl.encodedPath.substringBeforeLast('/') + "/"
                "${baseUrl.scheme}://${baseUrl.host}$basePath$url"
            }
        }
    }

    // ====================== Data Extraction Helpers ======================

    /**
     * Extracts the "data" JSON object from Next.js streaming HTML.
     * The data is embedded in self.__next_f.push scripts.
     */
    private fun extractDataObject(html: String): JsonObject? {
        val dataPattern = """"data"\s*:\s*\{""".toRegex()

        dataPattern.findAll(html).forEach { match ->
            val braceStart = html.indexOf('{', match.range.first)
            if (braceStart == -1) return@forEach

            val jsonStr = extractBalancedBraces(html, braceStart)
            if (jsonStr != null) {
                try {
                    val obj = json.decodeFromString<JsonObject>(jsonStr)
                    if (obj.containsKey("anime") || obj.containsKey("players") || obj.containsKey("episodes") || obj.containsKey("data")) {
                        return obj
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
        return null
    }

    private fun extractBalancedBraces(text: String, start: Int): String? {
        var count = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> count++
                '}' -> {
                    count--
                    if (count == 0) {
                        return text.substring(start, i + 1)
                    }
                }
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return null
    }

    // ====================== Sorting ======================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ====================== Search ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val stateFilter = filterList.find { it is StateFilter } as StateFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter

        val url = if (query.isNotBlank()) {
            val base = "$baseUrl/directorio?search=$query"
            if (page > 1) "$base&page=$page" else base
        } else {
            var base = "$baseUrl/directorio"
            val params = mutableListOf<String>()
            genreFilter.toUriPart().takeIf { it.isNotBlank() }?.let { params.add("genre=$it") }
            stateFilter.toUriPart().takeIf { it.isNotBlank() }?.let { params.add("status=$it") }
            typeFilter.toUriPart().takeIf { it.isNotBlank() }?.let { params.add("type=$it") }
            if (page > 1) params.add("page=$page")
            if (params.isNotEmpty()) base += "?" + params.joinToString("&")
            base
        }

        return GET(url)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        StateFilter(),
        TypeFilter(),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ====================== Filters ======================

    private class GenreFilter :
        UriPartFilter(
            "Genres",
            arrayOf(
                Pair("<Select>", ""),
                Pair("Action", "accion"),
                Pair("Aliens", "aliens"),
                Pair("Martial Arts", "artes-marciales"),
                Pair("Adventure", "aventura"),
                Pair("Sci-Fi", "ciencia-ficcion"),
                Pair("Comedy", "comedia"),
                Pair("Cyberpunk", "cyberpunk"),
                Pair("Demons", "demonios"),
                Pair("Sports", "deportes"),
                Pair("Detectives", "detectives"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("School", "escolar"),
                Pair("Space", "espacio"),
                Pair("Fantasy", "fantasia"),
                Pair("Gore", "gore"),
                Pair("Harem", "harem"),
                Pair("Historical", "historico"),
                Pair("Horror", "horror"),
                Pair("Josei", "josei"),
                Pair("Games", "juegos"),
                Pair("Kids", "kodomo"),
                Pair("Magic", "magia"),
                Pair("Mahou Shoujo", "maho-shoujo"),
                Pair("Mecha", "mecha"),
                Pair("Military", "militar"),
                Pair("Mystery", "misterio"),
                Pair("Music", "musica"),
                Pair("Parody", "parodia"),
                Pair("Police", "policial"),
                Pair("Psychological", "psicologico"),
                Pair("Slice of Life", "recuentos-de-la-vida"),
                Pair("Romance", "romance"),
                Pair("Samurai", "samurais"),
                Pair("Seinen", "seinen"),
                Pair("Shoujo", "shoujo"),
                Pair("Shoujo Ai", "shoujo-ai"),
                Pair("Shounen", "shounen"),
                Pair("Shounen Ai", "shounen-ai"),
                Pair("Supernatural", "sobrenatural"),
                Pair("Soft Hentai", "soft-hentai"),
                Pair("Super Powers", "super-poderes"),
                Pair("Thriller", "suspenso"),
                Pair("Terror", "terror"),
                Pair("Vampire", "vampiros"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
            ),
        )

    private class StateFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Completed", "0"),
                Pair("Ongoing", "1"),
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "Movie"),
                Pair("Special", "Special"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ====================== Helpers ======================

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("1") -> SAnime.ONGOING
        statusString.contains("0") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ====================== Preferences ======================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
