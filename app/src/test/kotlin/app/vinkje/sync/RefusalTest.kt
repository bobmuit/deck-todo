package app.vinkje.sync

import app.vinkje.data.OutboxEntity.Kind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RefusalTest {
    @Test
    fun `a refused create keeps what was typed here and tries again later`() {
        for (kind in listOf(Kind.CREATE, Kind.LIST_CREATE, Kind.GROUP_CREATE)) {
            for (status in listOf(400, 403, 404, 409)) assertEquals(Refusal.POSTPONE, refusal(kind, status), "$kind $status")
        }
    }

    @Test
    fun `a refused edit lets the server's version come back`() {
        for (kind in listOf(Kind.UPDATE, Kind.LIST_UPDATE, Kind.LIST_MOVE, Kind.LIST_DELETE, Kind.GROUP_UPDATE, Kind.GROUP_DELETE, Kind.SHARE, Kind.UNSHARE)) {
            for (status in listOf(403, 404)) assertEquals(Refusal.REFETCH, refusal(kind, status), "$kind $status")
        }
    }

    @Test
    fun `a delete of a card that is gone is done, a forbidden one is undone`() {
        assertEquals(Refusal.DELETE_LOCAL, refusal(Kind.DELETE, 404))
        assertEquals(Refusal.UNDELETE, refusal(Kind.DELETE, 403))
    }
}
