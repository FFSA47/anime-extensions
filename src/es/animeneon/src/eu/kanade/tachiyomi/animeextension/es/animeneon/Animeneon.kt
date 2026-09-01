package eu.kanade.tachiyomi.animeextension.es.animeneon

import android.util.Base64
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

class Animeneon : AnimeHttpSource() {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val id = 6957694006954649296L  // ID único
    override val lang = "es"
    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    // Claves de preferencias (camelCase)
    private val prefServerKey = "preferred_server"
    private val prefQualityKey = "preferred_quality"

    // Valores por defecto
    private val defaultServer = "Mp4upload"
    private val defaultQuality = "1080p"

    // Lista de servidores y calidades
    private val serverList = arrayOf(
        "Mp4upload",
        "Voe",
        "Mixdrop",
        "Streamtape",
        "Lulu"
    )

    private val qualityList = arrayOf("1080p", "720p", "480p")

    // Regex para extraer resolución
    private val qualityRegex = Regex("""(\d+)p""")

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

    // ====================== BÚSQUEDA ======================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ====================== LATEST (no usado) ======================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException()
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

    // ====================== VIDEOS ======================
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

        val serverObjects = Regex("""\{[^{}]*\}""").findAll(groupsJson).map { it.value }.toList()

        val allVideos = mutableListOf<Video>()

        // Mapa de extractores
        val extractors = mapOf(
            "mp4upload.com" to Mp4uploadExtractor(client),
            "voe.com" to VoeExtractor(client, headers),
            "voe.sx" to VoeExtractor(client, headers),
            "mixdrop.co" to MixDropExtractor(client),
            "mixdrop.ag" to MixDropExtractor(client),
            "streamtape.com" to StreamTapeExtractor(client),
            "lulustream.com" to LuluExtractor(client, headers)
        )

        for (obj in serverObjects) {
            val link = Regex(""""link"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: continue
            val hostKey = Regex(""""hostKey"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: continue

            val extractor = extractors[hostKey]
            if (extractor != null) {
                try {
                    val videos = when (extractor) {
                        is Mp4uploadExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                headers = Headers.headersOf(),
                                prefix = "$hostKey - "
                            )
                        }
                        is VoeExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - "
                            )
                        }
                        is MixDropExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - ",
                                lang = "es"
                            )
                        }
                        is StreamTapeExtractor -> {
                            val video = extractor.videoFromUrl(
                                url = link,
                                quality = hostKey
                            )
                            if (video != null) listOf(video) else emptyList()
                        }
                        is LuluExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - "
                            )
                        }
                        else -> emptyList()
                    }
                    allVideos.addAll(videos)
                } catch (e: Exception) {
                    // Ignoramos errores
                }
            }
        }

        return allVideos
    }

    // ====================== ORDENAMIENTO DE VIDEOS ======================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, defaultQuality) ?: defaultQuality
        val server = preferences.getString(prefServerKey, defaultServer) ?: defaultServer

        return this.sortedWith(
            compareBy(
                { !it.quality.contains(server, ignoreCase = true) },  // Primero el servidor preferido
                { !it.quality.contains(quality, ignoreCase = true) }, // Luego la calidad preferida
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } // Luego por resolución numérica
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
