package app.vinkje.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How To Do concepts are spelled in Deck. The app and the importer must agree on these. */
object DeckConventions {
    /**
     * The private board that holds the lists To Do shows outside any group. Found by
     * title (and owner), so it survives a reinstall and also shows in Deck web.
     */
    const val LOOSE_BOARD = "Lijsten"

    /** Deck's label for To Do's star, created on a board the first time someone stars a task there. */
    const val IMPORTANT_LABEL = "Belangrijk"
    const val IMPORTANT_COLOR = "f1c40f"

    /** Board colours for new groups, taken in turn: Deck's own palette. */
    val GROUP_COLORS = listOf("0082c9", "00a375", "e9322d", "f0c400", "a44a9f", "e67e22", "1abc9c", "7d6a5f")

    /**
     * To Do due dates are days; Deck stores a moment. The end of the day keeps Deck web
     * from marking a task overdue on the day it is due.
     */
    fun dueInstant(date: LocalDate, zone: ZoneId): Instant = date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant()
}
