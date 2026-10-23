package eu.kanade.tachiyomi.animeextension.es.animeid

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    val FILTER_LIST = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        OrderFilter(),
    )

    class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("Todos", ""), Pair("Acción", "accion"), Pair("Aventura", "aventura"),
            Pair("Comedia", "comedia"), Pair("Drama", "drama"), Pair("Fantasía", "fantasia"),
            Pair("Romance", "romance"), Pair("Sci-Fi", "ciencia-ficcion"), Pair("Seinen", "seinen"),
            Pair("Shounen", "shounen"), Pair("Suspenso", "suspenso"), Pair("Terror", "terror"),
        )
    )

    class YearFilter : UriPartFilter(
        "Año",
        arrayOf(
            Pair("Todos", ""),
            Pair("2026", "2026"), Pair("2025", "2025"), Pair("2024", "2024"),
            Pair("2023", "2023"), Pair("2022", "2022"), Pair("2021", "2021"),
            Pair("2020", "2020"),
        )
    )

    class StatusFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("Todos", ""),
            Pair("En emisión", "en-emision"),
            Pair("Finalizado", "finalizado"),
        )
    )

    class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Recientes", "newest"),
            Pair("Popularidad", "views"),
            Pair("A-Z", "asc"),
        )
    )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = if (state > 0) vals[state].second else ""
    }
}
