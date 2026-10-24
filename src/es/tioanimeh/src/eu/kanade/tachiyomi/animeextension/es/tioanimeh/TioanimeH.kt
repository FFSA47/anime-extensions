package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response

open class TioanimeH(override val name: String, override val baseUrl: String) :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val lang = "es"
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "Voe",
            "VidGuard",
            "Okru",
            "MixDrop",
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/directorio?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("ul.animes.list-unstyled.row li.col-6.col-sm-4.col-md-3.col-xl-2")
        val hasNextPage = document.selectFirst(".pagination .active ~ li:not(.disabled)") != null
        
        val animeList = elements.mapNotNull { element ->
            val animeTitle = element.select("article a h3").text()
            if (animeTitle.isBlank()) return@mapNotNull null
            
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("article a").attr("abs:href"))
                title = animeTitle
                thumbnail_url = element.select("article a div figure img").attr("abs:src")
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val epInfoScript = document.selectFirst("script:containsData(var episodes = )")?.data() ?: return emptyList()
        
        val episodesData = epInfoScript.substringAfter("episodes = [").substringBefore("];")
        if (episodesData.isEmpty()) {
            return emptyList()
        }
        
        val epNumList = episodesData.split(",")
        val epSlug = epInfoScript.substringAfter("anime_info = [").substringBefore("];").replace("\"", "").split(",").getOrNull(1) ?: ""
        
        return epNumList.map {
            SEpisode.create().apply {
                name = "Episodio $it"
                url = "/ver/$epSlug-$it"
                episode_number = it.toFloatOrNull() ?: 0f
            }
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val serverList = document.selectFirst("script:containsData(var videos =)")
            ?.data()?.substringAfter("var videos = [[")?.substringBefore("]];")
            ?.replace("\"", "")?.split("],[") ?: return emptyList()

        return serverList.map {
            val servers = it.split(",")
            val serverName = servers.getOrNull(0) ?: ""
            val serverUrl = servers.getOrNull(1)?.replace("\\/", "/") ?: ""

            val normalizedName = when (serverName.lowercase()) {
                "voe" -> "Voe"
                "vidguard" -> "VidGuard"
                "okru" -> "Okru"
                "yourupload" -> "YourUpload"
                "mixdrop" -> "MixDrop"
                else -> serverName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            Hoster(
                hosterUrl = serverUrl,
                hosterName = normalizedName,
                internalData = serverName.lowercase(),
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(server, ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverName = hoster.internalData
        val serverUrl = hoster.hosterUrl

        val videos = when (serverName) {
            "voe" -> voeExtractor.videosFromUrl(serverUrl)
            "vidguard" -> vidGuardExtractor.videosFromUrl(serverUrl)
            "okru" -> okruExtractor.videosFromUrl(serverUrl)
            "yourupload" -> yourUploadExtractor.videoFromUrl(serverUrl, headers = headers)
            "mixdrop" -> mixDropExtractor.videosFromUrl(serverUrl)
            else -> universalExtractor.videosFromUrl(serverUrl, headers)
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TioAnimeHFilters.getSearchParameters(filters, TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.HENTAI)
        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/directorio${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.title").text()
            description = document.selectFirst("p.sinopsis")?.ownText() ?: ""
            genre = document.select("p.genres span.btn.btn-sm.btn-primary.rounded-pill a").joinToString { it.text() }
            thumbnail_url = document.select(".thumb img").attr("abs:src")
            status = parseStatus(document.select("a.btn.btn-success.btn-block.status").text())
        }
    }

    private fun parseStatus(statusString: String): Int {
        val lowerStatus = statusString.lowercase()
        return when {
            lowerStatus.contains("en emision") -> SAnime.ONGOING
            lowerStatus.contains("finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".episodes li")
        val hasNextPage = document.selectFirst(".pagination .active ~ li:not(.disabled)") != null
        
        val animeList = elements.mapNotNull { element ->
            val animeTitle = element.select("article a h3").text()
            if (animeTitle.isBlank()) return@mapNotNull null

            val href = element.select("article a").attr("abs:href")
            val slug = if (baseUrl.contains("hentai")) "/hentai/" else "/anime/"
            
            val realUrl = href.substringBeforeLast("-").replace("/ver/", slug)
            
            SAnime.create().apply {
                title = animeTitle
                thumbnail_url = element.select("article a div figure img").attr("abs:src")
                setUrlWithoutDomain(realUrl)
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = TioAnimeHFilters.getFilterList(TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.HENTAI)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }
}
