package com.sebrain.app

import kotlin.random.Random

/**
 * Generated notes for [TrustEvalTest]: everyday notes in English and Spanish that reuse the words
 * of the real test notes (backup, flight, code, locker, oil, birthday…), so search has to tell
 * them apart by what they say. Fixed seed: the same count gives the same notes.
 */
object Filler {
    private const val SEED = 20261120
    private const val FIRST_DAY = 1
    private const val DAYS = 28

    private val people = listOf("Ana", "Ben", "Carla", "Diego", "Elena", "Femi", "Grace", "Hugo", "Inés", "Jon")
    private val places = listOf("Porto", "Sevilla", "Berlin", "Kyoto", "Lima", "Oslo", "Napoli", "Quito")
    private val things = listOf("the router", "the backup script", "the flight search", "the locker room", "the oil heater",
        "the booking page", "the birthday list", "the garden hose", "the server rack", "the insurance form")
    private val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto")
    private val foods = listOf("garbanzos", "arroz", "tomate", "pimiento", "lentejas rojas", "calabaza", "setas")

    private val templates: List<(Int, Random) -> String> = listOf(
        { n, r -> "# Meeting notes $n\n\nDiscussed ${things.pick(r)} with ${people.pick(r)}. Action: ${people.pick(r)} checks ${things.pick(r)} by day ${day(r)}.\n\n#work\n" },
        { n, r -> "# Idea de receta $n\n\n${foods.pick(r).replaceFirstChar { it.uppercase() }} con ${foods.pick(r)} y comino. ${r.nextInt(15, 60)} minutos al horno.\n\n#recetas\n" },
        { n, r -> "# Travel idea $n\n\nMaybe visit ${places.pick(r)} in spring. Flights around ${r.nextInt(80, 400)} euros, code to check later.\n\n#travel\n" },
        { n, r -> "# Tech log $n\n\nUpdated ${things.pick(r)}. The backup of ${things.pick(r)} ran at ${r.nextInt(0, 24)}:${r.nextInt(10, 60)} without errors.\n\n#tech\n" },
        { n, r -> "# Gasto $n\n\nPagué ${r.nextInt(5, 300)} euros en ${things.pick(r)} el ${day(r)} de ${months.pick(r)}.\n\n#finanzas\n" },
        { n, r -> "# Reading notes $n\n\nChapter ${r.nextInt(1, 30)}: ${people.pick(r)} talks about ${things.pick(r)}.\n\n#books\n" },
        { n, r -> "# Recordatorio $n\n\nLlamar a ${people.pick(r)} el ${day(r)} de ${months.pick(r)} por ${things.pick(r)}.\n\n#casa\n" },
        { n, r -> "# Workout $n\n\n${r.nextInt(3, 12)} km run and ${r.nextInt(2, 6)} sets of squats. Locker room was full.\n\n#deporte\n" },
    )

    /** [count] notes, each with its text and a date in 2026 before the real notes. */
    fun notes(count: Int): List<Pair<String, String>> {
        val random = Random(SEED)
        return (1..count).map { n ->
            val text = templates[n % templates.size](n, random)
            text to "2026-%02d-%02dT08:00:00Z".format(1 + n % 10, day(random))
        }
    }

    private fun day(random: Random) = random.nextInt(FIRST_DAY, DAYS + 1)

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]
}
