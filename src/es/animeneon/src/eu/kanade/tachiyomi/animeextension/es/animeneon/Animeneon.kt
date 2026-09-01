package eu.kanade.tachiyomi.animeextension.es.animeneon

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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

class Animeneon :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val id = 6957694006954649297  // ID único
    override val lang = "es"
    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Mp4upload"
        private val SERVER_LIST = arrayOf(
            "Mp4upload",
            "Voe",
            "Mixdrop",
            "Streamtape",
            "Lulu"
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480")

        private val EPISODE_NUMBER_REGEX = Regex("-(\\d+)\\.")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }

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
        val nextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animeList, nextPage)
    }

    // ====================== BÚSQUEDA ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ====================== ÚLTIMOS EPISODIOS (no usado) ======================

    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException()
    }

    // ====================== DETALLE ======================

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
                statusText?.contains("Próximo", ignoreCase = true) == true -> SAnime.UNKNOWN
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ====================== EPISODIOS ======================

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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(groups)").firstOrNull() ?: return emptyList()
        val scriptData = script.data()

        // Extraer el array "groups" del script
        val groupsRegex = Regex("""groups\s*:\s*(\[[\s\S]*?\])\s*[,}]""")
        val match = groupsRegex.find(scriptData)
        val groupsJson = match?.groupValues?.get(1) ?: return emptyList()

        // Extraer cada objeto servidor del array groups
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
                    // Ignoramos errores de un extractor
                }
            }
        }

        return allVideos
    }

    // ====================== ORDEN ======================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        fun getServerFromVideo(video: Video): String? {
            val nameParts = video.quality.split(" - ")
            return nameParts.firstOrNull()?.trim()?.lowercase()
        }

        fun getResolutionFromVideo(video: Video): String? {
            val name = video.quality.lowercase()
            val patterns = listOf("1080p", "720p", "480p")
            for (pattern in patterns) {
                if (name.contains(pattern)) {
                    return pattern.replace("p", "")
                }
            }
            return null
        }

        return this.sortedWith(
            compareBy(
                { !it.quality.contains(server, true) },
                { !it.quality.contains(quality) },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ).reserved(),
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
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
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
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = imageUrl
        }
    }

    private fun extractEpisodeNumber(url: String): Float? {
        val match = EPISODE_NUMBER_REGEX.find(url)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }
}
