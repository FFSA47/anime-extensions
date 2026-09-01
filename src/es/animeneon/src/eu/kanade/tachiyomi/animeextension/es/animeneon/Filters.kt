package eu.kanade.tachiyomi.animeextension.es.animeneon

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

/**
 * Filtros para AnimeNeon.
 */
object Filters {

    // ============================================
    // 1. GÉNEROS (selección múltiple)
    // ============================================
    open class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name)
    class GenreFilter :
        AnimeFilter.Group<GenreCheckBox>(
            "Géneros",
            getGenreList(),
        )

    // ============================================
    // 2. TEMAS (selección múltiple)
    // ============================================
    open class ThemeCheckBox(name: String) : AnimeFilter.CheckBox(name)
    class ThemeFilter :
        AnimeFilter.Group<ThemeCheckBox>(
            "Temas",
            getThemeList(),
        )

    // ============================================
    // 3. DEMOGRAFÍA (selección múltiple)
    // ============================================
    open class DemographicCheckBox(name: String) : AnimeFilter.CheckBox(name)
    class DemographicFilter :
        AnimeFilter.Group<DemographicCheckBox>(
            "Demografía",
            getDemographicList(),
        )

    // ============================================
    // 4. AÑO (selección única)
    // ============================================
    class YearFilter :
        AnimeFilter.Select(
            "Año",
            getYearOptions(),
        )

    // ============================================
    // 5. TEMPORADA (selección única)
    // ============================================
    class SeasonFilter :
        AnimeFilter.Select(
            "Temporada",
            getSeasonOptions(),
        )

    // ============================================
    // 6. FORMATO (selección única)
    // ============================================
    class FormatFilter :
        AnimeFilter.Select(
            "Formato",
            getFormatOptions(),
        )

    // ============================================
    // 7. ESTADO (selección única)
    // ============================================
    class StatusFilter :
        AnimeFilter.Select(
            "Estado",
            getStatusOptions(),
        )

    // ============================================
    // 8. IDIOMA (selección única)
    // ============================================
    class LanguageFilter :
        AnimeFilter.Select(
            "Idioma",
            getLanguageOptions(),
        )

    // ============================================
    // 9. ORDEN (selección única)
    // ============================================
    class OrderFilter :
        AnimeFilter.Select(
            "Ordenar por",
            getOrderOptions(),
        )

    // ============================================
    // LISTAS DE OPCIONES
    // ============================================

    private fun getGenreList(): List<GenreCheckBox> = listOf(
        GenreCheckBox("Accion"),
        GenreCheckBox("Aventura"),
        GenreCheckBox("Avant Garde"),
        GenreCheckBox("Award Winning"),
        GenreCheckBox("Boys Love"),
        GenreCheckBox("Ciencia Ficcion"),
        GenreCheckBox("Comedia"),
        GenreCheckBox("Deporte"),
        GenreCheckBox("Drama"),
        GenreCheckBox("Ecchi"),
        GenreCheckBox("Erotica"),
        GenreCheckBox("Fantasia"),
        GenreCheckBox("Girls Love"),
        GenreCheckBox("Gourmet"),
        GenreCheckBox("Historico"),
        GenreCheckBox("Mecha"),
        GenreCheckBox("Misterio"),
        GenreCheckBox("Recuentos de la vida"),
        GenreCheckBox("Romance"),
        GenreCheckBox("Shounen"),
        GenreCheckBox("Shoujo"),
        GenreCheckBox("Seinen"),
        GenreCheckBox("Josei"),
        GenreCheckBox("Sobrenatural"),
        GenreCheckBox("Suspenso"),
        GenreCheckBox("Terror"),
    )

    private fun getThemeList(): List<ThemeCheckBox> = listOf(
        ThemeCheckBox("Adult Cast"),
        ThemeCheckBox("Anthropomorphic"),
        ThemeCheckBox("Arte Marciales"),
        ThemeCheckBox("Carreras"),
        ThemeCheckBox("CGDCT"),
        ThemeCheckBox("Childcare"),
        ThemeCheckBox("Combat Sports"),
        ThemeCheckBox("Crossdressing"),
        ThemeCheckBox("Delinquents"),
        ThemeCheckBox("Detective"),
        ThemeCheckBox("Educational"),
        ThemeCheckBox("Escuela"),
        ThemeCheckBox("Espacial"),
        ThemeCheckBox("Gag Humor"),
        ThemeCheckBox("Gore"),
        ThemeCheckBox("Harem"),
        ThemeCheckBox("High Stakes Game"),
        ThemeCheckBox("Idols (Female)"),
        ThemeCheckBox("Idols (Male)"),
        ThemeCheckBox("Isekai"),
        ThemeCheckBox("Iyashikei"),
        ThemeCheckBox("Juego de Estrategia"),
        ThemeCheckBox("Love Polygon"),
        ThemeCheckBox("Love Status Quo"),
        ThemeCheckBox("Magical Sex Shift"),
        ThemeCheckBox("Mahou Shoujo"),
        ThemeCheckBox("Medical"),
        ThemeCheckBox("Militar"),
        ThemeCheckBox("Mitologia"),
        ThemeCheckBox("Musica"),
        ThemeCheckBox("Organized Crime"),
        ThemeCheckBox("Otaku Culture"),
        ThemeCheckBox("Parodia"),
        ThemeCheckBox("Performing Arts"),
        ThemeCheckBox("Pets"),
        ThemeCheckBox("Psicologico"),
        ThemeCheckBox("Reincarnation"),
        ThemeCheckBox("Reverse Harem"),
        ThemeCheckBox("Samurai"),
        ThemeCheckBox("Showbiz"),
        ThemeCheckBox("Super Poderes"),
        ThemeCheckBox("Survival"),
        ThemeCheckBox("Team Sports"),
        ThemeCheckBox("Time Travel"),
        ThemeCheckBox("Urban Fantasy"),
        ThemeCheckBox("Vampiros"),
        ThemeCheckBox("Video Game"),
        ThemeCheckBox("Villainess"),
        ThemeCheckBox("Visual Arts"),
        ThemeCheckBox("Workplace"),
    )

    private fun getDemographicList(): List<DemographicCheckBox> = listOf(
        DemographicCheckBox("Infantil"),
        DemographicCheckBox("Josei"),
        DemographicCheckBox("Seinen"),
        DemographicCheckBox("Shoujo"),
        DemographicCheckBox("Shounen"),
    )

    private fun getYearOptions(): Array<String> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (1950..currentYear).map { it.toString() }.reversed().toTypedArray()
    }

    private fun getSeasonOptions(): Array<String> = arrayOf(
        "Todos",
        "Invierno",
        "Primavera",
        "Verano",
        "Otoño",
    )

    private fun getFormatOptions(): Array<String> = arrayOf(
        "Todos",
        "TV",
        "OVA",
        "Película",
        "Especial",
        "ONA",
    )

    private fun getStatusOptions(): Array<String> = arrayOf(
        "Todos",
        "En emisión",
        "Finalizado",
        "Próximamente",
    )

    private fun getLanguageOptions(): Array<String> = arrayOf(
        "Todos",
        "Latino",
        "Subtitulado",
        "Castellano",
    )

    private fun getOrderOptions(): Array<String> = arrayOf(
        "Reciente agregado",
        "Popularidad",
        "Puntuación",
        "A-Z",
        "Z-A",
    )
}

