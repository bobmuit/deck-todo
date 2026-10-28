package app.vinkje.sync

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the sync refused or removed, so that afterwards it can be found out why a task went.
 * Kept to the last [MAX_LINES] lines; shown under "Synclogboek" on the home screen.
 */
class SyncLog(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    @Synchronized
    fun add(line: String) {
        val stamp = STAMP.format(Instant.ofEpochMilli(now()).atZone(zone()))
        // Never worth failing a sync round over.
        try {
            val lines = read().lines().filter { it.isNotEmpty() } + "$stamp $line"
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            Unit
        }
    }

    @Synchronized
    fun read(): String = try {
        if (file.exists()) file.readText() else ""
    } catch (e: IOException) {
        ""
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_LINES = 300
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
