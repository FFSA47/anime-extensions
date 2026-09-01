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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
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
        "Universal"
    )

    private val qualityList = arrayOf("1080p", "720p", "480p")
    private val qualityRegex = Regex("""(\d+)p""")

    // ====================== EXTRACTORES ======================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val serverConventions = listOf(
        "mp4upload" to listOf("mp4upload", "mp4upload.com"),
        "voe" to listOf("voe", "voe.com", "voe.sx"),
        "mixdrop" to listOf("mixdrop", "mixdrop.co", "mixdrop.ag"),
        "streamtape" to listOf("streamtape", "streamtape.com"),
        "lulu" to listOf("lulu", "lulustream", "lulustream.com", "luluvdo")
    )

    // ====================== POPULAR ======================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/nuevo-anime?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.grid a[href^=/anime/]")
        val animeList = elements.mapNotNull { element ->
            parseAnimeCard(element)
        }
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

    // ====================== EPISODIOS (desde JSON) ======================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Buscar el script que contiene el objeto __sveltekit_... con los datos
        val script = document.select("script:containsData(episodes)").firstOrNull()
            ?: return emptyList()

        val scriptData = script.data()

        // Extraer el objeto JSON del script
        // Patrón: data:{...}  o  data:{anime:{episodes:[...]}}
        val jsonRegex = Regex("""data:\s*(\{[^{}]*"episodes"\s*:\s*\[[\s\S]*?\][^{}]*\})""")
        val match = jsonRegex.find(scriptData)
        val jsonString = match?.groupValues?.get(1) ?: return emptyList()

        // Parsear el JSON para obtener el array de episodios
        return try {
            val jsonObject = json.parseToJsonElement(jsonString).jsonObject
            val animeObject = jsonObject["anime"]?.jsonObject ?: jsonObject
            val episodesArray = animeObject["episodes"]?.jsonArray ?: return emptyList()

            episodesArray.mapNotNull { element ->
                val obj = element.jsonObject
                val number = obj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: "Episodio $number"
                val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null

                SEpisode.create().apply {
                    url = "/ver/$slug"
                    this.name = title
                    episode_number = number
                }
            }.sortedByDescending { it.episode_number }
        } catch (e: Exception) {
            // Fallback: extraer episodios del HTML (paginado)
            val episodeElements = document.select(".a2-ep-list a.a2-ep-card")
            episodeElements.mapNotNull { element ->
                val href = element.attr("href")
                val number = extractEpisodeNumber(href) ?: return@mapNotNull null
                val name = element.selectFirst(".a2-ep-title")?.text()?.trim() ?: "Episodio $number"
                SEpisode.create().apply {
                    url = href
                    this.name = name
                    episode_number = number
                }
            }.sortedByDescending { it.episode_number }
        }
    }

    // ====================== VIDEOS (sin comillas en keys) ======================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(groups)").firstOrNull() ?: return emptyList()
        val scriptData = script.data()

        // Extraer el array groups sin comillas (JavaScript object)
        val groupsRegex = Regex("""groups\s*:\s*(\[[\s\S]*?\])\s*[,}]""")
        val groupsMatch = groupsRegex.find(scriptData)
        val groupsJson = groupsMatch?.groupValues?.get(1) ?: return emptyList()

        // Ahora extraer cada grupo y dentro de cada grupo, extraer "servers": [...]
        // Usamos regex sin comillas para las keys
        val groupObjects = Regex("""\{[^{}]*?\}""").findAll(groupsJson).map { it.value }.toList()

        val allVideos = mutableListOf<Video>()

        for (groupObj in groupObjects) {
            // Buscar "servers": [ ... ] dentro del grupo
            val serversRegex = Regex("""servers\s*:\s*(\[[\s\S]*?\])""")
            val serversMatch = serversRegex.find(groupObj)
            val serversArray = serversMatch?.groupValues?.get(1) ?: continue

            // Extraer cada objeto servidor dentro del array
            val serverObjects = Regex("""\{[^{}]*?\}""").findAll(serversArray).map { it.value }.toList()

            for (serverObj in serverObjects) {
                // Extraer link y hostKey sin comillas
                val link = Regex("""link\s*:\s*"([^"]+)"""").find(serverObj)?.groupValues?.get(1) ?: continue
                val hostKey = Regex("""hostKey\s*:\s*"([^"]+)"""").find(serverObj)?.groupValues?.get(1) ?: continue

                val videos = resolveServer(link, hostKey)
                allVideos.addAll(videos)
            }
        }

        return allVideos
    }

    // ====================== RESOLVER SERVIDOR ======================
    private fun resolveServer(url: String, hostKey: String): List<Video> {
        val matchedServer = serverConventions.firstOrNull { (_, aliases) ->
            aliases.any { it.equals(hostKey, ignoreCase = true) }
        }?.first

        val fallbackServer = matchedServer ?: serverConventions.firstOrNull { (_, aliases) ->
            aliases.any { url.contains(it, ignoreCase = true) }
        }?.first

        return when (fallbackServer) {
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers = Headers.headersOf(), prefix = "Mp4upload - ")
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "Voe - ")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url, prefix = "Mixdrop - ", lang = "es")
            "streamtape" -> {
                val video = streamTapeExtractor.videoFromUrl(url, quality = "Streamtape")
                if (video != null) listOf(video) else emptyList()
            }
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "Lulu - ")
            else -> {
                universalExtractor.videosFromUrl(url, headers)
            }
        }
    }

    // ====================== ORDENAMIENTO ======================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, defaultQuality) ?: defaultQuality
        val server = preferences.getString(prefServerKey, defaultServer) ?: defaultServer

        return this.sortedWith(
            compareBy(
                { !it.quality.contains(server, ignoreCase = true) },
                { !it.quality.contains(quality, ignoreCase = true) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            )
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
            Filters.OrderFilter()
        )
    }

    // ====================== PREFERENCIAS ======================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = prefServerKey
            title = "Preferred Server"
            summary = "Select the server to show first"
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
        val match = regex.find(url)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }
}
