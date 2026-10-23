package dev.chenwuyi.leaf

import androidx.compose.ui.geometry.Rect
import dev.chenwuyi.leaf.ui.home.neighboursOf
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighboursTest {
    @Test
    fun should_ring_only_chips_within_the_pad_when_a_chip_is_tapped() {
        val bounds = mapOf(
            "tapped" to Rect(100f, 100f, 200f, 130f),
            "right" to Rect(210f, 100f, 300f, 130f),
            "below" to Rect(120f, 145f, 220f, 175f),
            "far" to Rect(500f, 100f, 600f, 130f),
        )
        assertEquals(setOf("right", "below"), neighboursOf("tapped", bounds, pad = 22f))
    }

    @Test
    fun should_return_nothing_when_the_tapped_chip_was_never_laid_out() {
        assertEquals(emptySet<String>(), neighboursOf("missing", mapOf("a" to Rect(0f, 0f, 1f, 1f)), 22f))
    }
}