/**
 * Construye los parámetros de filtro para la URL.
 */
fun AnimeFilterList.buildFilterParams(): String {
    val params = mutableListOf<String>()

    // Géneros
    val genreFilter = this.filterIsInstance<Filters.GenreFilter>().firstOrNull()
    val selectedGenres = genreFilter?.state?.filter { it.state }?.map { it.name } ?: emptyList()
    if (selectedGenres.isNotEmpty()) {
        params.add("genre=${selectedGenres.joinToString(",")}")
    }

    // Temas
    val themeFilter = this.filterIsInstance<Filters.ThemeFilter>().firstOrNull()
    val selectedThemes = themeFilter?.state?.filter { it.state }?.map { it.name } ?: emptyList()
    if (selectedThemes.isNotEmpty()) {
        params.add("theme=${selectedThemes.joinToString(",")}")
    }

    // Demografía
    val demographicFilter = this.filterIsInstance<Filters.DemographicFilter>().firstOrNull()
    val selectedDemographics = demographicFilter?.state?.filter { it.state }?.map { it.name } ?: emptyList()
    if (selectedDemographics.isNotEmpty()) {
        params.add("demographic=${selectedDemographics.joinToString(",")}")
    }

    // Año
    val yearFilter = this.filterIsInstance<Filters.YearFilter>().firstOrNull()
    val year = yearFilter?.selected?.takeIf { it != 0 }?.let { getYearOptions().getOrNull(it) }
    if (year != null && year != "Todos") {
        params.add("year=$year")
    }

    // Temporada
    val seasonFilter = this.filterIsInstance<Filters.SeasonFilter>().firstOrNull()
    val season = seasonFilter?.selected?.takeIf { it != 0 }?.let { getSeasonOptions().getOrNull(it) }
    if (season != null && season != "Todos") {
        params.add("season=$season")
    }

    // Formato
    val formatFilter = this.filterIsInstance<Filters.FormatFilter>().firstOrNull()
    val format = formatFilter?.selected?.takeIf { it != 0 }?.let { getFormatOptions().getOrNull(it) }
    if (format != null && format != "Todos") {
        params.add("format=$format")
    }

    // Estado
    val statusFilter = this.filterIsInstance<Filters.StatusFilter>().firstOrNull()
    val status = statusFilter?.selected?.takeIf { it != 0 }?.let { getStatusOptions().getOrNull(it) }
    if (status != null && status != "Todos") {
        params.add("status=$status")
    }

    // Idioma
    val languageFilter = this.filterIsInstance<Filters.LanguageFilter>().firstOrNull()
    val language = languageFilter?.selected?.takeIf { it != 0 }?.let { getLanguageOptions().getOrNull(it) }
    if (language != null && language != "Todos") {
        params.add("lang=$language")
    }

    // Orden
    val orderFilter = this.filterIsInstance<Filters.OrderFilter>().firstOrNull()
    val order = orderFilter?.selected?.takeIf { it != 0 }?.let { getOrderOptions().getOrNull(it) }
    if (order != null) {
        val orderMap = mapOf(
            "Reciente agregado" to "recent",
            "Popularidad" to "popular",
            "Puntuación" to "score",
            "A-Z" to "title",
            "Z-A" to "title_desc",
        )
        orderMap[order]?.let { params.add("order=$it") }
    }

    return if (params.isNotEmpty()) "&${params.joinToString("&")}" else ""
}

// Funciones auxiliares para obtener las opciones (necesarias para buildFilterParams)
private fun getYearOptions(): Array<String> = Filters.YearFilter().values
private fun getSeasonOptions(): Array<String> = Filters.SeasonFilter().values
private fun getFormatOptions(): Array<String> = Filters.FormatFilter().values
private fun getStatusOptions(): Array<String> = Filters.StatusFilter().values
private fun getLanguageOptions(): Array<String> = Filters.LanguageFilter().values
private fun getOrderOptions(): Array<String> = Filters.OrderFilter().values
