package eu.kanade.tachiyomi.animeextension.es.animeneon

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

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
        "Universal",
    )

    private val qualityList = arrayOf(
        "1080p",
        "720p",
        "480p",
    )

    private val qualityRegex = Regex("""(\d+)p""")

    // ====================== EXTRACTORES ======================

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

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/nuevo-anime?page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val elements = document.select(
            "div.grid a[href^=/anime/]",
        )

        val animeList = elements.mapNotNull { element ->
            parseAnimeCard(element)
        }

        val hasNextPage = document.selectFirst(
            "a[rel=next]",
        ) != null

        return AnimesPage(
            animeList,
            hasNextPage,
        )
    }

    // ====================== ÚLTIMOS EPISODIOS ======================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/episodios?page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val episodeItems = document.select(
            "div.grid a[href^=/ver/]",
        )

        val animeList = episodeItems.mapNotNull { link ->
            val episodeUrl = link.attr("abs:href")

            val episodeSlug = episodeUrl
                .substringAfter("/ver/")

            val animeSlug = episodeSlug.replace(
                Regex("-\\d+\\.[A-Za-z0-9]+$"),
                "",
            )

            val animeUrl = "/anime/$animeSlug"

            val titleElement = link.selectFirst(
                ".text-\\[13px\\].font-semibold, .line-clamp-1",
            )

            val title = titleElement
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val img = link.selectFirst("img")
            val imageUrl = img?.attr("abs:src")

            SAnime.create().apply {
                url = animeUrl
                this.title = title
                thumbnail_url = imageUrl
                description = "Último episodio disponible"
            }
        }.distinctBy {
            it.url
        }

        val hasNextPage = document.selectFirst(
            "a[rel=next]",
        ) != null

        return AnimesPage(
            animeList,
            hasNextPage,
        )
    }

    // ====================== BÚSQUEDA ======================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val filterParams = filters.buildFilterParams()

        return GET(
            "$baseUrl/browse?q=$query&page=$page$filterParams",
            headers,
        )
    }

    override fun searchAnimeParse(
        response: Response,
    ): AnimesPage = popularAnimeParse(response)

    // ====================== DETALLE ======================

    override fun animeDetailsRequest(
        anime: SAnime,
    ): Request = GET(
        "$baseUrl${anime.url}",
        headers,
    )

    override fun animeDetailsParse(
        response: Response,
    ): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title =
                document.selectFirst(".a2-title")
                    ?.text()
                    ?.trim()
                    ?: document.selectFirst("h1, h2.text-2xl")
                        ?.text()
                        ?.trim()
                    ?: "Sin título"

            thumbnail_url =
                document.selectFirst(".a2-poster-img")
                    ?.attr("abs:src")
                    ?: document
                        .selectFirst(
                            "img[src*=/uploads/anime/]",
                        )
                        ?.attr("abs:src")

            description =
                document.selectFirst(".a2-desc-text")
                    ?.text()
                    ?.trim()
                    ?: document.selectFirst(
                        "p.text-sm.leading-relaxed, .sinopsis",
                    )
                        ?.text()
                        ?.trim()

            genre = document
                .select(".a2-genre-tag")
                .joinToString(", ") {
                    it.text().trim()
                }

            val statusElement = document.selectFirst(
                ".a2-badge-status",
            )

            val statusText = statusElement
                ?.text()
                ?.trim()

            status = when {
                statusText?.contains(
                    "Finalizado",
                    ignoreCase = true,
                ) == true -> {
                    SAnime.COMPLETED
                }

                statusText?.contains(
                    "Emisión",
                    ignoreCase = true,
                ) == true -> {
                    SAnime.ONGOING
                }

                else -> {
                    SAnime.UNKNOWN
                }
            }
        }
    }

    // ====================== EPISODIOS ======================

    override fun episodeListRequest(
        anime: SAnime,
    ): Request = GET(
        "$baseUrl${anime.url}",
        headers,
    )

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> {
        val document = response.asJsoup()

        val script = document
            .select("script:containsData(episodes)")
            .firstOrNull()
            ?: return emptyList()

        val scriptData = script.data()

        val jsonRegex = Regex(
            """data:\s*(\{[^{}]*"episodes"\s*:\s*\[[\s\S]*?\][^{}]*\})""",
        )

        val match = jsonRegex.find(scriptData)

        val jsonString =
            match?.groupValues?.get(1)
                ?: return emptyList()

        return try {
            val jsonObject =
                json.parseToJsonElement(
                    jsonString,
                ).jsonObject

            val animeObject =
                jsonObject["anime"]?.jsonObject
                    ?: jsonObject

            val episodesArray =
                animeObject["episodes"]
                    ?.jsonArray
                    ?: return emptyList()

            episodesArray
                .mapNotNull { element ->

                    val obj = element.jsonObject

                    val number =
                        obj["number"]
                            ?.jsonPrimitive
                            ?.content
                            ?.toFloatOrNull()
                            ?: return@mapNotNull null

                    val title =
                        obj["title"]
                            ?.jsonPrimitive
                            ?.content
                            ?: "Episodio $number"

                    val slug =
                        obj["slug"]
                            ?.jsonPrimitive
                            ?.content
                            ?: return@mapNotNull null

                    SEpisode.create().apply {
                        url = "/ver/$slug"
                        name = title
                        episode_number = number
                    }
                }
                .sortedByDescending {
                    it.episode_number
                }
        } catch (e: Exception) {
            // Fallback HTML

            val episodeElements = document.select(
                ".a2-ep-list a.a2-ep-card",
            )

            episodeElements
                .mapNotNull { element ->

                    val href = element.attr("href")

                    val number =
                        extractEpisodeNumber(href)
                            ?: return@mapNotNull null

                    val name =
                        element
                            .selectFirst(".a2-ep-title")
                            ?.text()
                            ?.trim()
                            ?: "Episodio $number"

                    SEpisode.create().apply {
                        url = href
                        this.name = name
                        episode_number = number
                    }
                }
                .sortedByDescending {
                    it.episode_number
                }
        }
    }

    // ====================== VIDEOS ======================

    override fun videoListRequest(
        episode: SEpisode,
    ): Request = GET(
        "$baseUrl${episode.url}",
        headers,
    )

    override fun videoListParse(
        response: Response,
    ): List<Video> {
        val document = response.asJsoup()

        val script = document
            .select("script:containsData(groups)")
            .firstOrNull()
            ?: return emptyList()

        val scriptData = script.data()

        /*
         * AnimeNeon utiliza JavaScript objects.
         *
         * Ejemplo:
         *
         * groups: [{
         *     name: "av1",
         *     servers: [{
         *         link: "...",
         *         hostKey: "mp4upload.com"
         *     }]
         * }]
         *
         * Las keys pueden aparecer con o sin comillas.
         */

        val groupsArray = extractArrayAfterKey(
            scriptData,
            "groups",
        ) ?: return emptyList()

        val allVideos = mutableListOf<Video>()

        /*
         * Buscamos todos los arrays servers dentro
         * del contenido de groups.
         */
        var searchPosition = 0

        while (searchPosition < groupsArray.length) {
            val serversStart = findKey(
                groupsArray,
                "servers",
                searchPosition,
            )

            if (serversStart == -1) {
                break
            }

            val serversArray =
                extractArrayAt(
                    groupsArray,
                    serversStart,
                )

            if (serversArray == null) {
                searchPosition = serversStart + 7
                continue
            }

            /*
             * Extraer los objetos individuales:
             *
             * {
             *     link: "...",
             *     hostKey: "..."
             * }
             */
            val serverObjects =
                extractObjects(serversArray)

            for (serverObj in serverObjects) {
                val link = extractStringProperty(
                    serverObj,
                    "link",
                ) ?: continue

                val hostKey = extractStringProperty(
                    serverObj,
                    "hostKey",
                ) ?: continue

                /*
                 * Mega no dispone de extractor en esta extensión.
                 * Simplemente lo ignoramos.
                 */
                if (
                    hostKey.contains(
                        "mega.nz",
                        ignoreCase = true,
                    ) ||
                    link.contains(
                        "mega.nz",
                        ignoreCase = true,
                    )
                ) {
                    searchPosition = serversStart + 7
                    continue
                }

                val videos = resolveServer(
                    link,
                    hostKey,
                )

                allVideos.addAll(videos)
            }

            searchPosition =
                serversStart +
                serversArray.length
        }

        return allVideos
    }

    // ====================== RESOLVER SERVIDOR ======================

    private fun resolveServer(
        url: String,
        hostKey: String,
    ): List<Video> {
        val normalizedHost =
            hostKey.lowercase()

        /*
         * Primero usamos hostKey.
         * Si AnimeNeon cambia el valor,
         * usamos también la URL como fallback.
         */

        val matchedServer =
            serverConventions.firstOrNull { (_, aliases) ->

                aliases.any { alias ->

                    normalizedHost.contains(
                        alias.lowercase(),
                    )
                }
            }?.first

        val fallbackServer =
            matchedServer
                ?: serverConventions.firstOrNull { (_, aliases) ->

                    aliases.any { alias ->

                        url.contains(
                            alias,
                            ignoreCase = true,
                        )
                    }
                }?.first

        return when (fallbackServer) {
            "mp4upload" -> {
                /*
                 * IMPORTANTE:
                 * Antes se utilizaba Headers.headersOf(),
                 * dejando el extractor sin headers.
                 *
                 * Ahora utilizamos los headers de la extensión.
                 */
                mp4uploadExtractor.videosFromUrl(
                    url,
                    headers = headers,
                    prefix = "Mp4upload - ",
                )
            }

            "voe" -> {
                voeExtractor.videosFromUrl(
                    url,
                    prefix = "Voe - ",
                )
            }

            "mixdrop" -> {
                mixdropExtractor.videosFromUrl(
                    url,
                    prefix = "Mixdrop - ",
                    lang = "es",
                )
            }

            "streamtape" -> {
                val video =
                    streamTapeExtractor.videoFromUrl(
                        url,
                        quality = "Streamtape",
                    )

                if (video != null) {
                    listOf(video)
                } else {
                    emptyList()
                }
            }

            "lulu" -> {
                luluExtractor.videosFromUrl(
                    url,
                    prefix = "Lulu - ",
                )
            }

            else -> {
                /*
                 * Si AnimeNeon añade un servidor nuevo,
                 * intentamos UniversalExtractor.
                 *
                 * Mega se filtra anteriormente y nunca llega aquí.
                 */
                universalExtractor.videosFromUrl(
                    url,
                    headers,
                )
            }
        }
    }

    // ====================== PARSER JAVASCRIPT ======================

    /**
     * Encuentra una propiedad y devuelve el array
     * que comienza después de ella.
     *
     * Acepta:
     *
     * groups: [...]
     * "groups": [...]
     * 'groups': [...]
     */
    private fun extractArrayAfterKey(
        text: String,
        key: String,
    ): String? {
        val position = findKey(
            text,
            key,
            0,
        )

        if (position == -1) {
            return null
        }

        return extractArrayAt(
            text,
            position,
        )
    }

    /**
     * Busca una key JavaScript ignorando si está
     * entre comillas o no.
     */
    private fun findKey(
        text: String,
        key: String,
        start: Int,
    ): Int {
        val pattern = Regex(
            """(?:"|')?${
                Regex.escape(key)
            }(?:"|')?\s*:""",
        )

        val match = pattern.find(
            text,
            start,
        )

        return match?.range?.first ?: -1
    }

    /**
     * Extrae un array [] respetando arrays anidados,
     * strings y caracteres escapados.
     */
    private fun extractArrayAt(
        text: String,
        keyPosition: Int,
    ): String? {
        val colon = text.indexOf(
            ':',
            keyPosition,
        )

        if (colon == -1) {
            return null
        }

        var start = colon + 1

        while (
            start < text.length &&
            text[start].isWhitespace()
        ) {
            start++
        }

        if (
            start >= text.length ||
            text[start] != '['
        ) {
            return null
        }

        val end = findMatchingBracket(
            text,
            start,
            '[',
            ']',
        ) ?: return null

        return text.substring(
            start,
            end + 1,
        )
    }

    /**
     * Encuentra el ] correspondiente al [.
     */
    private fun findMatchingBracket(
        text: String,
        start: Int,
        openChar: Char,
        closeChar: Char,
    ): Int? {
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

            if (
                char == '"' ||
                char == '\''
            ) {
                inString = true
                stringChar = char
                continue
            }

            if (char == openChar) {
                depth++
            } else if (char == closeChar) {
                depth--

                if (depth == 0) {
                    return i
                }
            }
        }

        return null
    }

    /**
     * Extrae todos los objetos { ... } de un array.
     *
     * Los objetos de servers actualmente son planos,
     * pero el método también soporta objetos anidados.
     */
    private fun extractObjects(
        arrayText: String,
    ): List<String> {
        val objects = mutableListOf<String>()

        var position = 0

        while (position < arrayText.length) {
            val start =
                arrayText.indexOf(
                    '{',
                    position,
                )

            if (start == -1) {
                break
            }

            val end = findMatchingBracket(
                arrayText,
                start,
                '{',
                '}',
            )

            if (end == null) {
                break
            }

            objects.add(
                arrayText.substring(
                    start,
                    end + 1,
                ),
            )

            position = end + 1
        }

        return objects
    }

    /**
     * Extrae:
     *
     * link: "..."
     * "link": "..."
     * 'link': '...'
     */
    private fun extractStringProperty(
        text: String,
        key: String,
    ): String? {
        val regex = Regex(
            """(?:"|')?${Regex.escape(key)}(?:"|')?\s*:\s*["']([^"']+)["']""",
        )

        return regex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
    }

    // ====================== ORDENAMIENTO ======================

    override fun List<Video>.sort(): List<Video> {
        val quality =
            preferences.getString(
                prefQualityKey,
                defaultQuality,
            ) ?: defaultQuality

        val server =
            preferences.getString(
                prefServerKey,
                defaultServer,
            ) ?: defaultServer

        return sortedWith(
            compareBy(
                {
                    !it.quality.contains(
                        server,
                        ignoreCase = true,
                    )
                },
                {
                    !it.quality.contains(
                        quality,
                        ignoreCase = true,
                    )
                },
                {
                    qualityRegex
                        .find(it.quality)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: 0
                },
            ),
        )
    }

    // ====================== FILTROS ======================

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

    // ====================== PREFERENCIAS ======================

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
        ListPreference(screen.context).apply {
            key = prefServerKey

            title = "Preferred Server"

            summary =
                "Select the server to show first"

            entries = serverList

            entryValues = serverList

            setDefaultValue(
                defaultServer,
            )

            value =
                preferences.getString(
                    prefServerKey,
                    defaultServer,
                )

            setOnPreferenceChangeListener {
                    _,
                    newValue,
                ->

                preferences
                    .edit()
                    .putString(
                        prefServerKey,
                        newValue as String,
                    )
                    .apply()

                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = prefQualityKey

            title = "Preferred Quality"

            summary =
                "Select the quality to show first"

            entries = qualityList

            entryValues = qualityList

            setDefaultValue(
                defaultQuality,
            )

            value =
                preferences.getString(
                    prefQualityKey,
                    defaultQuality,
                )

            setOnPreferenceChangeListener {
                    _,
                    newValue,
                ->

                preferences
                    .edit()
                    .putString(
                        prefQualityKey,
                        newValue as String,
                    )
                    .apply()

                true
            }
        }.also(screen::addPreference)
    }

    // ====================== AUXILIARES ======================

    private fun parseAnimeCard(
        element: Element,
    ): SAnime? {
        val href = element.attr("href")

        if (!href.startsWith("/anime/")) {
            return null
        }

        val title =
            element
                .selectFirst(
                    "h3.text-\\[13px\\].font-semibold",
                )
                ?.text()
                ?.trim()
                ?: element
                    .selectFirst("h3")
                    ?.text()
                    ?.trim()
                ?: return null

        val img =
            element.selectFirst("img")

        val imageUrl =
            img?.attr("abs:src")

        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = imageUrl
        }
    }

    private fun extractEpisodeNumber(
        url: String,
    ): Float? {
        val regex =
            Regex("-(\\d+)\\.")

        val match =
            regex.find(url)

        return match
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
    }
}
