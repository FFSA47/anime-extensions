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
import keiyoushi.network.get
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

    private val playerBase = "https://hentaiplayer.com"

    // Cabeceras separadas a nivel de clase (Regla: Separate custom headers)
    // Incluye barra final en Referer (Regla: Referer header trailing slash)
    // Sin User-Agent hardcodeado (Regla: Do not hardcode User-Agent)
    private val playerHeaders: Headers = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .add("Sec-Fetch-Dest", "iframe")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "cross-site")
        .build()

    // Regex cacheadas
    private val whitespaceRegex = Regex("\\s+")
    private val trailingQueryRegex = Regex("\\?\\d+$")
    private val episodeSuffixRegex = Regex("-(episodio|episode)-\\d+.*$", RegexOption.IGNORE_CASE)
    private val episodeTitleRegex = Regex("\\s+(Episodio|Episode)\\s+\\d+.*$", RegexOption.IGNORE_CASE)
    private val episodeNumberRegex = Regex("(?:Episodio|Episode)\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val hrefEpisodeRegex = Regex("(?:episodio|episode)-(\\d+)", RegexOption.IGNORE_CASE)
    private val playerCoreTokenRegex = Regex("""player-core-v2\.php\?t=([^"'&\s]+)""")
    private val jsUrlRegex = Regex("""(https?://[^"'\s\\]+\.(?:mp4|m3u8)[^"'\s\\]*)""")
    private val jsJsonUrlRegex = Regex(""""(?:url|file|src|videoUrl|source)"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""")

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
            val y = f.values[f.state]
            if (y != "Cualquiera") params += "anio=$y"
        }
        filters.firstInstanceOrNull<StudioFilter>()?.let { f ->
            val raw = f.state.trim()
            if (raw.isNotEmpty()) {
                val slug = raw.lowercase().replace(whitespaceRegex, "-")
                params += "estudio=" + URLEncoder.encode(slug, "UTF-8")
            }
        }
        filters.firstInstanceOrNull<GenreFilter>()?.let { f ->
            if (f.state > 0) GENRE_SLUGS[f.values[f.state]]?.let { params += "generos=$it" }
        }
        if (page > 1) params += "page=$page"
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return GET("$baseUrl/$base/$qs", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseGrid(response)

    private fun parseGrid(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a.group.block[href^=/ver/]")
            .ifEmpty { doc.select("div.grid > a[href^=/ver/]") }
        val animes = mutableListOf<SAnime>()
        val seen = mutableSetOf<String>()
        for (el in items) {
            val a = parseListAnime(el) ?: continue
            if (seen.add(seriesKey(a.url))) animes += a
        }
        return AnimesPage(animes, items.size >= 20)
    }

    private fun parseListAnime(el: Element): SAnime? {
        val href = el.absUrl("href").ifBlank { el.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val img = el.selectFirst("img") ?: return null
        val h3 = el.selectFirst("h3")?.text() ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = h3.replace(episodeTitleRegex, "").ifBlank { h3 }
            thumbnail_url = img.absUrl("src").ifBlank { img.attr("src") }
        }
    }

    private fun seriesKey(href: String): String {
        val full = if (href.startsWith("http")) href else baseUrl + href
        return full.toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
            ?.replace(episodeSuffixRegex, "") ?: href
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
                .map { it.text() }.filter { it.isNotEmpty() }.joinToString()
            status = SAnime.UNKNOWN
        }
    }

    // ---------------- Episodios ----------------

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val currentSlug = response.request.url.pathSegments.let { segs ->
            if (segs.size >= 2 && segs[0] == "ver") segs[1].replace(episodeSuffixRegex, "") else null
        }
        return doc.select("a[href^=/ver/]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (currentSlug != null) {
                    val candidate = href.trim('/').removePrefix("ver/").replace(episodeSuffixRegex, "")
                    if (candidate != currentSlug) return@mapNotNull null
                }
                val num = hrefEpisodeRegex.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: episodeNumberRegex.find(a.text())?.groupValues?.get(1)?.toFloatOrNull()
                    ?: -1f
                SEpisode.create().apply {
                    setUrlWithoutDomain(a.absUrl("href").ifBlank { href })
                    name = "Episodio ${num.toInt().takeIf { it > 0 } ?: 0}"
                    episode_number = num
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.episode_number }
    }

    override fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Seasons not supported")

    // ---------------- Hosters (parse puro, sin red) ----------------

    override fun hosterListParse(response: Response): List<Hoster> {
        val iframeUrl = response.asJsoup()
            .selectFirst("div.episode-player iframe")
            ?.let { it.absUrl("src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return listOf(
            Hoster(
                hosterUrl = iframeUrl,
                hosterName = "HentaiPlayer",
                internalData = iframeUrl,
            ),
        )
    }

    override fun videoListRequest(hoster: Hoster): Request =
        GET(hoster.internalData ?: hoster.hosterUrl, playerHeaders)

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        return response.asJsoup()
            .select(".servers ul li[data-id], li[data-id]")
            .mapNotNull { li ->
                val dataId = li.attr("data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val serverName = li.text().ifEmpty { "Main Player" }
                val playerPhpUrl = if (dataId.startsWith("http")) dataId else "$playerBase$dataId"
                Video(
                    videoUrl = "",
                    videoTitle = serverName,
                    initialized = false,
                    internalData = "$playerPhpUrl|$dataId|$serverName",
                )
            }
    }

    // ---------------- Resolución diferida ----------------

    override suspend fun resolveVideo(video: Video): Video? {
        val data = video.internalData ?: return null
        val parts = data.split("|", limit = 3)
        val playerPhpUrl = parts.getOrNull(0) ?: return null
        val dataId = parts.getOrNull(1) ?: return null
        val serverName = parts.getOrNull(2) ?: video.videoTitle

        val videoUrl = resolveVideoUrlSuspend(playerPhpUrl, dataId) ?: return null
        val resolvedHeaders = buildHeadersForUrl(videoUrl, playerPhpUrl)

        return Video(
            videoUrl = videoUrl,
            videoTitle = serverName,
            headers = resolvedHeaders,
            initialized = true,
        )
    }

    private suspend fun resolveVideoUrlSuspend(playerPhpUrl: String, dataId: String): String? {
        var cookies = ""
        try {
            client.get(playerPhpUrl, playerHeaders, ensureSuccess = false).use { resp ->
                cookies = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            }
        } catch (_: Exception) {}

        try {
            val reqBuilder = playerHeaders.newBuilder()
            if (cookies.isNotEmpty()) reqBuilder.set("Cookie", cookies)
            
            val playerResp = client.get(playerPhpUrl, reqBuilder.build(), ensureSuccess = false)
            val newCookies = playerResp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            if (newCookies.isNotEmpty()) {
                cookies = newCookies
            }
            
            val playerHtml = playerResp.use { it.body?.string() ?: "" }
            val token = playerCoreTokenRegex.find(playerHtml)?.groupValues?.get(1)
            
            if (token != null) {
                val coreUrl = "$playerBase/player-core-v2.php?t=$token&_=${System.currentTimeMillis() / 1000}"
                val coreReqBuilder = playerHeaders.newBuilder()
                if (cookies.isNotEmpty()) coreReqBuilder.set("Cookie", cookies)
                
                val coreJs = client.get(coreUrl, coreReqBuilder.build(), ensureSuccess = false)
                    .use { it.body?.string() ?: "" }

                val candidates = mutableListOf<String>()
                jsUrlRegex.findAll(coreJs).forEach { m ->
                    candidates.add(m.groupValues[1].replace("\\/", "/"))
                }
                jsJsonUrlRegex.findAll(coreJs).forEach { m ->
                    candidates.add(m.groupValues[1].replace("\\/", "/"))
                }
                for (c in candidates.distinct()) {
                    if (testVideoUrl(c, playerPhpUrl, cookies)) return c
                }
            }
        } catch (_: Exception) {}

        // Fallback al base64 con variantes
        val vidParam = dataId.parseQueryParam("vid") ?: return null
        val decoded = decodeBase64Safe(vidParam) ?: return null
        val parts = decoded.split("|")
        if (parts.size < 3) return null
        val rawUrl = parts[0].trim()
        val expires = parts[1].trim()
        val md5 = parts[2].trim()
        if (!rawUrl.startsWith("http")) return null

        val cleanUrl = rawUrl.replace(trailingQueryRegex, "")
        val candidates = mutableListOf<String>()
        fun add(u: String) {
            val sep = if (u.contains("?")) "&" else "?"
            candidates.add("$u${sep}md5=$md5&expires=$expires")
        }
        add(rawUrl)
        if (cleanUrl != rawUrl) add(cleanUrl)
        if (rawUrl.contains("+")) {
            add(rawUrl.replace("+", "%2B"))
            if (cleanUrl != rawUrl) add(cleanUrl.replace("+", "%2B"))
        }

        for (c in candidates.distinct()) {
            if (testVideoUrl(c, playerPhpUrl, cookies)) return c
        }
        return candidates.firstOrNull()
    }

    // Validación estricta por Content-Type para evitar que páginas HTML (Cloudflare "Verificando...") 
    // sean enviadas a mpv. (Regla: No comprobar Cloudflare manualmente en métodos parse).
    private suspend fun testVideoUrl(url: String, refererUrl: String, cookies: String): Boolean {
        return try {
            val hb = buildHeadersForUrl(url, refererUrl).newBuilder()
                .set("Range", "bytes=0-1")
                .set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            if (cookies.isNotEmpty()) hb.set("Cookie", cookies)
            
            val resp = client.get(url, hb.build(), ensureSuccess = false)
            val code = resp.code
            val contentType = resp.header("Content-Type")?.lowercase() ?: ""
            resp.close() // Cierre explícito y temprano para eficiencia de memoria
            
            val isHtml = contentType.contains("text/html")
            val isValidMedia = contentType.contains("video/") || 
                               contentType.contains("mpegurl") || 
                               contentType.contains("octet-stream") || 
                               contentType.contains("text/plain")
            
            (code in 200..299 || code == 206) && isValidMedia && !isHtml
        } catch (_: Exception) { 
            false 
        }
    }

    private fun buildHeadersForUrl(videoUrl: String, iframeUrl: String): Headers {
        val host = videoUrl.toHttpUrlOrNull()?.host.orEmpty()
        val referer = when {
            host.contains("1hanime", ignoreCase = true) -> "https://1hanime.com/"
            host.contains("hentaiplayer", ignoreCase = true) -> iframeUrl
            else -> "$baseUrl/"
        }
        return headers.newBuilder()
            .add("Referer", referer)
            .add("Origin", referer.trimEnd('/'))
            .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .add("Sec-Fetch-Dest", "video")
            .add("Sec-Fetch-Mode", "no-cors")
            .add("Sec-Fetch-Site", "cross-site")
            .build()
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
            "Recientes", "Más antiguos", "Más vistos", "Menos vistos",
            "Vistos esta semana", "Vistos este mes", "Vistos este año",
            "Más gustados", "Título",
        ),
    )

    class YearFilter : AnimeFilter.Select<String>(
        "Año",
        arrayOf("Cualquiera", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017"),
    )

    class StudioFilter : AnimeFilter.Text("Estudio (slug)")

    class GenreFilter : AnimeFilter.Select<String>(
        "Género",
        arrayOf("Cualquiera") + GENRE_SLUGS.keys.toTypedArray(),
    )

    override fun getFilterList() = AnimeFilterList(
        SortFilter(), YearFilter(), StudioFilter(), GenreFilter(),
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
