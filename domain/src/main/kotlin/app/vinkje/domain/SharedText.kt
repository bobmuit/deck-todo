package app.vinkje.domain

/** A task made from text shared from another app: its title and the note that goes along. */
data class SharedTask(val title: String, val note: String)

object SharedText {
    /** Deck refuses card titles longer than this. */
    const val MAX_TITLE = 255

    /**
     * The subject becomes the title when there is one (Chrome shares the page title there and
     * the link as text), else the first non-blank line does. Everything else becomes the note.
     * Null when there is nothing to add.
     */
    fun toTask(subject: CharSequence?, text: CharSequence?): SharedTask? {
        val subj = subject?.toString()?.trim().orEmpty()
        val body = text?.toString()?.replace("\r\n", "\n")?.trim().orEmpty()
        val (title, note) = when {
            subj.isNotEmpty() -> subj to body.takeUnless { it == subj }.orEmpty()
            else -> {
                val lines = body.lines()
                val first = lines.indexOfFirst { it.isNotBlank() }
                if (first < 0) return null
                lines[first].trim() to lines.drop(first + 1).joinToString("\n").trim()
            }
        }
        val oneLine = title.replace(Regex("\\s+"), " ")
        if (oneLine.length <= MAX_TITLE) return SharedTask(oneLine, note)
        // Cut at a space where possible; the whole text goes along in the note.
        val cut = oneLine.lastIndexOf(' ', MAX_TITLE - 1).takeIf { it > MAX_TITLE / 2 } ?: (MAX_TITLE - 1)
        val shortTitle = oneLine.take(cut).trimEnd() + "…"
        return SharedTask(shortTitle, listOf(title, note).filter { it.isNotEmpty() }.joinToString("\n\n"))
    }
}
