package eu.kanade.tachiyomi.animeextension.es.animelatinohd

import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
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
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

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

        // Dominios alternativos
        private val FILEMOON_DOMAINS = listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx")
        private val VOE_DOMAINS = listOf(
            "voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath",
            "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"
        )
        private val LULU_DOMAINS = listOf("luluvdo", "lulu", "lulustream")
    }

    // ====================== Popular ======================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/animes/populares")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("#__next > main > div > div[class*=\"Animes_paginate\"] a:last-child svg").any()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                val items = data["data"]?.jsonArray ?: data["popular_today"]?.jsonArray
                items?.forEach { item ->
                    val animeItem = item.jsonObject
                    val anime = SAnime.create().apply {
                        setUrlWithoutDomain("anime/${animeItem["slug"]?.jsonPrimitive?.content ?: return@forEach}")
                        thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]?.jsonPrimitive?.content ?: ""}"
                        title = animeItem["name"]?.jsonPrimitive?.content ?: ""
                    }
                    animeList.add(anime)
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
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
                setUrlWithoutDomain("anime/$slug")
                thumbnail_url = poster
                this.title = title
            }
            animeMap[slug] = anime
        }

        if (animeMap.isEmpty()) {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\":")) {
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
                            setUrlWithoutDomain("anime/$slug")
                            thumbnail_url = "https://image.tmdb.org/t/p/w200${animeData["poster"]?.jsonPrimitive?.content ?: ""}"
                            title = animeData["name"]?.jsonPrimitive?.content ?: ""
                        }
                        animeMap[slug] = anime
                    }
                }
            }
        }

        return AnimesPage(animeMap.values.toList(), false)
    }

    // ====================== Anime Details ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                anime.title = data["name"]?.jsonPrimitive?.content ?: ""
                anime.genre = data["genres"]?.jsonPrimitive?.content?.split(",")?.joinToString() ?: ""
                anime.description = data["overview"]?.jsonPrimitive?.content ?: ""
                anime.status = parseStatus(data["status"]?.jsonPrimitive?.content ?: "")
                anime.thumbnail_url = "https://image.tmdb.org/t/p/w600_and_h900_bestv2${data["poster"]?.jsonPrimitive?.content ?: ""}"
                anime.setUrlWithoutDomain("anime/${data["slug"]?.jsonPrimitive?.content ?: ""}")
            }
        }
        return anime
    }

    // ====================== Episode List ======================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                val episodes = data["episodes"]?.jsonArray ?: return@forEach
                episodes.forEach { item ->
                    val episodeObj = item.jsonObject
                    val number = episodeObj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@forEach
                    val episode = SEpisode.create().apply {
                        setUrlWithoutDomain("ver/${data["slug"]?.jsonPrimitive?.content ?: ""}/$number")
                        episode_number = number
                        name = "Episodio $number"
                    }
                    episodeList.add(episode)
                }
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
            episodeList.sortBy { it.episode_number }
        }

        return episodeList
    }

    // ====================== Video List ======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
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
            }
        }

        return videoList
    }

    private fun extractVideosFromUrl(url: String, language: String): List<Video> {
        val host = url.toHttpUrl().host.lowercase()
        return when {
            FILEMOON_DOMAINS.any { host.contains(it) } -> {
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$language Filemoon:", headers = headers)
            }
            VOE_DOMAINS.any { host.contains(it) } -> {
                VoeExtractor(client).videosFromUrl(url, prefix = "$language Voe:")
            }
            LULU_DOMAINS.any { host.contains(it) } -> {
                LuluExtractor(client).videosFromUrl(url, prefix = "$language Lulu:")
            }
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
        AnimeFilter.Header("La busqueda por texto ignora los filtros"),
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
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val pageProps = jObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                val data = pageProps?.get("data")?.jsonObject ?: return@forEach

                val items = data["data"]?.jsonArray ?: return@forEach
                items.forEach { item ->
                    val animeItem = item.jsonObject
                    val anime = SAnime.create().apply {
                        setUrlWithoutDomain("anime/${animeItem["slug"]?.jsonPrimitive?.content ?: return@forEach}")
                        thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]?.jsonPrimitive?.content ?: ""}"
                        title = animeItem["name"]?.jsonPrimitive?.content ?: ""
                    }
                    animeList.add(anime)
                }
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
            "Géneros",
            arrayOf(
                Pair("<Selecionar>", ""),
                Pair("Acción", "accion"),
                Pair("Aliens", "aliens"),
                Pair("Artes Marciales", "artes-marciales"),
                Pair("Aventura", "aventura"),
                Pair("Ciencia Ficción", "ciencia-ficcion"),
                Pair("Comedia", "comedia"),
                Pair("Cyberpunk", "cyberpunk"),
                Pair("Demonios", "demonios"),
                Pair("Deportes", "deportes"),
                Pair("Detectives", "detectives"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Escolar", "escolar"),
                Pair("Espacio", "espacio"),
                Pair("Fantasía", "fantasia"),
                Pair("Gore", "gore"),
                Pair("Harem", "harem"),
                Pair("Histórico", "historico"),
                Pair("Horror", "horror"),
                Pair("Josei", "josei"),
                Pair("Juegos", "juegos"),
                Pair("Kodomo", "kodomo"),
                Pair("Magia", "magia"),
                Pair("Maho Shoujo", "maho-shoujo"),
                Pair("Mecha", "mecha"),
                Pair("Militar", "militar"),
                Pair("Misterio", "misterio"),
                Pair("Musica", "musica"),
                Pair("Parodia", "parodia"),
                Pair("Policial", "policial"),
                Pair("Psicológico", "psicologico"),
                Pair("Recuentos De La Vida", "recuentos-de-la-vida"),
                Pair("Romance", "romance"),
                Pair("Samurais", "samurais"),
                Pair("Seinen", "seinen"),
                Pair("Shoujo", "shoujo"),
                Pair("Shoujo Ai", "shoujo-ai"),
                Pair("Shounen", "shounen"),
                Pair("Shounen Ai", "shounen-ai"),
                Pair("Sobrenatural", "sobrenatural"),
                Pair("Soft Hentai", "soft-hentai"),
                Pair("Super Poderes", "super-poderes"),
                Pair("Suspenso", "suspenso"),
                Pair("Terror", "terror"),
                Pair("Vampiros", "vampiros"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
            ),
        )

    private class StateFilter :
        UriPartFilter(
            "Estado",
            arrayOf(
                Pair("Todos", ""),
                Pair("Finalizado", "0"),
                Pair("En emisión", "1"),
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("Animes", "tv"),
                Pair("Películas", "movie"),
                Pair("Especiales", "special"),
                Pair("OVAS", "ova"),
                Pair("ONAS", "ona"),
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
            title = "Idioma preferido"
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
            title = "Calidad preferida"
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
            title = "Servidor preferido"
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

    // ============================================================
    //  EXTRACTORES SIMPLIFICADOS (Voe y Lulu)
    // ============================================================

    // ---------- VoeExtractor (sin dependencias externas) ----------
    private class VoeExtractor(private val client: OkHttpClient) {

        private val json: Json by injectLazy()

        private val redirectRegex = Regex("""window.location.href\s*=\s*'([^']+)';""")

        fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
            val videoList = mutableListOf<Video>()
            var document = client.newCall(GET(url)).execute().asJsoup()
            var baseUrl = url

            // Manejar redirección
            val scriptData = document.selectFirst("script")?.data()
            val redirectMatch = scriptData?.let { redirectRegex.find(it) }
            if (redirectMatch != null) {
                val originalUrl = redirectMatch.groupValues[1]
                baseUrl = originalUrl
                document = client.newCall(GET(originalUrl)).execute().asJsoup()
            }

            // Obtener JSON cifrado
            val encodedString = document.selectFirst("script[type=application/json]")?.data()
                ?.trim()?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: return emptyList()

            val decryptedJson = decryptF7(encodedString) ?: return emptyList()
            val m3u8 = decryptedJson["source"]?.jsonPrimitive?.content
            val mp4 = decryptedJson["direct_access_url"]?.jsonPrimitive?.content

            var cleanPrefix = prefix.trim()
            if (cleanPrefix.startsWith("(") && cleanPrefix.endsWith(")")) {
                cleanPrefix = cleanPrefix.substring(1, cleanPrefix.length - 1).trim()
            }
            if (cleanPrefix.endsWith("-")) {
                cleanPrefix = cleanPrefix.removeSuffix("-").trim()
            }
            val displayPrefix = if (cleanPrefix.isNotBlank()) cleanPrefix else "VOE"

            // Extraer videos desde m3u8
            if (m3u8 != null) {
                try {
                    val m3u8Content = client.newCall(GET(m3u8)).execute().bodyString()
                    val resolutionRegex = Regex("""#EXT-X-STREAM-INF:.*RESOLUTION=\d+x(\d+)""")
                    val urlRegex = Regex("""^(?!\s*#)(https?://[^\s]+)""")
                    var currentQuality = ""
                    m3u8Content.split("\n").forEach { line ->
                        if (line.contains("#EXT-X-STREAM-INF")) {
                            val match = resolutionRegex.find(line)
                            currentQuality = match?.groupValues?.get(1)?.let { "${it}p" } ?: "Unknown"
                        } else if (currentQuality.isNotEmpty() && urlRegex.matches(line.trim())) {
                            val videoUrl = line.trim()
                            val qualityLabel = if (displayPrefix == "VOE") "VOE:$currentQuality" else "$displayPrefix - VOE $currentQuality"
                            videoList.add(Video(videoUrl, qualityLabel, videoUrl))
                            currentQuality = ""
                        }
                    }
                } catch (_: Exception) {
                    // Si falla el parseo, agregamos el m3u8 directo como fallback
                    val fallbackLabel = if (displayPrefix == "VOE") "VOE:HLS" else "$displayPrefix - VOE HLS"
                    videoList.add(Video(m3u8, fallbackLabel, m3u8))
                }
            }

            // Si hay MP4 directo
            if (mp4 != null) {
                val mp4Quality = if (displayPrefix == "VOE") "VOE:MP4" else "$displayPrefix - VOE MP4"
                videoList.add(Video(mp4, mp4Quality, mp4))
            }

            return videoList
        }

        private fun decryptF7(p8: String): JsonObject? = try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = base64Decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = reverse(vF5)
            val vAtob = base64Decode(vF6)
            json.decodeFromString<JsonObject>(vAtob)
        } catch (e: Exception) {
            Log.e("VoeExtractor", "Decryption error: ${e.message}")
            null
        }

        private fun rot13(input: String): String = input.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")

        private val patternsRegex = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").joinToString("|") { Regex.escape(it) }.toRegex()

        private fun replacePatterns(input: String): String = input.replace(patternsRegex, "_")

        private fun removeUnderscores(input: String): String = input.replace("_", "")

        private fun charShift(input: String, shift: Int): String = input.map { (it.code - shift).toChar() }.joinToString("")

        private fun reverse(input: String): String = input.reversed()

        private fun base64Decode(input: String): String {
            val decodedBytes = Base64.decode(input, Base64.DEFAULT)
            return String(decodedBytes, Charsets.ISO_8859_1)
        }
    }

    // ---------- LuluExtractor (sin autoUnpacker) ----------
    private class LuluExtractor(private val client: OkHttpClient) {

        private val headers = Headers.Builder()
            .add("Referer", "https://luluvdo.com/")
            .add("Origin", "https://luluvdo.com")
            .build()

        fun videosFromUrl(url: String, prefix: String): List<Video> {
            val videos = mutableListOf<Video>()

            try {
                val html = client.newCall(GET(url, headers)).execute().body?.string() ?: return emptyList()
                val m3u8Url = extractM3u8Url(html) ?: return emptyList()
                val fixedUrl = fixM3u8Link(m3u8Url)
                val quality = getResolution(fixedUrl)

                videos.add(Video(fixedUrl, "${prefix}Lulu - $quality", fixedUrl, headers))
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return videos
        }

        private fun extractM3u8Url(html: String): String? {
            // Buscar directamente el M3U8 en el HTML sin necesidad de desempaquetar
            val patterns = listOf(
                // Patrón común: sources: [{file:"https://..."}]
                Regex("""sources:\s*\[\s*\{\s*file:\s*"([^"]+)"\s*\}""", RegexOption.IGNORE_CASE),
                // Otro patrón: file: "https://..."
                Regex("""file:\s*"([^"]+\.m3u8[^"]*)" """, RegexOption.IGNORE_CASE),
                // URL directa de m3u8
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            )

            patterns.forEach { pattern ->
                pattern.find(html)?.let { return it.groupValues[1] }
            }

            // Si no se encuentra, intentar con ofuscación simple (eval)
            if (html.contains("eval(function(p,a,c,k,e"))) {
                // Extraer el contenido entre comillas después de eval
                val evalRegex = Regex("""eval\(\s*function\s*\([^)]*\)\s*\{[^}]*\}\s*\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL)
                val match = evalRegex.find(html)
                if (match != null) {
                    val args = match.groupValues[1].split(",").map { it.trim().removeSurrounding("\"") }
                    // Intentar construir la cadena decodificada (muy básico)
                    // Mejor buscar el M3U8 en el resultado de la función (que suele estar en el último argumento)
                    if (args.size >= 2) {
                        val encoded = args[args.size - 1]
                        // Reemplazar patrones simples
                        val decoded = encoded.replace(Regex("""\\\\"""), "\\")
                            .replace(Regex("""\\'"""), "'")
                            .replace(Regex("""\\\"""",), "\"")
                        // Buscar m3u8 en decoded
                        Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(decoded)?.let { return it.groupValues[1] }
                    }
                }
            }

            return null
        }

        private fun fixM3u8Link(link: String): String {
            val paramOrder = listOf("t", "s", "e", "f")
            val params = Pattern.compile("[?&]([^=]*)=([^&]*)").matcher(link).let { matcher ->
                generateSequence { if (matcher.find()) matcher.group(1) to matcher.group(2) else null }.toList()
            }

            val paramDict = mutableMapOf<String, String>()
            val extraParams = mutableMapOf<String, String>()

            params.forEachIndexed { index, (key, value) ->
                if (key.isNullOrEmpty()) {
                    if (index < paramOrder.size) {
                        if (value != null) {
                            paramDict[paramOrder[index]] = value
                        }
                    }
                } else {
                    if (value != null) {
                        extraParams[key] = value
                    }
                }
            }

            extraParams["i"] = "0.3"
            extraParams["sp"] = "0"

            val baseUrl = link.split("?")[0]

            val fixedLink = baseUrl.toHttpUrl().newBuilder()
            paramOrder.filter { paramDict.containsKey(it) }.forEach { key ->
                fixedLink.addQueryParameter(key, paramDict[key])
            }
            extraParams.forEach { (key, value) ->
                fixedLink.addQueryParameter(key, value)
            }

            return fixedLink.build().toString()
        }

        private fun getResolution(m3u8Url: String): String = try {
            val content = client.newCall(GET(m3u8Url, headers)).execute()
                .body?.string() ?: return "Unknown"

            Pattern.compile("RESOLUTION=\\d+x(\\d+)")
                .matcher(content)
                .takeIf { it.find() }
                ?.group(1)
                ?.let { "${it}p" }
                ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }
}
