package eu.kanade.tachiyomi.animeextension.es.monoschinos

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object Filters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(
            displayName,
            vals.map { it.first }.toTypedArray(),
        ) {
        fun toQueryPart(name: String) =
            vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=$it" } ?: ""
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    /**
     * Serializa un grupo de checkboxes como claves repetidas:
     *   &genero[]=accion&genero[]=aventura
     */
    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }!!.second
            } else {
                null
            }
        }.joinToString("") { "&$name=$it" }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String =
        (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R =
        this.filterIsInstance<R>().first()

    private fun String.changePrefix() =
        this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: ""

    data class FilterSearchParams(val filter: String = "") {
        fun getQuery() = filter.changePrefix()
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(FiltersData.GENRES, "genero[]") +
                filters.asQueryPart<CategoryFilter>("categoria") +
                filters.asQueryPart<YearFilter>("fecha") +
                filters.asQueryPart<LetterFilter>("letra"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        GenresFilter(),
        CategoryFilter(),
        YearFilter(),
        LetterFilter(),
    )

    class GenresFilter : CheckBoxFilterList("Género", FiltersData.GENRES.map { CheckBoxVal(it.first) })

    class CategoryFilter : QueryPartFilter("Categoría", FiltersData.CATEGORIES)

    class YearFilter : QueryPartFilter("Año", FiltersData.YEARS)

    class LetterFilter : QueryPartFilter("Letra", FiltersData.LETTERS)

    private object FiltersData {
        // Valores tomados del HTML: name="categoria"
        val CATEGORIES = arrayOf(
            Pair("Todos", ""),
            Pair("Anime", "anime"),
            Pair("Ova", "ova"),
            Pair("Película", "pelicula"),
            Pair("Especial", "especial"),
            Pair("Corto", "corto"),
            Pair("Ona", "ona"),
            Pair("Donghua", "donghua"),
            Pair("Sin Censura", "sin-censura"),
            Pair("Preestreno", "preestreno"),
            Pair("Película 1080p", "pelicula-1080p"),
            Pair("Audio Japonés", "audio-japones"),
        )

        // Valores tomados del HTML: name="fecha". El sitio muestra 2026 como tope.
        val YEARS: Array<Pair<String, String>> = buildList {
            add("Todos" to "")
            val maxYear = Calendar.getInstance().get(Calendar.YEAR).coerceAtLeast(2026)
            for (year in maxYear downTo 1982) {
                add(year.toString() to year.toString())
            }
        }.toTypedArray()

        // Valores tomados del HTML: name="letra" (el # usa value="09")
        val LETTERS = arrayOf(
            Pair("Todas", ""),
            Pair("#", "09"),
            Pair("A", "A"),
            Pair("B", "B"),
            Pair("C", "C"),
            Pair("D", "D"),
            Pair("E", "E"),
            Pair("F", "F"),
            Pair("G", "G"),
            Pair("H", "H"),
            Pair("I", "I"),
            Pair("J", "J"),
            Pair("K", "K"),
            Pair("L", "L"),
            Pair("M", "M"),
            Pair("N", "N"),
            Pair("O", "O"),
            Pair("P", "P"),
            Pair("Q", "Q"),
            Pair("R", "R"),
            Pair("S", "S"),
            Pair("T", "T"),
            Pair("U", "U"),
            Pair("V", "V"),
            Pair("W", "W"),
            Pair("X", "X"),
            Pair("Y", "Y"),
            Pair("Z", "Z"),
        )

        // Valores tomados del HTML: name="genero[]", en el orden en que aparecen en el sitio.
        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasia"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Lucha", "lucha"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodias", "parodias"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Latino", "latino"),
            Pair("Espacial", "espacial"),
            Pair("Histórico", "historico"),
            Pair("Samurai", "samurai"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Demonios", "demonios"),
            Pair("Romance", "romance"),
            Pair("Dementia", "dementia"),
            Pair("Policía", "policia"),
            Pair("Castellano", "castellano"),
            Pair("Historia paralela", "historia-paralela"),
            Pair("Aenime", "aenime"),
            Pair("Blu-ray", "blu-ray"),
            Pair("Monogatari", "monogatari"),
            Pair("Suspenso", "suspenso"),
            Pair("Isekai", "isekai"),
        )
    }
}
