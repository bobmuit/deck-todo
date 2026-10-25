package app.vinkje.domain

/** One To Do "step": a checkbox line in the card description. */
data class Step(val text: String, val done: Boolean = false)

/**
 * The parts of a To Do task that live in a Deck card description.
 *
 * On the server the description is plain Markdown that Deck web renders sensibly:
 *
 * ```
 * Free-text note
 *
 * - [ ] first step
 * - [x] second step
 *
 * ---
 * Herhaling: FREQ=WEEKLY;BYDAY=MO
 * ```
 *
 * The footer after the last `---` holds machine-readable fields. It is a plain
 * paragraph rather than an HTML comment because the editor in Deck web drops
 * comments when someone edits the description there.
 */
data class TaskBody(
    val note: String = "",
    val steps: List<Step> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
) {
    val recurrence: Recurrence? get() = meta[KEY_RECURRENCE]?.let(Recurrence::parse)

    fun withRecurrence(rule: Recurrence?): TaskBody =
        copy(meta = if (rule == null) meta - KEY_RECURRENCE else meta + (KEY_RECURRENCE to rule.format()))

    companion object {
        const val KEY_RECURRENCE = "Herhaling"

        /** Only these keys are recognised in the footer, so a note that happens to end in `---` is left alone. */
        val FOOTER_KEYS = setOf(KEY_RECURRENCE)
    }
}

object TaskBodyCodec {
    private val stepLine = Regex("""^\s*[-*+] \[([ xX])\]\s?(.*)$""")
    private val footerLine = Regex("""^([A-Za-z][\w-]*): (.*)$""")

    fun parse(description: String?): TaskBody {
        if (description.isNullOrBlank()) return TaskBody()
        var lines = description.replace("\r\n", "\n").split('\n')

        val meta = linkedMapOf<String, String>()
        val separator = lines.indexOfLast { it.trim() == "---" }
        if (separator >= 0) {
            val tail = lines.drop(separator + 1).filter { it.isNotBlank() }
            val parsed = tail.map { footerLine.matchEntire(it.trim()) }
            if (tail.isNotEmpty() && parsed.all { it != null && it.groupValues[1] in TaskBody.FOOTER_KEYS }) {
                parsed.forEach { meta[it!!.groupValues[1]] = it.groupValues[2].trim() }
                lines = lines.take(separator)
            }
        }

        val steps = mutableListOf<Step>()
        val noteLines = mutableListOf<String>()
        for (line in lines) {
            val m = stepLine.matchEntire(line)
            if (m != null) steps += Step(m.groupValues[2].trim(), m.groupValues[1] != " ")
            else noteLines += line
        }
        return TaskBody(noteLines.joinToString("\n").trim('\n').trimEnd(), steps, meta)
    }

    fun format(body: TaskBody): String {
        val sections = mutableListOf<String>()
        if (body.note.isNotBlank()) sections += body.note.trimEnd()
        if (body.steps.isNotEmpty()) {
            sections += body.steps.joinToString("\n") { s ->
                "- [${if (s.done) "x" else " "}] ${s.text.replace('\n', ' ').trim()}"
            }
        }
        if (body.meta.isNotEmpty()) {
            sections += "---\n" + body.meta.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        }
        return sections.joinToString("\n\n")
    }
}
