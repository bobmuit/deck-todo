package app.vinkje.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset
import kotlin.test.assertEquals

class SyncLogTest {
    @TempDir lateinit var dir: File

    private fun log() = SyncLog(File(dir, "sync.log"), now = { 0L }, zone = { ZoneOffset.UTC })

    @Test
    fun `lines are stamped and kept in order`() {
        val log = log()
        log.add("een")
        log.add("twee")
        assertEquals("1970-01-01 00:00:00 een\n1970-01-01 00:00:00 twee\n", log.read())
    }

    @Test
    fun `only the newest lines are kept`() {
        val log = log()
        repeat(SyncLog.MAX_LINES + 5) { log.add("regel $it") }
        val lines = log.read().lines().filter { it.isNotEmpty() }
        assertEquals(SyncLog.MAX_LINES, lines.size)
        assertEquals("1970-01-01 00:00:00 regel 5", lines.first())
        assertEquals("1970-01-01 00:00:00 regel ${SyncLog.MAX_LINES + 4}", lines.last())
    }

    @Test
    fun `clearing leaves nothing`() {
        val log = log()
        log.add("een")
        log.clear()
        assertEquals("", log.read())
    }
}
