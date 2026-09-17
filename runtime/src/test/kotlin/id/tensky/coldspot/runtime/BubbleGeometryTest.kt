package id.tensky.coldspot.runtime

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Where the bubble may be. Every test reads as **given** a window, what bars and cutouts take of it, and a
 * bubble of 48 with a margin of 8, **when** placed, dragged or let go, **then** this is where it is.
 */
class BubbleGeometryTest {
    /** A portrait phone, edge to edge: a status bar of 60 on top, a navigation bar of 120 below. */
    private val portrait = BubbleGeometry(width = 1080, height = 2400, insets = Edges(0, 60, 0, 120), size = 48, margin = 8)

    /** The same phone on its side: a cutout of 90 on the left, the navigation bar of 120 on the right. */
    private val landscape = BubbleGeometry(width = 2400, height = 1080, insets = Edges(90, 60, 120, 0), size = 48, margin = 8)

    @Test
    fun `it rests against an edge, a margin inside what the bars and cutouts leave`() {
        assertEquals(1080 - 8 - 48 to 60 + 8, portrait.place(BubblePosition(right = true, vertical = 0f)))
        assertEquals(8 to 2400 - 120 - 8 - 48, portrait.place(BubblePosition(right = false, vertical = 1f)))
        // on its side, the cutout and the navigation bar are what the edges are measured from
        assertEquals(90 + 8 to 60 + 8, landscape.place(BubblePosition(right = false, vertical = 0f)))
        assertEquals(2400 - 120 - 8 - 48 to 1080 - 8 - 48, landscape.place(BubblePosition(right = true, vertical = 1f)))
    }

    @Test
    fun `the height is a fraction of the room, so that it means the same place in a window of another size`() {
        // the room runs from 68 to 2224 in portrait and from 68 to 1024 on its side
        assertEquals(68 + (2224 - 68) / 2, portrait.place(BubblePosition(right = true, vertical = 0.5f)).second)
        assertEquals(68 + (1024 - 68) / 2, landscape.place(BubblePosition(right = true, vertical = 0.5f)).second)
        // and a fraction out of range, from a damaged preference, is brought back into it
        assertEquals(2224, portrait.place(BubblePosition(right = true, vertical = 7f)).second)
        assertEquals(68, portrait.place(BubblePosition(right = true, vertical = -1f)).second)
    }

    @Test
    fun `dragged, it follows the finger but never goes under a bar or a cutout`() {
        assertEquals(500f to 900f, portrait.clamp(500f, 900f))
        assertEquals(8f to 68f, portrait.clamp(-300f, 0f))
        assertEquals(1024f to 2224f, portrait.clamp(5000f, 5000f))
        assertEquals(98f to 68f, landscape.clamp(0f, 0f))
        assertEquals(2224f to 1024f, landscape.clamp(2400f, 1080f))
    }

    @Test
    fun `let go, it takes the nearer edge by its centre and keeps its height`() {
        // centre at 515 + 24 = 539, left of the middle at 540
        assertEquals(false, portrait.snap(515f, 900f).right)
        // centre at 516 + 24 = 540, the middle itself: right
        assertEquals(true, portrait.snap(516f, 900f).right)
        // the height survives the round trip
        val released = portrait.snap(700f, 900f)
        assertEquals(1024 to 900, portrait.place(released))
        // let go under the navigation bar, it rests at the bottom of its room
        assertEquals(1f, portrait.snap(700f, 2399f).vertical)
    }

    @Test
    fun `a window too small for it leaves it at the top-left of its room, never at a negative place`() {
        val tiny = BubbleGeometry(width = 40, height = 40, insets = Edges(0, 10, 0, 10), size = 48, margin = 8)
        assertEquals(8 to 18, tiny.place(BubblePosition(right = true, vertical = 1f)))
        assertEquals(8f to 18f, tiny.clamp(100f, 100f))
        assertEquals(0f, tiny.snap(100f, 100f).vertical)
    }

    @Test
    fun `a cutout inside a bar takes no more room than the bar`() {
        assertEquals(Edges(90, 60, 0, 120), Edges(0, 60, 0, 120).union(Edges(90, 40, 0, 0)))
    }
}
