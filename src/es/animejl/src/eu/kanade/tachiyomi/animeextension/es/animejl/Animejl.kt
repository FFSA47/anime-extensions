package eu.kanade.tachiyomi.animeextension.es.animejl

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Animejl :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish", "YourUpload", "Okru", "StreamTape",
            "StreamHideVid", "Voe", "Uqload", "Mp4upload",
        )

        private val EPISODES_PATTERN = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
        private val ANIME_INFO_PATTERN = Regex("var anime_info = \\[(.*?)]")
        private val EPISODE_ENTRY_PATTERN = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
        private val VIDEO_PATTERN = Regex("""video\d+ = '([^']+)'""")
        private val IFRAME_SRC_PATTERN = Regex("""src=["']([^"']+)["']""")
        private val QUALITY_PATTERN = Regex("""(\d+)p""")
    }

    // -------------------------------- Popular Anime --------------------------------

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?order=rating&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = element.select("a div.Image figure img").attr("abs:src")
        anime.description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=\"next\"]"

    // -------------------------------- Episode List --------------------------------

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(var episodes =)").firstOrNull()?.data()
            ?: return emptyList()

        val episodesMatch = EPISODES_PATTERN.find(script) ?: return emptyList()
        val episodesString = episodesMatch.groupValues[1]

        val animeInfoMatch = ANIME_INFO_PATTERN.find(script) ?: return emptyList()
        val animeInfo = animeInfoMatch.groupValues[1].split(",").map { it.trim('"') }
        val animeSlug = animeInfo.getOrNull(2).orEmpty()
        val animeId = animeInfo.getOrNull(0).orEmpty()

        return EPISODE_ENTRY_PATTERN.findAll(episodesString).mapNotNull { match ->
            val episodeNumber = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val url = "$baseUrl/anime/$animeId/$animeSlug/episodio-$episodeNumber"
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                episode_number = episodeNumber.toFloat()
                name = "Episodio $episodeNumber"
            }
        }.sortedByDescending { it.episode_number }
    }

    // -------------------------------- Video List --------------------------------

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptContent = document.selectFirst("script:containsData(var video = [)")?.data()
            ?: return emptyList()

        return VIDEO_PATTERN.findAll(scriptContent).toList().catchingFlatMapBlocking { match ->
            val rawValue = match.groupValues[1]
            val url = if (rawValue.trimStart().startsWith("<iframe", ignoreCase = true)) {
                IFRAME_SRC_PATTERN.find(rawValue)?.groupValues?.get(1)
                    ?: return@catchingFlatMapBlocking emptyList<Video>()
            } else {
                rawValue
            }
            if (url.isBlank()) return@catchingFlatMapBlocking emptyList<Video>()

            when {
                url.contains("streamtape") -> listOfNotNull(streamTapeExtractor.videoFromUrl(url))
                url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
                url.contains("yourupload") -> yourUploadExtractor.videoFromUrl(url, headers)
                url.contains("streamwish") ||
                    url.contains("playerwish") ||
                    url.contains("bysezoxexe") -> streamWishExtractor.videosFromUrl(url)
                url.contains("streamhidevid") || url.contains("vidhide") ->
                    vidHideExtractor.videosFromUrl(url)
                url.contains("voe") -> voeExtractor.videosFromUrl(url)
                url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
                url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        }.distinctBy { it.videoUrl }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // -------------------------------- Search --------------------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET(
                "$baseUrl/animes".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString())
                    .build(),
            )
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // -------------------------------- Anime Details --------------------------------

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url =
            document.selectFirst("div.AnimeCover div.Image figure img")!!.attr("abs:src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        anime.description = document.selectFirst("div.Description")?.text()?.removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }.takeIf { it.isNotEmpty() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        val s = statusString.lowercase()
        return when {
            s.contains("en emision") -> SAnime.ONGOING
            s.contains("finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // -------------------------------- Latest Anime --------------------------------

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?order=updated&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    // -------------------------------- Preferences --------------------------------

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_PATTERN.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

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
}
