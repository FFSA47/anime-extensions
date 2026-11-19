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
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setUrlWithoutDomain
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Animejl : AniSource(), ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

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

        private val POPULAR_ANIME_SELECTOR = "div.Container ul.ListAnimes li article"
        private val NEXT_PAGE_SELECTOR = "ul.pagination li a[rel=\"next\"]"

        private val EPISODES_PATTERN = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
        private val ANIME_INFO_PATTERN = Regex("var anime_info = \\[(.*?)];")
        private val EPISODE_PATTERN = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
        private val VIDEO_PATTERN = Regex("""video\[\d+] = '<iframe src="(.*?)"""")
        private val QUALITY_PATTERN = Regex("""(\d+)p""")
    }

    // ---------------------------------------------
    // Popular
    // ---------------------------------------------

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.get("$baseUrl/animes?order=rating&page=$page", headers)
        return popularAnimeParse(response)
    }

    private fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(POPULAR_ANIME_SELECTOR).mapNotNull { element ->
            runCatching { popularAnimeFromElement(element) }.getOrNull()
        }
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val button = element.selectFirst("div.Description a.Button")!!
        val url = button.absUrl("href").ifEmpty { error("Missing anime url") }
        anime.setUrlWithoutDomain(url)
        anime.title = element.selectFirst("a h3")!!.text().ifEmpty { error("Missing title") }
        anime.thumbnail_url = element.selectFirst("a div.Image figure img")?.absUrl("src")
        anime.description = element.selectFirst("div.Description p:eq(2)")?.text()?.removeSurrounding("\"")
        return anime
    }

    // ---------------------------------------------
    // Latest
    // ---------------------------------------------

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.get("$baseUrl/animes?order=updated&page=$page", headers)
        return popularAnimeParse(response)
    }

    // ---------------------------------------------
    // Search
    // ---------------------------------------------

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = Filters.getSearchParameters(filters)
        val url = when {
            query.isNotBlank() -> "$baseUrl/animes?q=$query&page=$page"
            params.filter.isNotBlank() -> "$baseUrl/animes${params.getQuery()}&page=$page"
            else -> "$baseUrl/animes?order=rating&page=$page"
        }
        val response = client.get(url, headers)
        return popularAnimeParse(response)
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // ---------------------------------------------
    // Details
    // ---------------------------------------------

    private suspend fun fetchAnimeDetails(anime: SAnime): SAnime {
        val response = client.get("$baseUrl${anime.url}", headers)
        return animeDetailsParse(response.asJsoup()).apply { initialized = true }
    }

    private fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")?.absUrl("src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!
            .text().ifEmpty { error("Missing title") }
        anime.description = document.selectFirst("div.Description")?.text()?.removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        val lower = statusString.lowercase()
        return when {
            lower.contains("en emision") -> SAnime.ONGOING
            lower.contains("finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ---------------------------------------------
    // Episodes
    // ---------------------------------------------

    private suspend fun fetchEpisodes(anime: SAnime): List<SEpisode> {
        val response = client.get("$baseUrl${anime.url}", headers)
        return episodeListParse(response.asJsoup())
    }

    private fun episodeListParse(document: Document): List<SEpisode> {
        val script = document.select("script:containsData(var episodes =)").firstOrNull()?.data()
            ?: return emptyList()

        val episodesMatch = EPISODES_PATTERN.find(script) ?: return emptyList()
        val episodesString = episodesMatch.groupValues[1]

        val animeInfoMatch = ANIME_INFO_PATTERN.find(script) ?: return emptyList()
        val animeInfo = animeInfoMatch.groupValues[1].split(",").map { it.trim('"') }

        val animeSlug = animeInfo.getOrNull(2) ?: ""
        val animeId = animeInfo.getOrNull(0) ?: ""

        return EPISODE_PATTERN.findAll(episodesString).mapNotNull { match ->
            runCatching {
                val episodeNumber = match.groupValues[1].toIntOrNull() ?: 0
                val url = "$baseUrl/anime/$animeId/$animeSlug/episodio-$episodeNumber"
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    episode_number = episodeNumber.toFloat()
                    name = "Episodio $episodeNumber"
                }
            }.getOrNull()
        }.sortedByDescending { it.episode_number }
    }

    // ---------------------------------------------
    // Anime Update (Lib 16 flow)
    // ---------------------------------------------

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        val updatedAnime = if (fetchDetails) fetchAnimeDetails(anime) else anime
        val updatedEpisodes = if (fetchEpisodes) fetchEpisodes(anime) else episodes
        return SAnimeUpdate(anime = updatedAnime, episodes = updatedEpisodes)
    }

    // ---------------------------------------------
    // Hosters
    // ---------------------------------------------

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val response = client.get("$baseUrl${episode.url}", headers)
        val document = response.asJsoup()
        val scriptContent = document.selectFirst("script:containsData(var video = [)")?.data()
            ?: return emptyList()

        return VIDEO_PATTERN.findAll(scriptContent).mapNotNull { match ->
            runCatching {
                val url = match.groupValues[1]
                val serverName = extractServerName(url)
                Hoster(url = url, hosterName = serverName, internalData = url)
            }.getOrNull()
        }.toList()
    }

    private fun extractServerName(url: String): String = when {
        url.contains("streamtape") -> "StreamTape"
        url.contains("ok.ru") -> "Okru"
        url.contains("yourupload") -> "YourUpload"
        url.contains("streamwish") || url.contains("playerwish") -> "StreamWish"
        url.contains("streamhidevid") -> "StreamHideVid"
        url.contains("voe") -> "Voe"
        url.contains("uqload") -> "Uqload"
        url.contains("mp4upload") -> "Mp4upload"
        else -> "Other"
    }

    // ---------------------------------------------
    // Videos
    // ---------------------------------------------

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val url = hoster.internalData as? String ?: hoster.url
        return buildList {
            runCatching {
                val videos = when {
                    url.contains("streamtape") -> listOfNotNull(streamTapeExtractor.videoFromUrl(url))
                    url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
                    url.contains("yourupload") -> listOfNotNull(yourUploadExtractor.videoFromUrl(url, headers))
                    url.contains("streamwish") || url.contains("playerwish") -> streamWishExtractor.videosFromUrl(url)
                    url.contains("streamhidevid") -> vidHideExtractor.videosFromUrl(url)
                    url.contains("voe") -> voeExtractor.videosFromUrl(url)
                    url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
                    url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
                    else -> universalExtractor.videosFromUrl(url, headers)
                }
                addAll(videos)
            }
        }
    }

    // ---------------------------------------------
    // Sorting
    // ---------------------------------------------

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(compareBy { !it.hosterName.contains(server, ignoreCase = true) })
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { !it.videoTitle.contains(server, ignoreCase = true) },
                { !it.videoTitle.contains(quality) },
                { QUALITY_PATTERN.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        )
    }

    // ---------------------------------------------
    // Preferences
    // ---------------------------------------------

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
