package com.echorunner

import com.echorunner.render.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests the colour-blind-safe Echo palette.
 *
 * ## Why this is a test and not a screenshot
 *
 * A screenshot cannot answer the question this feature exists to answer. Looking at a frame with
 * normal colour vision tells you the Echoes look different *to you*; it says nothing about whether
 * they are still distinguishable to the roughly one man in twelve with a red-green deficiency, and
 * nothing at all about greyscale. What can be checked mechanically is the property the palette is
 * chosen for: separation in a channel that colour vision deficiency does not remove.
 *
 * So this asserts relative luminance spread (the channel that survives every form of CVD) and the
 * distinctness of the outline patterns (which survive even total achromatopsia).
 *
 * Telling timelines apart is mechanical here, not cosmetic: a Chapter 6 solve puts four Echoes on
 * screen and the player must know which one is about to step off a plate.
 */
class EchoPaletteTest {

    /** WCAG relative luminance, sRGB-linearised. */
    private fun luminance(argb: Int): Float {
        fun channel(c: Int): Float {
            val s = c / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun safeArgb(i: Int): Int {
        val c = Palette.echoSafe(i)
        return ((c.alpha * 255).toInt() shl 24) or
            ((c.red * 255).toInt() shl 16) or
            ((c.green * 255).toInt() shl 8) or
            (c.blue * 255).toInt()
    }

    @Test
    fun `the safe palette gives every timeline its own colour`() {
        val used = (0 until Palette.echoHueCount).map { safeArgb(it) }
        assertEquals("all five timelines should differ", used.size, used.toSet().size)
    }

    @Test
    fun `the safe palette separates timelines by luminance, not only hue`() {
        val lums = (0 until Palette.echoHueCount).map { luminance(safeArgb(it)) }.sorted()

        // Adjacent pairs must differ enough to read as "lighter / darker" once hue is gone. 0.045
                // is a deliberately modest floor: the point is that no two Echoes collapse into the
        // same grey, not that the palette is high-contrast art direction.
        for (i in 0 until lums.size - 1) {
            val gap = lums[i + 1] - lums[i]
            assertTrue(
                "two Echo colours are only ${"%.4f".format(gap)} apart in luminance — " +
                    "they would be the same shade of grey to a player who cannot use hue",
                gap >= 0.045f,
            )
        }
        assertTrue("the palette should span a useful range", lums.last() - lums.first() >= 0.45f)
    }

    @Test
    fun `the default palette is the one that needed fixing`() {
        // Documents the motivation rather than asserting a bug: the shipped default separates
        // Echo 0 (magenta) and Echo 4 (rose) almost entirely by hue, which is exactly the pair a
        // red-green deficiency struggles with. If someone later "fixes" this by editing the
        // default palette, this test failing is the signal to delete it, not to panic.
        val magenta = Palette.echo(0)
        val rose = Palette.echo(4)
        val lm = luminance(
            (0xFF shl 24) or ((magenta.red * 255).toInt() shl 16) or
                ((magenta.green * 255).toInt() shl 8) or (magenta.blue * 255).toInt(),
        )
        val lr = luminance(
            (0xFF shl 24) or ((rose.red * 255).toInt() shl 16) or
                ((rose.green * 255).toInt() shl 8) or (rose.blue * 255).toInt(),
        )
        assertTrue(
            "the default palette's first and last Echo are close in luminance (${"%.3f".format(abs(lm - lr))}), " +
                "which is why the safe palette exists",
            abs(lm - lr) < 0.12f,
        )
    }

    @Test
    fun `every timeline gets a distinct outline pattern, so the cue survives greyscale`() {
        val patterns = (0 until Palette.echoHueCount).map { Palette.echoDashPattern(it)?.toList() }
        assertEquals("patterns must be unique per timeline", patterns.size, patterns.toSet().size)
        assertEquals("exactly one timeline should be solid", 1, patterns.count { it == null })
        for (p in patterns.filterNotNull()) {
            assertTrue("a dash pattern needs an even number of entries", p.size % 2 == 0)
            assertTrue("dash lengths must be positive world units", p.all { it > 0f })
        }
    }

    @Test
    fun `both palettes wrap safely for any Echo index`() {
        // The renderer must never be able to crash on an unexpected index, including a negative
        // one: an Echo index arrives from world state, not from a literal.
        for (i in listOf(-7, -1, 0, 4, 5, 99)) {
            Palette.echo(i)
            Palette.echoSafe(i)
            Palette.echoDashPattern(i)
        }
    }
}
