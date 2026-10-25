package app.vinkje.deck

import kotlinx.serialization.Serializable

/**
 * The personal parts of imported To Do tasks, which have no place on a shared card.
 * The importer leaves this file in the user's own Nextcloud files; the app adopts the
 * reminders it can match to a card and removes the file once nothing is left.
 */
@Serializable
data class ImportHandoff(val reminders: List<Reminder> = emptyList()) {
    /** A reminder at [at] (epoch seconds) on the card with id [card]. */
    @Serializable
    data class Reminder(val card: Long, val at: Long)

    companion object {
        /** Relative to the user's files root. Nextcloud hides dot folders by default. */
        const val PATH = ".deck-todo/import.json"
    }
}
