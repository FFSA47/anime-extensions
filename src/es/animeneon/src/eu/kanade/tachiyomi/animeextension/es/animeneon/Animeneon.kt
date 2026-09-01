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
import eu.kanade.tachiyomi.util.preferences.setPref
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Animeneon : ParsedAnimeHttpSource() {

    override val name = "AnimeNeon"
    override val baseUrl = "https://animeneon.net"
    override val lang = "es"

    // ===============================
    // PREFERENCIAS (en inglés)
    // ===============================
    private val preferences by lazy {
        context.getSharedPreferences("source_$id", android.content.Context.MODE_PRIVATE)
    }

    private val serverPrefKey = "preferred_server"
    private val qualityPrefKey = "preferred_quality"

    private fun getPreferredServer(): String? = preferences.getString(serverPrefKey, null)

    private fun getPreferredQuality(): String? = preferences.getString(qualityPrefKey, null)

    // ===============================
    // 1. POPULAR
    // ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/nuevo-anime?page=$page", headers)

    override fun popularAnimeParse(response: Response): List<SAnime> = parseAnimeList(response.asJsoup())

    // ===============================
    // 2. BÚSQUEDA (con filtros)
    // ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterParams = filters.buildFilterParams()
        return GET("$baseUrl/browse?q=$query&page=$page$filterParams", headers)
    }

    override fun searchAnimeParse(response: Response): List<SAnime> = parseAnimeList(response.asJsoup())

    // ===============================
    // 3. DETALLES DEL ANIME
    // ===============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val anime = SAnime.create()

        anime.title = doc.selectFirst(".a2-title")?.text()?.trim()
            ?: doc.selectFirst("h1, h2.text-2xl")?.text()?.trim()
            ?: "Sin título"

        anime.thumbnail_url = doc.selectFirst(".a2-poster-img")?.attr("abs:src")
            ?: doc.selectFirst("img[src*=/uploads/anime/]")?.attr("abs:src")

        anime.description = doc.selectFirst(".a2-desc-text")?.text()?.trim()
            ?: doc.selectFirst("p.text-sm.leading-relaxed, .sinopsis")?.text()?.trim()

        val genreElements = doc.select(".a2-genre-tag")
        anime.genre = genreElements.joinToString(", ") { it.text().trim() }

        val statusElement = doc.selectFirst(".a2-badge-status")
        val statusText = statusElement?.text()?.trim()
        anime.status = when {
            statusText?.contains("Finalizado", ignoreCase = true) == true -> SAnime.COMPLETED
            statusText?.contains("Emisión", ignoreCase = true) == true -> SAnime.ONGOING
            statusText?.contains("Próximo", ignoreCase = true) == true -> SAnime.UPCOMING
            else -> SAnime.UNKNOWN
        }

        return anime
    }

    // ===============================
    // 4. LISTA DE EPISODIOS (descendente: más reciente primero)
    // ===============================
    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodeElements = doc.select(".a2-ep-list a.a2-ep-card")
        return episodeElements.mapNotNull { element ->
            val href = element.attr("href")
            val number = extractEpisodeNumber(href) ?: return@mapNotNull null

            val name = element.selectFirst(".a2-ep-title")?.text()?.trim()
                ?: "Episodio $number"

            SEpisode.create().apply {
                url = href
                this.name = name
                episode_number = number.toFloat()
            }
        }.sortedByDescending { it.episode_number } // Más reciente primero
    }

    // ===============================
    // 5. VIDEOS (con extractores + preferencias)
    // ===============================
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val script = doc.select("script:containsData(groups)").firstOrNull() ?: return emptyList()
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
            "lulustream.com" to LuluExtractor(client, headers),
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
                                prefix = "$hostKey - ",
                            )
                        }
                        is VoeExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - ",
                            )
                        }
                        is MixDropExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - ",
                                lang = "es",
                            )
                        }
                        is StreamTapeExtractor -> {
                            val video = extractor.videoFromUrl(
                                url = link,
                                quality = hostKey,
                            )
                            if (video != null) listOf(video) else emptyList()
                        }
                        is LuluExtractor -> {
                            extractor.videosFromUrl(
                                url = link,
                                prefix = "$hostKey - ",
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

        // ============================================
        // ORDENAR según preferencias del usuario
        // ============================================
        val preferredServer = getPreferredServer()
        val preferredQuality = getPreferredQuality()

        fun getServerFromVideo(video: Video): String? {
            val nameParts = video.quality.split(" - ")
            return nameParts.firstOrNull()?.trim()?.lowercase()
        }

        fun getResolutionFromVideo(video: Video): String? {
            val name = video.quality.lowercase()
            val patterns = listOf("1080p", "720p", "480p")
            for (pattern in patterns) {
                if (name.contains(pattern)) {
                    return pattern
                }
            }
            return null
        }

        // Orden personalizado: servidor preferido, luego calidad preferida, luego alfabético
        return allVideos.sortedWith(
            compareBy<Video> { video ->
                val server = getServerFromVideo(video)
                val matchesServer = preferredServer != null && server == preferredServer.lowercase()
                if (matchesServer) 0 else 1
            }.thenBy { video ->
                val res = getResolutionFromVideo(video)
                val matchesQuality = preferredQuality != null && res == preferredQuality.lowercase()
                if (matchesQuality) 0 else 1
            }.thenBy { video ->
                video.quality
            },
        )
    }

    // ===============================
    // 6. FILTROS
    // ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
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

    // ===============================
    // 7. PANTALLA DE PREFERENCIAS
    // ===============================
    override fun getPreferenceScreen(): android.preference.PreferenceScreen? {
        val screen = context.getSharedPreferences("source_$id", android.content.Context.MODE_PRIVATE)
            .let { prefs ->
                android.preference.PreferenceScreen(context).apply {
                    // Preferencia: Servidor
                    val serverPref = android.preference.ListPreference(context).apply {
                        key = serverPrefKey
                        title = "Preferred Server"
                        summary = "Select the server to show first (all servers will still appear)"
                        entries = arrayOf("Default", "Mp4upload", "Voe", "Mixdrop", "Streamtape", "Lulu")
                        entryValues = arrayOf("", "mp4upload.com", "voe.com", "mixdrop.co", "streamtape.com", "lulustream.com")
                        setDefaultValue("")
                        value = prefs.getString(key, "")
                        setOnPreferenceChangeListener { _, newValue ->
                            prefs.setPref(key, newValue as String)
                            true
                        }
                    }
                    addPreference(serverPref)

                    // Preferencia: Calidad (solo 1080p, 720p, 480p)
                    val qualityPref = android.preference.ListPreference(context).apply {
                        key = qualityPrefKey
                        title = "Preferred Quality"
                        summary = "Select the quality to show first (all qualities will still appear)"
                        entries = arrayOf("Default", "1080p", "720p", "480p")
                        entryValues = arrayOf("", "1080p", "720p", "480p")
                        setDefaultValue("")
                        value = prefs.getString(key, "")
                        setOnPreferenceChangeListener { _, newValue ->
                            prefs.setPref(key, newValue as String)
                            true
                        }
                    }
                    addPreference(qualityPref)
                }
            }
        return screen
    }

    // ===============================
    // FUNCIONES AUXILIARES
    // ===============================
    private fun parseAnimeList(doc: Document): List<SAnime> {
        val animeElements = doc.select("div.grid a[href^=/anime/]")
        return animeElements.mapNotNull { element ->
            parseAnimeCard(element)
        }
    }

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
