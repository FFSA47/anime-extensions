package eu.kanade.tachiyomi.animeextension.es.animeneon

import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.min

class Animeneon : AnimeHttpSource() {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val id = 6957694006954649296L
    override val lang = "es"
    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    // Preferencias (camelCase)
    private val prefServerKey = "preferred_server"
    private val prefQualityKey = "preferred_quality"

    private val defaultServer = "Mp4upload"
    private val defaultQuality = "1080p"

    private val serverList = arrayOf(
        "Mp4upload",
        "Voe",
        "Mixdrop",
        "Streamtape",
        "Lulu"
    )

    private val qualityList = arrayOf("1080p", "720p", "480p")
    private val qualityRegex = Regex("""(\d+)p""")

    // ===============================
    // EXTRACTORES (lazy)
    // ===============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }

    // ===============================
    // CONVENCIONES DE SERVIDORES
    // ===============================
    private val conventions = listOf(
        // Voe: múltiples dominios y alias
        "voe" to listOf(
            "voe", "voe.sx", "voe.com",
            "tubelessceliolymph", "simpulumlamerop", "urochsunloath",
            "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"
        ),
        // Mixdrop
        "mixdrop" to listOf("mixdrop", "mixdrop.co", "mixdrop.ag"),
        // Streamtape
        "streamtape" to listOf("streamtape", "streamtape.com", "stape", "stp"),
        // Mp4upload
        "mp4upload" to listOf("mp4upload", "mp4upload.com"),
        // Lulu
        "lulu" to listOf("lulu", "lulustream", "lulustream.com", "luluvdo")
    )

    // ===============================
    // POPULAR
    // ===============================
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

    // ===============================
    // BÚSQUEDA
    // ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ===============================
    // LATEST (no usado)
    // ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException()
    }

    // ===============================
    // DETALLE
    // ===============================
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

    // ===============================
    // EPISODIOS
    // ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeElements = document.select(".a2-ep-list a.a2-ep-card")
        return episodeElements.mapNotNull { element ->
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

    // ===============================
    // VIDEOS (con detección inteligente)
    // ===============================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(groups)").firstOrNull() ?: return emptyList()
        val scriptData = script.data()

        val groupsRegex = Regex("""groups\s*:\s*(\[[\s\S]*?\])\s*[,}]""")
        val match = groupsRegex.find(scriptData)
        val groupsJson = match?.groupValues?.get(1) ?: return emptyList()

        // Extraer cada objeto servidor
        val serverObjects = Regex("""\{[^{}]*\}""").findAll(groupsJson).map { it.value }.toList()

        val allVideos = mutableListOf<Video>()

        for (obj in serverObjects) {
            val link = Regex(""""link"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: continue
            val hostKey = Regex(""""hostKey"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)?.lowercase() ?: continue
            val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: hostKey

            // Detectar el extractor basado en el hostKey o el dominio del link
            val matchedServer = detectServer(hostKey, link)

            if (matchedServer != null) {
                try {
                    val videos = resolveVideos(link, matchedServer, label)
                    allVideos.addAll(videos)
                } catch (e: Exception) {
                    // Ignorar errores
                }
            }
        }

        return allVideos
    }

    // ===============================
    // DETECCIÓN DE SERVIDOR
    // ===============================
    private fun detectServer(hostKey: String, link: String): String? {
        val source = link.lowercase()

        // Primero intentar por hostKey
        for ((server, aliases) in conventions) {
            if (aliases.any { it == hostKey }) {
                return server
            }
        }

        // Luego por dominio en el enlace
        for ((server, aliases) in conventions) {
            if (aliases.any { it in source }) {
                return server
            }
        }

        return null
    }

    // ===============================
    // RESOLVER VIDEOS
    // ===============================
    private suspend fun resolveVideos(url: String, server: String, label: String): List<Video> {
        return when (server) {
            "voe" -> voeExtractor.videosFromUrl(url, prefix = "$label - ")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url, prefix = "$label - ", lang = "es")
            "streamtape" -> {
                val video = streamTapeExtractor.videoFromUrl(url, quality = label)
                if (video != null) listOf(video) else emptyList()
            }
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, Headers.headersOf(), prefix = "$label - ")
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "$label - ")
            else -> emptyList()
        }
    }

    // ===============================
    // ORDENAMIENTO DE VIDEOS
    // ===============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, defaultQuality) ?: defaultQuality
        val server = preferences.getString(prefServerKey, defaultServer) ?: defaultServer

        return this.sortedWith(
            compareBy(
                { !it.quality.contains(server, ignoreCase = true) },  // Servidor preferido
                { !it.quality.contains(quality, ignoreCase = true) }, // Calidad preferida
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } // Resolución numérica
            )
        )
    }

    // ===============================
    // FILTROS
    // ===============================
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

    // ===============================
    // AUXILIARES
    // ===============================
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
