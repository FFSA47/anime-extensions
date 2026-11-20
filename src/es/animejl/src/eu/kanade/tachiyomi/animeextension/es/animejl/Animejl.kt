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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

@Source
@OptIn(keiyoushi.annotation.AnimiruOnly::class)
abstract class Animejl : AnimeHttpSource(), ConfigurableAnimeSource {

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
    }

    /*--------------------------------Popular------------------------------------*/
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/animes?order=rating&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = animesPageParse(response)

    /*--------------------------------Latest-------------------------------------*/
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/animes?order=updated&page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = animesPageParse(response)

    /*--------------------------------Search-------------------------------------*/
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?q=$query&page=$page")
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = animesPageParse(response)

    /*--------------------------------Helpers------------------------------------*/
    private fun animesPageParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.Container ul.ListAnimes li article")
            .map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=\"next\"]") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.Description a.Button").attr("abs:href"),
        )
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = element.select("a div.Image figure img")
            .attr("src")
            .replace("/storage", "$baseUrl/storage")
        anime.description = element.select("div.Description p:eq(2)")
            .text()
            .removeSurrounding("\"")
        return anime
    }

    /*--------------------------------Details------------------------------------*/
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")!!
            .attr("abs:src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        anime.description = document.selectFirst("div.Description")!!
            .text()
            .removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("En emision") -> SAnime.ONGOING
        statusString.contains("Finalizado") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    /*--------------------------------Seasons (lib16)----------------------------*/
    // AnimeJL no separa por temporadas.
    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    /*--------------------------------Episodes-----------------------------------*/
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val script = document.select("script:containsData(var episodes =)")
            .firstOrNull()?.data() ?: return emptyList()
        val episodesPattern = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
        val episodesMatch = episodesPattern.find(script) ?: return emptyList()
        val episodesString = episodesMatch.groupValues[1]
        val animeInfoPattern = Regex("var anime_info = \\[(.*?)];")
        val animeInfoMatch = animeInfoPattern.find(script) ?: return emptyList()
        val animeInfo = animeInfoMatch.groupValues[1].split(",").map { it.trim('"') }
        val animeSlug = animeInfo.getOrNull(2) ?: ""
        val animeId = animeInfo.getOrNull(0) ?: ""
        val episodePattern = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
        episodePattern.findAll(episodesString).forEach { match ->
            val episodeNumber = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain("$baseUrl/anime/$animeId/$animeSlug/episodio-$episodeNumber")
            episode.episode_number = episodeNumber.toFloat()
            episode.name = "Episodio $episodeNumber"
            episodeList.add(episode)
        }
        return episodeList.sortedByDescending { it.episode_number }
    }

    /*--------------------------------Hosters (lib16)----------------------------*/
    // ✅ Hoster con hosterUrl válida para que el host realice la petición de video.
    override fun hosterListParse(response: Response): List<Hoster> {
        val episodeUrl = response.request.url.toString()
        return listOf(
            Hoster(
                hosterUrl = episodeUrl,
                hosterName = "AnimeJL",
                lazy = true,
            ),
        )
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    // ✅ Firma de lib16: recibe el Hoster seleccionado
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val document = response.asJsoup()
        val scriptContent = document.selectFirst("script:containsData(var video = [)")?.data()
            ?: return emptyList()
        val videoPattern = Regex("""video\[\d+] = '([^']+)'""")
        return videoPattern.findAll(scriptContent).toList().catchingFlatMapBlocking { match ->
            val rawValue = match.groupValues[1]

            // El sitio envuelve cada video en <iframe src="…">; extraer la URL real
            val url = if (rawValue.trimStart().startsWith("<iframe", ignoreCase = true)) {
                Regex("""src=["']([^"']+)["']""").find(rawValue)?.groupValues?.get(1)
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

    /*--------------------------------Filters------------------------------------*/
    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    /*--------------------------------Sorting------------------------------------*/
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    /*--------------------------------Preferences------------------------------------*/
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
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

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
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
    }
}
