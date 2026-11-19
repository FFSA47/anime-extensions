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

        private val ANIME_INFO_PATTERN = Regex("var anime_info = \\[(.*?)];")
        private val EPISODES_ARRAY_PATTERN =
            Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
        private val EPISODE_NUMBER_PATTERN = Regex("\\[(\\d+),")
        private val VIDEO_IFRAME_PATTERN = Regex("video\\[\\d+] = '<iframe src=\"([^\"]+)")
        private val RESOLUTION_PATTERN = Regex("(\\d+)p")
    }

    // ----------------------------------------------------------------- Popular

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/animes?order=rating&page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
        title = element.select("a h3").text()
        thumbnail_url = element.selectFirst("a div.Image figure img")?.attr("abs:src")
        description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=\"next\"]"

    // ------------------------------------------------------------------ Latest

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/animes?order=updated&page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ------------------------------------------------------------------ Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimejlFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> {
                val url = "$baseUrl/animes".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString())
                    .build()
                GET(url, headers)
            }
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ----------------------------------------------------------------- Details

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")?.attr("abs:src")
        title = document.selectFirst("div.Ficha.fchlt div.Container .Title")?.text()
            ?: error("Missing anime title")
        description = document.selectFirst("div.Description")?.text()?.removeSurrounding("\"")
        genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        status = parseStatus(document.select("span.fa-tv").text())
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("En emision") -> SAnime.ONGOING
        statusString.contains("Finalizado") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ---------------------------------------------------------------- Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var episodes =)")?.data()
            ?: return emptyList()

        val animeInfo = ANIME_INFO_PATTERN.find(script)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim('"') }
            ?: return emptyList()

        val animeId = animeInfo.getOrNull(0) ?: return emptyList()
        val animeSlug = animeInfo.getOrNull(2).orEmpty()

        val episodesArray = EPISODES_ARRAY_PATTERN.find(script)
            ?.groupValues?.get(1)
            ?: return emptyList()

        return EPISODE_NUMBER_PATTERN.findAll(episodesArray)
            .mapNotNull { match ->
                val episodeNumber = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain("$baseUrl/anime/$animeId/$animeSlug/episodio-$episodeNumber")
                    this.episode_number = episodeNumber.toFloat()
                    name = "Episodio $episodeNumber"
                }
            }
            .sortedByDescending { it.episode_number }
            .toList()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    // ------------------------------------------------------------------ Videos

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

        return VIDEO_IFRAME_PATTERN.findAll(scriptContent)
            .map { it.groupValues[1] }
            .toList()
            .catchingFlatMapBlocking { url ->
                when {
                    url.contains("streamtape") -> listOfNotNull(streamTapeExtractor.videoFromUrl(url))
                    url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
                    url.contains("yourupload") -> yourUploadExtractor.videoFromUrl(url, headers)
                    url.contains("streamwish") || url.contains("playerwish") -> streamWishExtractor.videosFromUrl(url)
                    url.contains("streamhidevid") -> vidHideExtractor.videosFromUrl(url)
                    url.contains("voe") -> voeExtractor.videosFromUrl(url)
                    url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
                    url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
                    else -> universalExtractor.videosFromUrl(url, headers)
                }
            }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { RESOLUTION_PATTERN.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ----------------------------------------------------------------- Filters

    override fun getFilterList(): AnimeFilterList = AnimejlFilters.FILTER_LIST

    // ------------------------------------------------------------- Preferences

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
