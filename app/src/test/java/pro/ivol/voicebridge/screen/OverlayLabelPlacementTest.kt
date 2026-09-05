package pro.ivol.voicebridge.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLabelPlacementTest {
    @Test
    fun `adjacent short labels are separated instead of overlapping`() {
        val first = OverlayLabelBox(left = 10f, top = 10f, right = 70f, bottom = 30f)
        val desired = OverlayLabelBox(left = 60f, top = 10f, right = 120f, bottom = 30f)

        val placed = requireNotNull(
            resolveOverlayLabelPlacement(
                desired = desired,
                occupied = listOf(first),
                bounds = OverlayLabelBox(0f, 0f, 200f, 120f),
                gap = 2f,
                maximumShift = 64f,
            ),
        )

        assertEquals(72f, placed.left)
        assertEquals(10f, placed.top)
        assertFalse(overlapsWithGap(first, placed, 2f))
    }

    @Test
    fun `label near bottom moves above an occupied label`() {
        val occupied = OverlayLabelBox(left = 10f, top = 80f, right = 110f, bottom = 100f)
        val desired = OverlayLabelBox(left = 10f, top = 86f, right = 110f, bottom = 106f)

        val placed = requireNotNull(
            resolveOverlayLabelPlacement(
                desired = desired,
                occupied = listOf(occupied),
                bounds = OverlayLabelBox(0f, 0f, 120f, 110f),
                gap = 2f,
                maximumShift = 64f,
            ),
        )

        assertEquals(58f, placed.top)
        assertFalse(overlapsWithGap(occupied, placed, 2f))
    }

    @Test
    fun `already separated labels keep their anchor position`() {
        val occupied = OverlayLabelBox(left = 0f, top = 0f, right = 50f, bottom = 20f)
        val desired = OverlayLabelBox(left = 0f, top = 22f, right = 50f, bottom = 42f)

        val placed = resolveOverlayLabelPlacement(
            desired = desired,
            occupied = listOf(occupied),
            bounds = OverlayLabelBox(0f, 0f, 100f, 100f),
            gap = 2f,
            maximumShift = 64f,
        )

        assertEquals(desired, placed)
    }

    @Test
    fun `dense screen drops a label rather than painting over another`() {
        val occupied = listOf(
            OverlayLabelBox(left = 0f, top = 0f, right = 100f, bottom = 20f),
            OverlayLabelBox(left = 0f, top = 22f, right = 100f, bottom = 42f),
        )
        val desired = OverlayLabelBox(left = 0f, top = 10f, right = 100f, bottom = 30f)

        val placed = resolveOverlayLabelPlacement(
            desired = desired,
            occupied = occupied,
            bounds = OverlayLabelBox(0f, 0f, 100f, 42f),
            gap = 2f,
            maximumShift = 64f,
        )

        assertNull(placed)
    }

    @Test
    fun `placement remains inside the visible bounds`() {
        val desired = OverlayLabelBox(left = -8f, top = -4f, right = 32f, bottom = 16f)

        val placed = requireNotNull(
            resolveOverlayLabelPlacement(
                desired = desired,
                occupied = emptyList(),
                bounds = OverlayLabelBox(6f, 8f, 100f, 100f),
                gap = 2f,
                maximumShift = 64f,
            ),
        )

        assertTrue(placed.left >= 6f)
        assertTrue(placed.top >= 8f)
        assertTrue(placed.right <= 100f)
        assertTrue(placed.bottom <= 100f)
    }

    @Test
    fun `row of nearby buttons remains readable`() {
        val occupied = mutableListOf<OverlayLabelBox>()
        val desiredLabels = listOf(
            OverlayLabelBox(0f, 10f, 60f, 30f),
            OverlayLabelBox(40f, 10f, 100f, 30f),
            OverlayLabelBox(80f, 10f, 140f, 30f),
            OverlayLabelBox(120f, 10f, 180f, 30f),
        )

        desiredLabels.forEach { desired ->
            val placed = requireNotNull(
                resolveOverlayLabelPlacement(
                    desired = desired,
                    occupied = occupied,
                    bounds = OverlayLabelBox(0f, 0f, 240f, 100f),
                    gap = 2f,
                    maximumShift = 64f,
                ),
            )
            assertTrue(occupied.none { existing -> overlapsWithGap(existing, placed, 2f) })
            occupied += placed
        }

        assertEquals(desiredLabels.size, occupied.size)
    }

    private fun overlapsWithGap(
        first: OverlayLabelBox,
        second: OverlayLabelBox,
        gap: Float,
    ): Boolean = first.left < second.right + gap &&
        first.right > second.left - gap &&
        first.top < second.bottom + gap &&
        first.bottom > second.top - gap
}
