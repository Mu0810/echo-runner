package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.Viewport
import com.echorunner.level.LevelParser
import com.echorunner.ui.ControlLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Proves the on-screen controls cannot cover the level.
 *
 * This is the test that did not exist when it was needed. The renderer sized the world to the
 * full canvas while the controls placed themselves against that same canvas independently, so
 * nothing could detect that the JUMP button sat on top of the exit portal — which it did, on 22
 * of the 30 levels, plus three pressure plates. A screenshot review missed it because the
 * buttons are translucent and the portal glow read through them.
 *
 * The geometry is now one pure function, so the question is decidable on every device shape at
 * once rather than one emulator at a time.
 */
class ViewportLayoutTest {

    /** width x height x density: real Android shapes, portrait-locked game so width < height. */
    private val devices = listOf(
        Triple(1080f, 2400f, 2.75f), // Pixel 8 — 20:9, the reference device
        Triple(1080f, 2340f, 2.75f), // 19.5:9, very common
        Triple(1440f, 3120f, 3.5f),  // Pixel 8 Pro — high density
        Triple(720f, 1280f, 2.0f),   // 16:9 budget phone, minSdk-era shape
        Triple(1200f, 1600f, 2.0f),  // 4:3 tablet — worst case for a width-bound fit
        Triple(1768f, 2208f, 2.6f),  // Pixel Fold inner display, near-square
    )

    private val controlScales = listOf(0.75f, 1.0f, 1.4f)

    private fun cases(): List<Case> = buildList {
        for ((w, h, d) in devices) {
            for (cs in controlScales) {
                for (lefty in listOf(false, true)) add(Case(w, h, d, cs, lefty))
            }
        }
    }

    private data class Case(
        val w: Float,
        val h: Float,
        val density: Float,
        val controlScale: Float,
        val leftHanded: Boolean,
    ) {
        // The game renders in landscape, so the canvas is the device rotated.
        val screenW get() = h
        val screenH get() = w
        val playfield get() = ControlLayout.playfield(screenW, screenH, density, controlScale, leftHanded)
        val stick get() = ControlLayout.stickFootprint(screenW, screenH, density, controlScale, leftHanded)
        val cluster get() = ControlLayout.clusterFootprint(screenW, screenH, density, controlScale, leftHanded)
        override fun toString() =
            "${screenW.toInt()}x${screenH.toInt()} @${density}x scale=$controlScale lefty=$leftHanded"
    }

    @Test
    fun `no control ever overlaps the playfield`() {
        for (c in cases()) {
            assertTrue(
                "stick overlaps the world on $c\n  world=${c.playfield}\n  stick=${c.stick}",
                !c.playfield.overlaps(c.stick),
            )
            assertTrue(
                "action arc overlaps the world on $c\n  world=${c.playfield}\n  arc=${c.cluster}",
                !c.playfield.overlaps(c.cluster),
            )
        }
    }

    @Test
    fun `playfield stays on screen and keeps the world's aspect ratio`() {
        val worldAspect = GameConstants.WORLD_W / GameConstants.WORLD_H
        for (c in cases()) {
            val p = c.playfield
            assertTrue("playfield off the left on $c", p.left >= -0.01f)
            assertTrue("playfield off the top on $c", p.top >= -0.01f)
            assertTrue("playfield off the right on $c: ${p.right} > ${c.screenW}", p.right <= c.screenW + 0.01f)
            assertTrue("playfield off the bottom on $c: ${p.bottom} > ${c.screenH}", p.bottom <= c.screenH + 0.01f)
            assertEquals("aspect distorted on $c", worldAspect, p.width / p.height, 0.001f)
        }
    }

    @Test
    fun `the HUD strip is never drawn over`() {
        for (c in cases()) {
            val hud = Viewport(0f, 0f, c.screenW, ControlLayout.HUD_DP * c.density)
            assertTrue("world overlaps the HUD on $c", !c.playfield.overlaps(hud))
        }
    }

    @Test
    fun `shrinking the controls grows the play area`() {
        val (w, h, d) = devices.first()
        val big = ControlLayout.playfield(h, w, d, controlScale = 1.4f)
        val mid = ControlLayout.playfield(h, w, d, controlScale = 1.0f)
        val small = ControlLayout.playfield(h, w, d, controlScale = 0.75f)
        assertTrue("1.0x should give more room than 1.4x", mid.width > big.width)
        assertTrue("0.75x should give more room than 1.0x", small.width > mid.width)
    }

    /**
     * The regression proper: replay every shipped level's exit and plates through the real
     * transform and assert none of them lands under a button.
     *
     * Before the fix this failed on 25 levels. It is tied to the level data on purpose — the
     * geometric test above proves the frame is sound, but only this one proves the frame is
     * sound *for the rooms that actually ship*.
     */
    @Test
    fun `no shipped level puts its exit or a plate under a control`() {
        val levelsDir = File("src/main/assets/levels")
        val root = JSONObject(File(levelsDir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        val ids = ArrayList<String>()
        for (i in 0 until chapters.length()) {
            val levels = chapters.getJSONObject(i).getJSONArray("levels")
            for (j in 0 until levels.length()) ids.add(levels.getString(j))
        }

        // Reference device at default settings: what the overwhelming majority of players see.
        val c = Case(1080f, 2400f, 2.75f, 1.0f, leftHanded = false)
        val p = c.playfield
        val scale = p.width / GameConstants.WORLD_W
        fun toScreen(x: Float, y: Float, w: Float, h: Float) =
            Viewport(p.left + x * scale, p.top + y * scale, w * scale, h * scale)

        val offenders = ArrayList<String>()
        for (id in ids) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            val targets = buildList {
                add("exit" to spec.exit)
                spec.plates.forEach { add("plate ${it.id}" to it.rect) }
            }
            for ((label, r) in targets) {
                val box = toScreen(r.x, r.y, r.w, r.h)
                if (box.overlaps(c.stick)) offenders += "$id $label under the stick"
                if (box.overlaps(c.cluster)) offenders += "$id $label under the action arc"
            }
        }
        assertTrue(
            "level content is hidden behind the controls:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `reference device loses less than a quarter of its height to the reservation`() {
        val p = ControlLayout.playfield(2400f, 1080f, 2.75f, controlScale = 1.0f)
        val fraction = p.height / 1080f
        // Documents the actual trade rather than asserting a vague "looks fine": if a future
        // control change pushes the world below three quarters of the screen, that is a design
        // decision someone must make deliberately, not discover in a store review.
        assertTrue(
            "world shrank to ${"%.0f".format(fraction * 100)}% of screen height — too small",
            fraction >= 0.75f,
        )
        assertTrue("world should not fill the screen; controls need room", fraction < 1f)
        assertTrue("sanity: uniform scale", abs(p.width / p.height - 40f / 22.5f) < 0.001f)
    }
}
