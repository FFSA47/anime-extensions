package eu.kanade.tachiyomi.animeextension.es.animeid

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.decodeHexToString
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class AnimeID :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeID"
    override val baseUrl = "https://wwv.animeid2.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== EXTRACTORS ==============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val urlRegex = """(http|ftp|https)://([\w_-]+(?:(?:\.[\w_-]+)+))([\w.,@?^=%&:/~+#-]*[\w@?^=%&/~+#-])""".toRegex()
    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")

    // ============================== SERVER CONVENTIONS (ALIAS) ==============================
    
    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx", "bysekoze"),
        "uqload" to listOf("uqload", "upload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "kswplayer", "swhoi", "multimovies", "uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d", "dooodster", "dvsplayer", "dvsplay", "playmogo"),
        "lulu" to listOf("luluvdo", "lulu", "lulustream"),
    )

    // Backup domains for DoodStream (constantly changing)
    private val doodstreamDomains = listOf(
        "dvsplay.com", "doodstream.com", "dooodster.com", "dood.to", "dooood.com",
        "doods.pro", "d000d.com", "ds2play.com", "ds2video.com", "d0000d.com",
        "playmogo.com", "dvsplayer.com",
    )
    private var cachedDoodDomain: String? = null

    // ============================== Seasons (not supported) ===============================
    override fun seasonListSelector(): String = ""
    override fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException("Seasons not supported")

    // ============================== Hoster ===============================
    override fun hosterListParse(response: Response): List<Hoster> = listOf(Hoster(hosterUrl = response.request.url.toString()))

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.ul.x5 article.li, div.ul article.li"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series?pag=$page"
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a") ?: throw Exception("AnimeID: no <a> element found")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid href '${linkElement.attr("href")}'")
        
        anime.setUrlWithoutDomain(httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: ""))
        anime.title = linkElement.selectFirst("span")?.text()?.trim() ?: throw Exception("AnimeID: missing title")

        element.selectFirst("figure.i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() } ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }?.let { anime.thumbnail_url = it }
        
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pag li a:contains(Siguiente)"

    // ============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "div.ul.hm article.li"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl?pag=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a") ?: throw Exception("AnimeID: no <a> element found")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid href '${linkElement.attr("href")}'")
        
        val path = httpUrl.encodedPath.removePrefix("/")
        val animeSlug = if ("ver/" in path) {
            path.substringAfter("ver/").substringBeforeLast("-")
        } else {
            path.substringBeforeLast("-")
        }
        if (animeSlug.isBlank()) throw Exception("AnimeID: could not parse slug")
        
        anime.setUrlWithoutDomain("/$animeSlug")
        anime.title = linkElement.selectFirst("span")?.text()?.trim() ?: throw Exception("AnimeID: missing title")

        element.selectFirst("figure.i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() } ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }?.let { anime.thumbnail_url = it }
        
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = "ul.pag li a:contains(Siguiente)"

    // ============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.firstInstanceOrNull<Filters.GenreFilter>()
        val yearFilter = filterList.firstInstanceOrNull<Filters.YearFilter>()
        val statusFilter = filterList.firstInstanceOrNull<Filters.StatusFilter>()
        val orderFilter = filterList.firstInstanceOrNull<Filters.OrderFilter>()

        val urlBuilder = "$baseUrl/series".toHttpUrlOrNull()!!.newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("buscar", query)
        } else {
            genreFilter?.takeIf { it.state != 0 }?.let { urlBuilder.addQueryParameter("genero", it.toUriPart()) }
            yearFilter?.takeIf { it.state != 0 }?.let { urlBuilder.addQueryParameter("anio", it.toUriPart()) }
            statusFilter?.takeIf { it.state != 0 }?.let { urlBuilder.addQueryParameter("estado", it.toUriPart()) }
            orderFilter?.takeIf { it.state != 0 }?.let { urlBuilder.addQueryParameter("sort", it.toUriPart()) }
        }

        if (page > 1) {
            urlBuilder.addQueryParameter("pag", page.toString())
        }

        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // ============================== Anime Details ===============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.info-a .c h1")?.text()?.trim() ?: throw Exception("AnimeID: title not found")
        
        anime.thumbnail_url = document.selectFirst("div.info-a .i img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() } ?: img.absUrl("src").takeIf { it.isNotEmpty() }
        }
        
        anime.description = document.selectFirst("div.info-a .c .tx p")?.text()?.trim()
        anime.genre = document.select("div.info-a .c .gn li a").joinToString { it.text() }
        
        val statusText = document.selectFirst("div.info-b .dv ul li strong.ee, div.info-b .dv ul li strong.ef")?.text()?.trim()
        anime.status = when {
            statusText?.contains("emision", ignoreCase = true) == true -> SAnime.ONGOING
            statusText?.contains("finalizada", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        
        return anime
    }

    // ============================== Episodes ===============================
    override fun episodeListSelector(): String = "ul.epis li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val linkElement = element.selectFirst("a") ?: throw Exception("AnimeID: no <a> in episode")
        val httpUrl = linkElement.absUrl("href").toHttpUrlOrNull()
            ?: throw Exception("AnimeID: invalid episode href '${linkElement.attr("href")}'")
        
        val episodeNumber = httpUrl.encodedPath.substringAfterLast("-").toFloatOrNull() ?: 1f
        episode.setUrlWithoutDomain(httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: ""))
        
        val episodeDisplay = if (episodeNumber == episodeNumber.toInt().toFloat()) {
            episodeNumber.toInt().toString()
        } else {
            episodeNumber.toString()
        }
        
        episode.name = "Episodio $episodeDisplay"
        episode.episode_number = episodeNumber
        return episode
    }

    override fun episodeListParse(response: Response): List<SEpisode> = runBlocking {
        val doc = response.asJsoup()
        val animeId = doc.selectFirst("#dt")?.attr("data-i") ?: return@runBlocking emptyList()
        val animeSlug = doc.selectFirst("#dt")?.attr("data-u") ?: return@runBlocking emptyList()

        val allEpisodes = mutableListOf<SEpisode>()
        var currentPage = 1
        val maxPages = 200

        while (currentPage <= maxPages) {
            val formBody = FormBody.Builder()
                .add("acc", "episodes")
                .add("i", animeId)
                .add("u", animeSlug)
                .add("p", currentPage.toString())
                .build()
            
            val pageDoc = postToIdEndpoint(formBody) ?: break
            val episodesOnPage = pageDoc.select(episodeListSelector()).map { episodeFromElement(it) }
            
            if (episodesOnPage.isEmpty()) break
            
            allEpisodes.addAll(episodesOnPage)
            currentPage++
            delay(100)
        }
        
        allEpisodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    // ============================== Videos ===============================
    
    
    private suspend fun serverVideoResolver(url: String): List<Video> {
        val source = url.lowercase()
        
        
        val matched = conventions.firstOrNull { (_, aliases) ->
            aliases.any { it in source }
        }?.first

        return try {
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, prefix = "Voe")
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "Mp4upload")
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix = "Uqload")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
                "lulu" -> luluExtractor.videosFromUrl(url, prefix = "LuluStream:")
                
            
                "doodstream" -> {
                    val candidates = buildDoodCandidates(url)
                    for (candidate in candidates) {
                        val videos = tryDoodExtract(candidate)
                        if (videos.isNotEmpty()) {
                            candidate.toHttpUrlOrNull()?.host?.let { cachedDoodDomain = it }
                            return videos
                        }
                    }
                    emptyList()
                }
                
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            universalExtractor.videosFromUrl(url, headers)
        }
    }

    // Generates alternative URLs for DoodStream if the original domain fails
    private fun buildDoodCandidates(url: String): List<String> {
        val httpUrl = url.toHttpUrlOrNull() ?: return listOf(url)
        val path = httpUrl.encodedPath
        val query = httpUrl.encodedQuery

        val orderedDomains = buildList {
            cachedDoodDomain?.let { add(it) }
            addAll(doodstreamDomains.filter { it != cachedDoodDomain })
        }

        return orderedDomains.map { host ->
            if (query != null) "https://$host$path?$query" else "https://$host$path"
        }
    }

    private suspend fun tryDoodExtract(url: String): List<Video> = try {
        doodExtractor.videosFromUrl(url, "DoodStream:")
    } catch (_: Exception) {
        emptyList()
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = runBlocking {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.selectFirst("ul.opt[data-encrypt]")?.let { optElement ->
            val encryptedId = optElement.attr("data-encrypt")
            val formBody = FormBody.Builder()
                .add("acc", "opt")
                .add("i", encryptedId)
                .build()
            
            postToIdEndpoint(formBody)?.let { serversDoc ->
                for (pageUrl in parseServerUrls(serversDoc)) {
                    videos.addAll(serverVideoResolver(pageUrl))
                }
                if (videos.isNotEmpty()) return@runBlocking orderVideosByPreferences(videos)
            }
        }

        val scripts: Elements = document.select("#partes div.container li.subtab div.parte")
        for (script in scripts) {
            val jsonString = script.attr("data")
            val jsonUnescape = unescapeJava(jsonString).replace("\\", "")
            val url = fetchUrls(jsonUnescape).firstOrNull()?.replace("\\\\", "\\") ?: ""
            if (url.isNotBlank()) {
                videos.addAll(serverVideoResolver(url))
            }
        }

        orderVideosByPreferences(videos)
    }

    private fun postToIdEndpoint(formBody: FormBody): Document? {
        val requestHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        val request = Request.Builder()
            .url("$baseUrl/id")
            .post(formBody)
            .headers(requestHeaders)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.asJsoup()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun parseServerUrls(document: Document): List<String> = buildList {
        for (item in document.select("li")) {
            val encryptedUrl = item.attr("encrypt")
            if (encryptedUrl.isNotEmpty()) {
                val decodedUrl = encryptedUrl.decodeHexToString()
                if (decodedUrl.isNotEmpty()) add(decodedUrl)
            }
        }
    }

    private fun orderVideosByPreferences(videos: List<Video>): List<Video> {
        val preferredServer = preferences.getString("animeid_preferred_server", "voe")?.lowercase()
        val preferredQuality = preferences.getString("animeid_preferred_quality", "any")?.lowercase()

        fun detectServer(video: Video): String = when {
            video.videoTitle.startsWith("Voe", ignoreCase = true) -> "voe"
            video.videoTitle.startsWith("Mp4upload", ignoreCase = true) -> "mp4upload"
            video.videoTitle.startsWith("Uqload", ignoreCase = true) -> "uqload"
            video.videoTitle.startsWith("StreamWish", ignoreCase = true) -> "streamwish"
            video.videoTitle.startsWith("DoodStream", ignoreCase = true) -> "doodstream"
            video.videoTitle.startsWith("Filemoon", ignoreCase = true) -> "filemoon"
            video.videoTitle.startsWith("LuluStream", ignoreCase = true) -> "lulustream"
            else -> "other"
        }

        fun qualityScore(video: Video): Int {
            if (preferredQuality.isNullOrBlank() || preferredQuality == "any" || preferredQuality == "automatic") {
                return 0
            }
            val qToken = "${preferredQuality}p"
            return when {
                video.videoTitle.contains(qToken, ignoreCase = true) -> 3
                video.videoTitle.contains(preferredQuality, ignoreCase = true) -> 2
                video.videoUrl.contains("/$qToken", ignoreCase = true) -> 3
                else -> 0
            }
        }

        val grouped = videos.groupBy { detectServer(it) }
        val order = mutableListOf<Video>()

        if (!preferredServer.isNullOrBlank()) {
            grouped[preferredServer]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        for (srv in listOf("voe", "mp4upload", "uqload", "streamwish", "doodstream", "filemoon", "lulustream", "other")) {
            if (srv == preferredServer) continue
            grouped[srv]?.let { list ->
                order.addAll(list.sortedByDescending { qualityScore(it) })
            }
        }

        val addedUrls = order.map { it.videoUrl }.toSet()
        videos.filter { it.videoUrl !in addedUrls }.let { leftovers ->
            order.addAll(leftovers.sortedByDescending { qualityScore(it) })
        }

        return order
    }

    // ============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "animeid_preferred_server"
            title = "Preferred server"
            entries = arrayOf("Voe", "Mp4upload", "Uqload", "StreamWish", "DoodStream", "Filemoon", "LuluStream")
            entryValues = arrayOf("voe", "mp4upload", "uqload", "streamwish", "doodstream", "filemoon", "lulustream")
            setDefaultValue("voe")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "animeid_preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Automatic", "480p", "720p", "1080p")
            entryValues = arrayOf("any", "480", "720", "1080")
            setDefaultValue("any")
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Utilities ===============================
    private fun unescapeJava(escaped: String): String = unicodeEscapeRegex.replace(escaped) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return urlRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }
}
