package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptHistoryTest {

    @Test
    fun `a higher take is improving`() {
        assertEquals(TakeTrend.UP, takeTrend(current = 70, previous = 58))
    }

    @Test
    fun `a lower take is not a failure`() {
        assertEquals(TakeTrend.DOWN, takeTrend(current = 50, previous = 62))
    }

    @Test
    fun `an equal take is the same`() {
        assertEquals(TakeTrend.SAME, takeTrend(current = 54, previous = 54))
    }

    @Test
    fun `the item key is scoped by language`() {
        assertEquals("en:drill.1", AttemptHistory.key("drill.1", language = "en"))
        assertEquals("fr:drill.1", AttemptHistory.key("drill.1", language = "fr"))
    }
}
