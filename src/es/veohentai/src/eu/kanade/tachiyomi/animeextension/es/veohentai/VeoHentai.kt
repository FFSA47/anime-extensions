package eu.kanade.tachiyomi.animeextension.es.veohentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class VeoHentai : AnimeHttpSource() {

    override val name = "VeoHentai"
    override val baseUrl = "https://veohentai.com"
    override val lang = "es"
    override val supportsLatest = true

    // ---------------- Red & headers compartidos ----------------
    private val playerBase = "https://hentaiplayer.com"

    private val cdnHeaders: Headers = Headers.Builder()
        .add("Referer", "$playerBase/")
        .add("Origin", playerBase)
        .build()

    private val playerHeaders: Headers = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    // ---------------- Regex cacheadas ----------------
    private val episodeSuffixRegex = Regex("-(episodio|episode)-\\d+.*$", RegexOption.IGNORE_CASE)
    private val episodeTitleRegex = Regex("\\s+(Episodio|Episode)\\s+\\d+.*$", RegexOption.IGNORE_CASE)
    private val episodeNumberRegex = Regex("(?:Episodio|Episode)\\s+(\\d+)", RegexOption.IGNORE_CASE)

    // ---------------- Populares ----------------
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/explorar/?orden=vistas${if (page > 1) "&page=$page" else ""}", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseGrid(response)

    // ---------------- Recientes ----------------
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/explorar/${if (page > 1) "?page=$page" else ""}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseGrid(response)

    // ---------------- Búsqueda + filtros ----------------
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val base = if (query.isBlank()) "explorar" else "buscar"
        val params = mutableListOf<String>()

        if (query.isNotBlank()) params += "q=" + URLEncoder.encode(query, "UTF-8")

        filters.firstInstanceOrNull<SortFilter>()?.let { f ->
            SORT_SLUGS[f.values[f.state]]?.let { params += "orden=$it" }
        }
        filters.firstInstanceOrNull<YearFilter>()?.let { f ->
            val year = f.values[f.state]
            if (year != "Cualquiera") params += "anio=$year"
        }
        filters.firstInstanceOrNull<StudioFilter>()?.let { f ->
            val raw = f.state.trim()
            if (raw.isNotEmpty()) {
                val slug = raw.lowercase().replace(Regex("\\s+"), "-")
                params += "estudio=" + URLEncoder.encode(slug, "UTF-8")
            }
        }
        filters.firstInstanceOrNull<GenreFilter>()?.let { f ->
            if (f.state > 0) {
                GENRE_SLUGS[f.values[f.state]]?.let { params += "generos=$it" }
            }
        }
        if (page > 1) params += "page=$page"

        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return GET("$baseUrl/$base/$qs", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseGrid(response)

    // ---------------- Grid compartido ----------------
    private fun parseGrid(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a.group.block[href^=/ver/]")
            .ifEmpty { doc.select("div.grid > a[href^=/ver/]") }

        val animes = mutableListOf<SAnime>()
        val seen = mutableSetOf<String>()
        for (el in items) {
            val anime = parseListAnime(el) ?: continue
            if (seen.add(seriesKey(anime.url))) animes += anime
        }
        return AnimesPage(animes, items.size >= 20)
    }

    private fun parseListAnime(el: Element): SAnime? {
        val href = el.absUrl("href").ifBlank { el.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val img = el.selectFirst("img") ?: return null
        val h3 = el.selectFirst("h3")?.text() ?: return null

        val seriesTitle = h3.replace(episodeTitleRegex, "")

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = seriesTitle.ifBlank { h3 }
            thumbnail_url = img.absUrl("src").ifBlank { img.attr("src") }
        }
    }

    private fun seriesKey(href: String): String {
        val full = if (href.startsWith("http")) href else baseUrl + href
        val segments = full.toHttpUrlOrNull()?.pathSegments ?: return href
        val slug = segments.getOrNull(1) ?: return href
        return slug.replace(episodeSuffixRegex, "")
    }

    // ---------------- Detalles ----------------
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val crumb = doc.selectFirst("nav[aria-label='Migas de pan'] ol li:nth-child(2) a")
            val h1 = doc.selectFirst("h1")?.text()
            title = crumb?.text()
                ?: h1?.replace(episodeTitleRegex, "")
                ?: throw Exception("Missing anime title")
            thumbnail_url = doc.selectFirst("section.card img")
                ?.let { it.absUrl("src").ifBlank { it.attr("src") } }
            description = doc.selectFirst("div.prose-vh")?.text()
            genre = doc.select("section.card + div.flex.flex-wrap.gap-2 > a[href^=/genero/]")
                .map { it.text() }
                .filter { it.isNotEmpty() }
                .joinToString()
            status = SAnime.UNKNOWN
        }
    }

    // ---------------- Episodios ----------------
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val ol = doc.select("ol").firstOrNull { it.select("li > a[href^=/ver/]").isNotEmpty() }
            ?: return emptyList()

        return ol.select("li > a").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = a.selectFirst("p")?.text() ?: ""
            val num = episodeNumberRegex.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = title.ifEmpty { "Episodio ${num.toInt().takeIf { it > 0 } ?: 0}" }
                episode_number = num
            }
        }.sortedByDescending { it.episode_number }
    }

    // ---------------- Seasons (no soportado) ----------------
    override fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Seasons not supported")

    // ---------------- Hosters (lib 16 — API request/parse) ----------------
    override fun hosterListParse(response: Response): List<Hoster> {
        val episodeDoc = response.asJsoup()
        val iframeUrl = episodeDoc.selectFirst("div.episode-player iframe")
            ?.let { it.absUrl("src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val playerDoc = client.newCall(GET(iframeUrl, playerHeaders)).execute().use { it.asJsoup() }

        return playerDoc.select(".servers ul li[data-id], li[data-id]").mapNotNull { li ->
            val dataId = li.attr("data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val serverName = li.text().ifEmpty { "Main Player" }
            val vidParam = dataId.parseQueryParam("vid") ?: return@mapNotNull null
            val decoded = decodeBase64Safe(vidParam) ?: return@mapNotNull null

            val parts = decoded.split("|")
            if (parts.size < 3) return@mapNotNull null
            val rawUrl = parts[0]
            val expires = parts[1]
            val md5 = parts[2]
            if (!rawUrl.startsWith("http")) return@mapNotNull null

            val directUrl = "$rawUrl?md5=$md5&expires=$expires"

            val videos = listOf(
                Video(
                    videoUrl = directUrl,
                    videoTitle = "$serverName [MP4]",
                    headers = cdnHeaders,
                ),
            )
            Hoster(
                hosterUrl = "$playerBase$dataId",
                hosterName = serverName,
                videoList = videos,
            )
        }
    }

    // ---------------- Utilidades ----------------
    private fun String.parseQueryParam(key: String): String? {
        val full = if (startsWith("http")) this else "https://x$this"
        return full.toHttpUrlOrNull()?.queryParameter(key)
    }

    private fun decodeBase64Safe(input: String): String? = runCatching {
        runCatching { String(Base64.getDecoder().decode(input)) }
            .getOrElse { String(Base64.getUrlDecoder().decode(input)) }
    }.getOrNull()

    // ---------------- Filtros ----------------
    class SortFilter : AnimeFilter.Select<String>(
        "Ordenar por",
        arrayOf(
            "Recientes",
            "Más antiguos",
            "Más vistos",
            "Menos vistos",
            "Vistos esta semana",
            "Vistos este mes",
            "Vistos este año",
            "Más gustados",
            "Título",
        ),
    )

    class YearFilter : AnimeFilter.Select<String>(
        "Año",
        arrayOf(
            "Cualquiera",
            "2026",
            "2025",
            "2024",
            "2023",
            "2022",
            "2021",
            "2020",
            "2019",
            "2018",
            "2017",
        ),
    )

    class StudioFilter : AnimeFilter.Text("Estudio (slug)")

    class GenreFilter : AnimeFilter.Select<String>(
        "Género",
        arrayOf("Cualquiera") + GENRE_SLUGS.keys.toTypedArray(),
    )

    override fun getFilterList() = AnimeFilterList(
        SortFilter(),
        YearFilter(),
        StudioFilter(),
        GenreFilter(),
    )

    companion object {
        private val SORT_SLUGS = mapOf(
            "Recientes" to null,
            "Más antiguos" to "antiguos",
            "Más vistos" to "vistas",
            "Menos vistos" to "menos-vistas",
            "Vistos esta semana" to "vistas-semana",
            "Vistos este mes" to "vistas-mes",
            "Vistos este año" to "vistas-anio",
            "Más gustados" to "likes",
            "Título" to "za",
        )

        private val GENRE_SLUGS = mapOf(
            "Tetonas" to "tetonas",
            "Escolares" to "hentai-escolares",
            "Vírgenes" to "virgenes",
            "Violación" to "violacion",
            "Romance" to "romance",
            "Anal" to "anal",
            "Harem" to "harem",
            "Corridas" to "corridas",
            "Oral" to "oral",
            "Sin Censura" to "hentai-sin-censura",
            "Censurado" to "censurado",
            "Orgías" to "orgias",
            "MILFs" to "milfs",
            "Ahegao" to "ahegao",
            "Ninfomanía" to "ninfomania",
            "Incesto" to "incesto",
            "Lolicon" to "lolicon",
            "Yuri" to "yuri",
            "Juegos Sexuales" to "juegos-sexuales",
            "Hardcore" to "hardcore",
        )
    }
}
