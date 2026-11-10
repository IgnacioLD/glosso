package com.glosso.app

import kotlin.test.assertEquals
import kotlin.test.Test

class MatchParseTest {

    @Test
    fun splitsWordFromMeaning() {
        val pairs = parseMatchPairs(
            listOf(
                "holiday=time away from work to rest",
                "camera=a machine for taking photos",
                "station=the place where trains stop",
            ),
        )
        assertEquals(3, pairs.size)
        assertEquals("holiday" to "time away from work to rest", pairs[0])
        assertEquals("station" to "the place where trains stop", pairs[2])
    }

    @Test
    fun keepsEqualsInsideTheMeaning() {
        val pairs = parseMatchPairs(listOf("sign=a board with words, e.g. a=plan"))
        assertEquals("sign" to "a board with words, e.g. a=plan", pairs.single())
    }

    @Test
    fun dropsMalformedRowsInsteadOfCrashing() {
        val pairs = parseMatchPairs(listOf("noequals", "=meaning only", "word=", " ok = fine "))
        assertEquals(listOf("ok" to "fine"), pairs)
    }

    @Test
    fun handlesManyPairs() {
        val options = (1..8).map { "word$it=meaning $it" }
        assertEquals(8, parseMatchPairs(options).size)
    }
}
