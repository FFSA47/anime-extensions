package eu.kanade.tachiyomi.animeextension.es.animelatinohd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
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
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf("Filemoon", "Voe", "Lulu")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[ESP]", "[SUB]")

        // Domain aliases for each extractor (same as MonosChinos)
        private val CONVENTIONS = listOf(
            "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx"),
            "lulu" to listOf("luluvdo", "lulu", "lulustream"),
        )
    }

    // Library extractors
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }

    // ====================== Popular (Directory) ======================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/directorio" else "$baseUrl/directorio?page=$page"
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        // Extract from DOM (directory grid)
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

        // Fallback: parse from embedded JSON
        if (animeList.isEmpty()) {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\":")) {
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

        // Detect next page
        val nextPage = document.selectFirst("a[href*=\"page=\"]:not([href*=\"page=1\"])") != null
        return AnimesPage(animeList, nextPage)
    }

    // ====================== Latest Episodes ======================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeMap = mutableMapOf<String, SAnime>()

        // Parse from recent episode links on homepage
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

        // Fallback: parse from JSON
        if (animeMap.isEmpty()) {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\":")) {
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

    // ====================== Anime Details ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        // Try to parse from embedded JSON first
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
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

        // Robust DOM fallback if JSON fails
        if (anime.title.isBlank()) {
            // Title: try multiple selectors
            anime.title = document.selectFirst("h1.fs-2, h1, .title, [class*=\"title\"]")?.text()?.trim() ?: "Unknown Title"

            // Thumbnail: look for any image with tmdb or poster
            anime.thumbnail_url = document.selectFirst("img[src*=\"tmdb\"], img[src*=\"poster\"], .poster img")?.attr("src") ?: ""

            // Description: try common selectors
            anime.description = document.selectFirst("p.description, .description, .sinopsis, [class*=\"sinopsis\"], [class*=\"description\"]")?.text()?.trim() ?: ""

            // Genre: extract from tags/badges
            anime.genre = document.select(".badge.bg-secondary, .genre, [class*=\"genre\"]").joinToString(", ") { it.text() }

            // Status: look for "Estado" or status text
            val statusText = document.selectFirst(".col:has(.text-muted:contains(Estado)) div.ms-2 div:last-child, [class*=\"status\"]")?.text()
            anime.status = when {
                statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
                statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        // Ensure title is never empty
        if (anime.title.isBlank()) {
            anime.title = "Unknown Anime"
        }

        return anime
    }

    // ====================== Episode List ======================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        // The site renders all episodes at once (no pagination).
        // This single request fetches the entire list, even for long-running shows.
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
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

        // DOM fallback: extract all episode links
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

        // Sort by episode number descending (latest first)
        episodeList.sortByDescending { it.episode_number }

        return episodeList
    }

    // ====================== Video List ======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                try {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                    val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                    val players = data["players"]?.jsonArray ?: return@forEach
                    players.forEach { playerElement ->
                        val playerObj = playerElement.jsonObject
                        val language = when (playerObj["language"]?.jsonPrimitive?.content) {
                            "LAT" -> "[LAT]"
                            "ESP" -> "[ESP]"
                            "SUB" -> "[SUB]"
                            else -> "[SUB]"
                        }

                        val bridgeUrl = playerObj["bridge_url"]?.jsonPrimitive?.content ?: return@forEach
                        val videos = extractVideosFromUrl(bridgeUrl, language)
                        videoList.addAll(videos)
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }

        return videoList
    }

    private fun extractVideosFromUrl(url: String, language: String): List<Video> {
        val host = url.toHttpUrl().host.lowercase()

        // Match server using domain conventions
        val matched = CONVENTIONS.firstOrNull { (_, aliases) ->
            aliases.any { it in host }
        }?.first

        val effective = matched ?: when {
            host.contains("filemoon") -> "filemoon"
            host.contains("voe") -> "voe"
            host.contains("lulu") -> "lulu"
            else -> null
        }

        return when (effective) {
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "$language Voe:")
            "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$language Filemoon:", headers = headers)
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "$language Lulu:")
            else -> emptyList()
        }
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

        val filterUrl = if (query.isBlank()) {
            "$baseUrl/animes?page=$page&genre=${genreFilter.toUriPart()}&status=${stateFilter.toUriPart()}&type=${typeFilter.toUriPart()}"
        } else {
            "$baseUrl/animes?page=$page&search=$query"
        }

        return GET(filterUrl)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        StateFilter(),
        TypeFilter(),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("#__next > main > div > div[class*=\"Animes_paginate\"] a:last-child svg").any()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
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

        if (animeList.isEmpty()) {
            document.select("a[href^=\"/anime/\"]").forEach { link ->
                val href = link.attr("href")
                val img = link.select("img").first()
                val poster = img?.attr("src") ?: ""
                val title = link.select("h3").first()?.text() ?: ""

                val anime = SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    thumbnail_url = poster
                    this.title = title
                }
                animeList.add(anime)
            }
        }

        return AnimesPage(animeList, hasNextPage)
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
                Pair("TV", "tv"),
                Pair("Movie", "movie"),
                Pair("Special", "special"),
                Pair("OVA", "ova"),
                Pair("ONA", "ona"),
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
