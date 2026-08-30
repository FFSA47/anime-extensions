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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
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

        private val CONVENTIONS = listOf(
            "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx"),
            "lulu" to listOf("luluvdo", "lulu", "lulustream"),
        )

        // Regex to extract player objects from Next.js streaming HTML
        private val PLAYER_REGEX = """\{"id":\d+,"language":"(\w+)","server_name":"([^"]+)","bridge_url":"([^"]+)"[^}]*\}""".toRegex()
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
            document.select("script").forEach { script ->
                if (script.data().contains("\"props\":{\"pageProps\":")) {
                    try {
                        val jObject = json.decodeFromString<JsonObject>(script.data())
                        val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                        val data = pageProps?.get("data")?.jsonObject ?: return@forEach
                        val items = data["data"]?.jsonArray ?: return@forEach
                        items.forEach { item ->
                            val animeItem = item.jsonObject
                            val anime = SAnime.create().apply {
                                setUrlWithoutDomain("/anime/${animeItem["slug"]?.jsonPrimitive?.content ?: return@forEach}")
                                thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]?.jsonPrimitive?.content ?: ""}"
                                title = animeItem["name"]?.jsonPrimitive?.content ?: ""
                            }
                            animeList.add(anime)
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
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
            document.select("script").forEach { script ->
                if (script.data().contains("\"props\":{\"pageProps\":")) {
                    try {
                        val jObject = json.decodeFromString<JsonObject>(script.data())
                        val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                        val data = pageProps?.get("data")?.jsonObject ?: return@forEach
                        val episodes = data["episodes"]?.jsonArray ?: data["latest_episodes"]?.jsonArray
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
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }

        return AnimesPage(animeMap.values.toList(), false)
    }

    // ====================== Details ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

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

        if (anime.title.isBlank()) {
            document.select("script").forEach { script ->
                if (script.data().contains("\"props\":{\"pageProps\":")) {
                    try {
                        val jObject = json.decodeFromString<JsonObject>(script.data())
                        val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                        val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                        anime.title = data["name"]?.jsonPrimitive?.content ?: ""
                        anime.genre = data["genres"]?.jsonPrimitive?.content?.split(",")?.joinToString() ?: ""
                        anime.description = data["overview"]?.jsonPrimitive?.content ?: ""
                        anime.status = parseStatus(data["status"]?.jsonPrimitive?.content ?: "")
                        anime.thumbnail_url = "https://image.tmdb.org/t/p/w600_and_h900_bestv2${data["poster"]?.jsonPrimitive?.content ?: ""}"
                        anime.setUrlWithoutDomain("/anime/${data["slug"]?.jsonPrimitive?.content ?: ""}")
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }

        if (anime.title.isBlank()) {
            anime.title = document.selectFirst("h1.fs-2, h1, .title, [class*=\"title\"]")?.text()?.trim() ?: "Unknown Title"
            anime.thumbnail_url = document.selectFirst("img[src*=\"tmdb\"], img[src*=\"poster\"], .poster img")?.attr("src") ?: ""
            anime.description = document.selectFirst("p.description, .description, .sinopsis, [class*=\"sinopsis\"], [class*=\"description\"]")?.text()?.trim() ?: ""
            anime.genre = document.select(".badge.bg-secondary, .genre, [class*=\"genre\"]").joinToString(", ") { it.text() }

            val statusText = document.selectFirst(".col:has(.text-muted:contains(Estado)) div.ms-2 div:last-child, [class*=\"status\"]")?.text()
            anime.status = when {
                statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
                statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        if (anime.title.isBlank()) {
            anime.title = "Unknown Title"
        }

        return anime
    }

    // ====================== Episodes ======================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select("script").forEach { script ->
            if (script.data().contains("\"props\":{\"pageProps\":")) {
                try {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                    val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                    val episodes = data["episodes"]?.jsonArray ?: return@forEach
                    episodes.forEach { item ->
                        val episodeObj = item.jsonObject
                        val number = episodeObj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@forEach
                        val episode = SEpisode.create().apply {
                            setUrlWithoutDomain("/ver/${data["slug"]?.jsonPrimitive?.content ?: ""}/$number")
                            episode_number = number
                            name = "Episodio $number"
                        }
                        episodeList.add(episode)
                    }
                } catch (_: Exception) { /* ignore */ }
            }
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

    // ====================== Video List (FIXED) ======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Get the raw HTML as string for regex parsing (Next.js streaming format)
        val html = document.html()

        // Method 1: Extract all players from Next.js streaming scripts using regex
        // The data is embedded in self.__next_f.push scripts, not in __NEXT_DATA__
        val players = PLAYER_REGEX.findAll(html)

        players.forEach { match ->
            val languageCode = match.groupValues[1]
            val serverName = match.groupValues[2]
            val bridgeUrl = match.groupValues[3].replace("\\/", "/")

            val language = when (languageCode) {
                "LAT" -> "[LAT]"
                "ESP" -> "[ESP]"
                "SUB" -> "[SUB]"
                else -> "[SUB]"
            }

            val videos = extractVideosFromUrl(bridgeUrl, language, serverName)
            videoList.addAll(videos)
        }

        // Method 2: Fallback - extract from visible iframe if regex didn't find anything
        if (videoList.isEmpty()) {
            document.selectFirst("iframe[src*=\"website.animelatinohd.com\"]")?.attr("src")?.let { iframeUrl ->
                val videos = extractVideosFromUrl(iframeUrl, "[SUB]", "Default")
                videoList.addAll(videos)
            }
        }

        return videoList
    }

    // ====================== Bridge URL Resolution (FIXED) ======================

    /**
     * Resolves bridge URLs from website.animelatinohd.com.
     * These bridge pages typically return an HTML with an iframe pointing
     * to the actual video host (Filemoon, Voe, Lulu, etc.).
     */
    private fun extractVideosFromUrl(url: String, language: String, serverName: String): List<Video> {
        val prefix = "$language $serverName"

        if (url.contains("website.animelatinohd.com", ignoreCase = true)) {
            return resolveBridgeUrl(url, prefix)
        }

        return extractWithExtractor(url, prefix)
    }

    private fun resolveBridgeUrl(url: String, prefix: String): List<Video> {
        try {
            // Important: send Referer from the main site to avoid blocks
            val bridgeHeaders = headers.newBuilder()
                .add("Referer", "$baseUrl/")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val request = GET(url, bridgeHeaders)
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()

            // If we were redirected to a non-bridge domain, extract directly
            if (!finalUrl.contains("website.animelatinohd.com", ignoreCase = true)) {
                return extractWithExtractor(finalUrl, prefix)
            }

            val bridgeDoc = response.asJsoup()
            val bodyHtml = bridgeDoc.html()

            // Method 1: Direct iframe src
            bridgeDoc.selectFirst("iframe[src]")?.attr("src")?.let { src ->
                if (src.isNotBlank() && !src.startsWith("javascript:", ignoreCase = true)) {
                    return extractWithExtractor(resolveUrl(src, finalUrl), prefix)
                }
            }

            // Method 2: iframe data-src (lazy loading)
            bridgeDoc.selectFirst("iframe[data-src]")?.attr("data-src")?.let { src ->
                if (src.isNotBlank()) {
                    return extractWithExtractor(resolveUrl(src, finalUrl), prefix)
                }
            }

            // Method 3: frame src
            bridgeDoc.selectFirst("frame[src]")?.attr("src")?.let { src ->
                if (src.isNotBlank()) {
                    return extractWithExtractor(resolveUrl(src, finalUrl), prefix)
                }
            }

            // Method 4: embed src
            bridgeDoc.selectFirst("embed[src]")?.attr("src")?.let { src ->
                if (src.isNotBlank()) {
                    return extractWithExtractor(resolveUrl(src, finalUrl), prefix)
                }
            }

            // Method 5: object data
            bridgeDoc.selectFirst("object[data]")?.attr("data")?.let { src ->
                if (src.isNotBlank()) {
                    return extractWithExtractor(resolveUrl(src, finalUrl), prefix)
                }
            }

            // Method 6: JavaScript variables with URLs
            val jsPatterns = listOf(
                """iframe\.src\s*=\s*["']([^"']+)["']""".toRegex(),
                """src\s*=\s*["']([^"']+(?:filemoon|voe|lulu|stream|player)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                """["'](https?://[^"']+(?:filemoon|voe|lulu|stream)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                """var\s+\w+\s*=\s*["']([^"']+(?:filemoon|voe|lulu)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                """url\s*:\s*["']([^"']+)["']""".toRegex(),
                """data-url\s*=\s*["']([^"']+)["']""".toRegex(),
            )

            for (pattern in jsPatterns) {
                pattern.find(bodyHtml)?.groupValues?.get(1)?.let { foundUrl ->
                    if (foundUrl.isNotBlank() && (foundUrl.startsWith("http") || foundUrl.contains("filemoon") || foundUrl.contains("voe") || foundUrl.contains("lulu"))) {
                        return extractWithExtractor(resolveUrl(foundUrl, finalUrl), prefix)
                    }
                }
            }

            // Method 7: JavaScript redirects
            val redirectPatterns = listOf(
                """(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""".toRegex(),
                """location\.replace\(["']([^"']+)["']\)""".toRegex(),
                """location\.assign\(["']([^"']+)["']\)""".toRegex(),
            )

            for (pattern in redirectPatterns) {
                pattern.find(bodyHtml)?.groupValues?.get(1)?.let { redirectUrl ->
                    if (redirectUrl.isNotBlank() && !redirectUrl.startsWith("javascript:", ignoreCase = true)) {
                        return extractWithExtractor(resolveUrl(redirectUrl, finalUrl), prefix)
                    }
                }
            }

            // Method 8: Meta refresh
            bridgeDoc.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.let { content ->
                val metaRedirect = """url\s*=\s*['"]?([^'";]+)""".toRegex(RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                if (!metaRedirect.isNullOrBlank()) {
                    return extractWithExtractor(resolveUrl(metaRedirect, finalUrl), prefix)
                }
            }

            // Method 9: Form action
            bridgeDoc.selectFirst("form[action]")?.attr("action")?.let { action ->
                if (action.isNotBlank() && action.startsWith("http")) {
                    return extractWithExtractor(action, prefix)
                }
            }

            // FALLBACK: UniversalExtractor (WebView) - handles JS-only pages
            return universalExtractor.videosFromUrl(url, headers, prefix = prefix)

        } catch (e: Exception) {
            return universalExtractor.videosFromUrl(url, headers, prefix = prefix)
        }
    }

    /**
     * Resolves relative URLs to absolute URLs.
     */
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

    /**
     * Maps a URL to the correct extractor.
     */
    private fun extractWithExtractor(url: String, prefix: String): List<Video> {
        val host = try {
            url.toHttpUrl().host.lowercase()
        } catch (_: Exception) {
            return emptyList()
        }

        val effective = when {
            host.contains("filemoon") -> "filemoon"
            host.contains("voe") -> "voe"
            host.contains("lulu") -> "lulu"
            CONVENTIONS.any { it.second.any { alias -> alias in host } } -> {
                CONVENTIONS.first { (_, aliases) -> aliases.any { alias -> alias in host } }.first
            }
            else -> null
        }

        val videos = when (effective) {
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "$prefix:")
            "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix:", headers = headers)
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "$prefix:")
            else -> universalExtractor.videosFromUrl(url, headers, prefix = prefix)
        }

        return if (videos.isNotEmpty()) videos else universalExtractor.videosFromUrl(url, headers, prefix = prefix)
    }

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
