package eu.kanade.tachiyomi.animeextension.es.animeneon

import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.preference.getPref
import eu.kanade.tachiyomi.util.preference.setPref
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.content.SharedPreferences
import android.content.Context

class Animeneon : ParsedAnimeHttpSource() {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val lang = "es"
    override val supportsLatest = false

    // ===============================
    // PREFERENCIAS
    // ===============================
    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    private val serverPrefKey = "preferred_server"
    private val qualityPrefKey = "preferred_quality"
    private val PREF_SERVER_DEFAULT = "mp4upload.com"
    private val PREF_QUALITY_DEFAULT = "1080p"

    private fun getPreferredServer(): String = preferences.getString(serverPrefKey, PREF_SERVER_DEFAULT)!!
    private fun getPreferredQuality(): String = preferences.getString(qualityPrefKey, PREF_QUALITY_DEFAULT)!!

    // ===============================
    // 1. POPULAR
    // ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/nuevo-anime?page=$page", headers)
    }

    override fun popularAnimeSelector(): String = "div.grid a[href^=/anime/]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return parseAnimeCard(element) ?: SAnime.create()
    }

    override fun popularAnimeNextPageSelector(): String? = "a[rel=next]"

    // ===============================
    // 2. BÚSQUEDA
    // ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime {
        return parseAnimeCard(element) ?: SAnime.create()
    }

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    // ===============================
    // 3. LATEST (no usado)
    // ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException()

    // ===============================
    // 4. DETALLES
    // ===============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst(".a2-title")?.text()?.trim()
            ?: document.selectFirst("h1, h2.text-2xl")?.text()?.trim()
            ?: "Sin título"

        anime.thumbnail_url = document.selectFirst(".a2-poster-img")?.attr("abs:src")
            ?: document.selectFirst("img[src*=/uploads/anime/]")?.attr("abs:src")

        anime.description = document.selectFirst(".a2-desc-text")?.text()?.trim()
            ?: document.selectFirst("p.text-sm.leading-relaxed, .sinopsis")?.text()?.trim()

        val genreElements = document.select(".a2-genre-tag")
        anime.genre = genreElements.joinToString(", ") { it.text().trim() }

        val statusElement = document.selectFirst(".a2-badge-status")
        val statusText = statusElement?.text()?.trim()
        anime.status = when {
            statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
            statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
            statusText?.contains("Próximo", ignoreCase = true) == true -> SAnime.UNKNOWN
            else -> SAnime.UNKNOWN
        }

        return anime
    }

    // ===============================
    // 5. EPISODIOS (descendente)
    // ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}", headers)
    }

    override fun episodeListSelector(): String = ".a2-ep-list a.a2-ep-card"

    override fun episodeFromElement(element: Element): SEpisode {
        val href = element.attr("href")
        val number = extractEpisodeNumber(href) ?: 0f
        val name = element.selectFirst(".a2-ep-title")?.text()?.trim() ?: "Episodio $number"
        return SEpisode.create().apply {
            url = href
            this.name = name
            episode_number = number
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = super.episodeListParse(response)
        return episodes.sortedByDescending { it.episode_number }
    }

    // ===============================
    // 6. VIDEOS
    // ===============================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", headers)
    }

    override fun videoListSelector(): String = ""  // No se usa

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("No se usa")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("No se usa")
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val script = doc.select("script:containsData(groups)").firstOrNull() ?: return emptyList()
        val scriptData = script.data()

        val groupsRegex = Regex("""groups\s*:\s*(\[[\s\S]*?\])\s*[,}]""")
        val match = groupsRegex.find(scriptData)
        val groupsJson = match?.groupValues?.get(1) ?: return emptyList()

        val serverObjects = Regex("""\{[^{}]*\}""").findAll(groupsJson).map { it.value }.toList()

        val allVideos = mutableListOf<Video>()

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

    // ===============================
    // 7. ORDEN DE VIDEOS (con preferencias)
    // ===============================
    override fun List<Video>.sort(): List<Video> {
        val preferredServer = getPreferredServer()
        val preferredQuality = getPreferredQuality()
        val qualityRegex = Regex("""(\d+)p""")

        return this.sortedWith(
            compareBy(
                // Primero los que coinciden con el servidor preferido
                { video ->
                    val serverName = video.quality.split(" - ").firstOrNull()?.lowercase() ?: ""
                    val matchesServer = serverName.contains(preferredServer.lowercase())
                    if (matchesServer) 0 else 1
                },
                // Luego los que coinciden con la calidad preferida
                { video ->
                    val qualityMatch = qualityRegex.find(video.quality)
                    val qualityValue = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val prefQualityInt = preferredQuality.replace("p", "").toIntOrNull() ?: 0
                    if (qualityValue == prefQualityInt) 0 else 1
                },
                // Luego por resolución descendente
                { video ->
                    val qualityMatch = qualityRegex.find(video.quality)
                    - (qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                }
            )
        )
    }

    // ===============================
    // 8. FILTROS
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
    // FUNCIONES AUXILIARES
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
