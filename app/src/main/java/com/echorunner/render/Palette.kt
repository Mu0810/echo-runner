package com.echorunner.render

import androidx.compose.ui.graphics.Color

/**
 * The game's entire colour language.
 *
 * Deliberately small: a near-black void plus **five** meaning-bearing hues. Cyan is always
 * the player, a per-timeline hue is always an Echo, amber is always lethal, violet is always
 * a way out, emerald is always "signal on". Nothing else gets a colour, because a fifth
 * decorative hue would cost the player the ability to read a frame at a glance — which in a
 * game about choreographing four simultaneous timelines is the whole interface.
 *
 * Brightness, not saturation, carries the neon: shapes are dark slabs with one bright edge
 * over a dark field. Filling shapes with saturated colour is what makes a neon scene read as
 * generic, so it is not done anywhere.
 *
 * Every value is a `Color`, which is a value class over `ULong` — reading these in a draw
 * loop allocates nothing. Gradient brushes, which *do* allocate, live cached in the renderer.
 */
object Palette {

    // ---- Field ------------------------------------------------------------------------

    /** The void. Every other colour is chosen to read against this. */
    val void = Color(0xFF05060B)

    /**
     * Barely-lifted void, used only as the lower stop of the backdrop gradient. Enough to
     * imply depth in the room without becoming a visible band.
     */
    val voidLift = Color(0xFF090D18)

    val gridLine = Color(0xFF17202F)

    /** Cold haze tint for the drifting background washes. */
    val fog = Color(0xFF14263C)

    // ---- Geometry ---------------------------------------------------------------------

    /** Slab body. Almost the void: a platform is a silhouette, not a bright block. */
    val platformFill = Color(0xFF0B0F18)

    /**
     * Platform top edge. A cool near-white rather than a saturated hue, so the level's
     * geometry never competes with the cyan player or the coloured Echoes for attention.
     */
    val platformEdge = Color(0xFFB8C9DE)

    // ---- Bodies -----------------------------------------------------------------------

    val player = Color(0xFF22D3EE)

    /** Lighter than [player]: a halo reads as light bleeding, not as a second body. */
    val playerGlow = Color(0xFF67E8F9)

    // ---- Lethal (one family, so danger is learned once) --------------------------------

    val hazard = Color(0xFFFBBF24)

    /** Hot end of the amber family — a beam is light, so it sits brighter than spikes. */
    val laser = Color(0xFFFCD34D)

    /** Deep end of the amber family — an enemy is a body, so it sits darker than light. */
    val enemy = Color(0xFFF59E0B)

    val bullet = Color(0xFFFDE68A)

    // ---- Signals and exits -------------------------------------------------------------

    val exit = Color(0xFFA78BFA)

    /** Emerald is reserved for "signal on" and used nowhere else in the frame. */
    val plateOn = Color(0xFF34D399)
    val plateOff = Color(0xFF2A3546)

    /** Indigo: same portal family as [exit], distinct enough not to be mistaken for one. */
    val teleporter = Color(0xFF818CF8)

    // ---- Echoes -----------------------------------------------------------------------

    /** Number of distinct Echo hues; matches `GameConstants.MAX_ECHOES`. */
    const val echoHueCount = 5

    /**
     * Hue for the Echo created at [index].
     *
     * Distinct hues per timeline is a mechanical requirement, not decoration: a Chapter 6
     * solve has four Echoes on screen at once and the player has to know which one is about
     * to step off the plate. Wraps rather than throwing so a renderer can never crash on an
     * unexpected index.
     */
    fun echo(index: Int): Color = when (((index % echoHueCount) + echoHueCount) % echoHueCount) {
        0 -> Color(0xFFE879F9) // magenta
        1 -> Color(0xFF2DD4BF) // teal
        2 -> Color(0xFFA3E635) // lime
        3 -> Color(0xFFFB923C) // orange
        else -> Color(0xFFFB7185) // rose
    }

    /**
     * Colour-blind-safe hue for the Echo at [index], from the Okabe-Ito qualitative palette.
     *
     * Ordered by **ascending luminance**, so "a later Echo is a brighter Echo" becomes a rule the
     * player can learn and use even when the hues are indistinguishable to them.
     *
     * The five were chosen by measuring, not by eye. Okabe-Ito offers seven; a first attempt took
     * sky blue and bluish green alongside orange and vermilion, and the palette test rejected it —
     * two of those sit 0.035 apart in relative luminance, close enough to collapse into the same
     * grey once hue is gone. This subset has a 0.069 minimum gap across a 0.59 span. Sky blue was
     * also the closest of the seven to the player's own cyan, so dropping it separates "you" from
     * "your Echoes" as well.
     */
    fun echoSafe(index: Int): Color = when (((index % echoHueCount) + echoHueCount) % echoHueCount) {
        0 -> Color(0xFF0072B2) // blue        L 0.152
        1 -> Color(0xFFD55E00) // vermilion   L 0.222
        2 -> Color(0xFFCC79A7) // reddish purple  L 0.293
        3 -> Color(0xFFE69F00) // orange      L 0.416
        else -> Color(0xFFF0E442) // yellow   L 0.744
    }

    /**
     * Outline dash pattern per timeline, in world units: solid, long dash, dot, dash-dot, fine.
     *
     * The cue of last resort. A pattern is not a colour, so it survives any form of colour
     * vision deficiency and a greyscale screenshot alike.
     */
    fun echoDashPattern(index: Int): FloatArray? =
        when (((index % echoHueCount) + echoHueCount) % echoHueCount) {
            0 -> null                       // solid
            1 -> floatArrayOf(0.34f, 0.20f)
            2 -> floatArrayOf(0.10f, 0.16f)
            3 -> floatArrayOf(0.34f, 0.14f, 0.10f, 0.14f)
            else -> floatArrayOf(0.06f, 0.10f)
        }

    /**
     * Particle colour for a pool tint index. Emitters pick an index rather than a colour so
     * the simulation stays free of rendering types; `when` avoids the boxing an
     * `Array<Color>` lookup would introduce in the particle loop.
     */
    fun particleTint(index: Int): Color = when (((index % 8) + 8) % 8) {
        0 -> Color(0xFF67E8F9) // player cyan — jump / dash
        1 -> Color(0xFFE2E8F0) // cold white — landing
        2 -> Color(0xFFFBBF24) // amber — impacts, muzzle flash
        3 -> Color(0xFF94A3B8) // slate — footfall dust
        4 -> Color(0xFFE879F9) // magenta — Echo trail
        5 -> Color(0xFF2DD4BF) // teal — Echo trail
        6 -> Color(0xFFA78BFA) // violet — teleport
        else -> Color(0xFFA3E635) // lime — Echo trail
    }
}
