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
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.AniSource
import keiyoushi.source.SAnimeUpdate
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Animejl :
    AniSource(),
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

        private val IFRAME_PATTERN = Regex("""video\[\d+] = '<iframe src="(.*?)"""")
        private val ANIME_INFO_PATTERN = Regex("var anime_info = \\[(.*?)];")
        private val EPISODES_ARRAY_PATTERN = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
        private val EPISODE_ENTRY_PATTERN = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
        private val RESOLUTION_PATTERN = Regex("""(\d+)p""")
    }

    /* ---------------------------------- Popular ---------------------------------- */

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.get("$baseUrl/animes?order=rating&page=$page", headers)
        return response.parseAnimePage()
    }

    /* ---------------------------------- Latest ----------------------------------- */

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.get("$baseUrl/animes?order=updated&page=$page", headers)
        return response.parseAnimePage()
    }

    /* ---------------------------------- Search ----------------------------------- */

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val params = Filters.getSearchParameters(filters)
        val response = when {
            query.isNotBlank() -> client.get(
                "$baseUrl/animes".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString())
                    .build(),
                headers,
            )
            params.filter.isNotBlank() -> client.get("$baseUrl/animes${params.getQuery()}&page=$page", headers)
            else -> client.get("$baseUrl/animes?order=rating&page=$page", headers)
        }
        return response.parseAnimePage()
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    /* ---------------------------------- Details ---------------------------------- */

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime, episodes)

        val document = client.get("$baseUrl${anime.url}", headers).asJsoup()

        return SAnimeUpdate(
            anime = if (fetchDetails) anime.updateFrom(document) else anime,
            episodes = if (fetchEpisodes) document.toEpisodeList() else episodes,
        )
    }

    private fun SAnime.updateFrom(document: Document): SAnime {
        title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")?.attr("abs:src")
        description = document.selectFirst("div.Description")?.text()?.removeSurrounding("\"")
        genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        status = parseStatus(document.select("span.fa-tv").text())
        return this
    }

    private fun Document.toEpisodeList(): List<SEpisode> {
        val script = selectFirst("script:containsData(var episodes =)")?.data() ?: return emptyList()
        val episodesMatch = EPISODES_ARRAY_PATTERN.find(script) ?: return emptyList()
        val animeInfoMatch = ANIME_INFO_PATTERN.find(script) ?: return emptyList()
        val animeInfo = animeInfoMatch.groupValues[1].split(",").map { it.trim('"') }
        val animeId = animeInfo.getOrNull(0) ?: return emptyList()
        val animeSlug = animeInfo.getOrNull(2) ?: return emptyList()

        return EPISODE_ENTRY_PATTERN.findAll(episodesMatch.groupValues[1])
            .mapNotNull { match ->
                val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain("$baseUrl/anime/$animeId/$animeSlug/episodio-$number")
                    episode_number = number.toFloat()
                    name = "Episodio $number"
                }
            }
            .sortedByDescending { it.episode_number }
            .toList()
    }

    private fun parseStatus(statusString: String): Int {
        val status = statusString.lowercase()
        return when {
            status.contains("en emision") -> SAnime.ONGOING
            status.contains("finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    /* ------------------------------- Hosters / Videos ----------------------------- */

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get("$baseUrl${episode.url}", headers).asJsoup()
        val scriptContent = document.selectFirst("script:containsData(var video = [)")?.data() ?: return emptyList()

        return IFRAME_PATTERN.findAll(scriptContent)
            .map { it.groupValues[1] }
            .map { url -> Hoster(url, serverName(url), internalData = url) }
            .toList()
    }

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val url = hoster.internalData as? String ?: return emptyList()
        return extractVideos(url)
    }

    private suspend fun extractVideos(url: String): List<Video> = when {
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

    private fun serverName(url: String): String = when {
        url.contains("streamtape") -> "StreamTape"
        url.contains("ok.ru") -> "Okru"
        url.contains("yourupload") -> "YourUpload"
        url.contains("streamwish") || url.contains("playerwish") -> "StreamWish"
        url.contains("streamhidevid") -> "StreamHideVid"
        url.contains("voe") -> "Voe"
        url.contains("uqload") -> "Uqload"
        url.contains("mp4upload") -> "Mp4upload"
        else -> "Universal"
    }

    /* ---------------------------------- Utils ------------------------------------ */

    private fun Response.parseAnimePage(): AnimesPage {
        val document = asJsoup()
        val animes = document.select("div.Container ul.ListAnimes li article").mapNotNull { it.toSAnimeOrNull() }
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=\"next\"]") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun Element.toSAnimeOrNull(): SAnime? {
        val url = select("div.Description a.Button").attr("abs:href")
        val title = select("a h3").text()
        if (url.isBlank() || title.isBlank()) return null

        return SAnime.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = select("a div.Image figure img").attr("src").replace("/storage", "$baseUrl/storage")
            description = select("div.Description p:eq(2)").text().removeSurrounding("\"")
        }
    }

    /* ------------------------------- Preferences --------------------------------- */

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { RESOLUTION_PATTERN.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
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
