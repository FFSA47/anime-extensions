package eu.kanade.tachiyomi.animeextension.es.animejl

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance
import java.util.Calendar

object AnimejlFilters {

    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(
            displayName,
            vals.map { it.first }.toTypedArray(),
        ) {
        fun toQueryPart(name: String) = vals[state].second
            .takeIf { it.isNotEmpty() }
            ?.let { "&$name=$it" }
            .orEmpty()
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String = (firstInstance<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.firstOrNull { it.first == checkbox.name }?.second
            } else {
                null
            }
        }
        .joinToString("&$name[]=")
        .let { if (it.isBlank()) "" else "&$name[]=$it" }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String =
        (firstInstance<R>() as QueryPartFilter).toQueryPart(name)

    private fun String.changePrefix() = if (startsWith("&")) replaceFirst("&", "?") else this

    data class FilterSearchParams(val filter: String = "") {
        fun getQuery() = filter.changePrefix()
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(FiltersData.GENRES, "genre") +
                filters.parseCheckbox<YearsFilter>(FiltersData.YEARS, "year") +
                filters.parseCheckbox<TypesFilter>(FiltersData.TYPES, "type") +
                filters.parseCheckbox<StateFilter>(FiltersData.STATE, "estado") +
                filters.asQueryPart<SortFilter>("order"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        YearsFilter(),
        TypesFilter(),
        StateFilter(),
        SortFilter(),
    )

    class GenresFilter : CheckBoxFilterList(
        "Género",
        FiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    class YearsFilter : CheckBoxFilterList(
        "Año",
        FiltersData.YEARS.map { CheckBoxVal(it.first, false) },
    )

    class TypesFilter : CheckBoxFilterList(
        "Tipo",
        FiltersData.TYPES.map { CheckBoxVal(it.first, false) },
    )

    class StateFilter : CheckBoxFilterList(
        "Estado",
        FiltersData.STATE.map { CheckBoxVal(it.first, false) },
    )

    class SortFilter : QueryPartFilter("Orden", FiltersData.SORT)

    private object FiltersData {
        val YEARS = (1990..Calendar.getInstance().get(Calendar.YEAR))
            .map { "$it" to "$it" }
            .reversed()
            .toTypedArray()

        val TYPES = arrayOf(
            "Anime" to "1",
            "Ova" to "2",
            "Pelicula" to "3",
            "Donghua" to "7",
        )

        val STATE = arrayOf(
            "En emisión" to "0",
            "Finalizado" to "1",
            "Próximamente" to "2",
        )

        val SORT = arrayOf(
            "Por Defecto" to "created",
            "Recientemente Actualizados" to "updated",
            "Nombre A-Z" to "titleaz",
            "Nombre Z-A" to "titleza",
            "Calificación" to "rating",
            "Vistas" to "views",
        )

        val GENRES = arrayOf(
            "Acción" to "1",
            "Artes Marciales" to "2",
            "Aventuras" to "3",
            "Ciencia Ficción" to "33",
            "Comedia" to "9",
            "Cultivación" to "71",
            "Demencia" to "40",
            "Demonios" to "42",
            "Deportes" to "27",
            "Donghua" to "50",
            "Drama" to "10",
            "Ecchi" to "25",
            "Escolares" to "22",
            "Espacial" to "48",
            "Fantasia" to "6",
            "Gore" to "67",
            "Harem" to "32",
            "Hentai" to "31",
            "Historico" to "43",
            "Horror" to "39",
            "Isekai" to "45",
            "Josei" to "70",
            "Juegos" to "11",
            "Latino / Castellano" to "46",
            "Magia" to "38",
            "Mecha" to "41",
            "Militar" to "44",
            "Misterio" to "26",
            "Mitología" to "73",
            "Musica" to "28",
            "Parodia" to "13",
            "Policía" to "51",
            "Psicologico" to "29",
            "Recuentos de la vida" to "23",
            "Reencarnación" to "72",
            "Romance" to "12",
            "Samurai" to "69",
            "Seinen" to "24",
            "Shoujo" to "36",
            "Shounen" to "4",
            "Sin Censura" to "68",
            "Sobrenatural" to "7",
            "Superpoderes" to "5",
            "Suspenso" to "21",
            "Terror" to "20",
            "Vampiros" to "49",
            "Venganza" to "74",
            "Yaoi" to "53",
            "Yuri" to "52",
        )
    }
}
