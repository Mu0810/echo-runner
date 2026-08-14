package com.echorunner.ui

import com.echorunner.core.Viewport

/**
 * One source of truth for how much screen the on-screen controls occupy — and therefore how
 * much is left for the world.
 *
 * ## Why the world no longer fills the screen
 *
 * The renderer used to fit the world to screen *height* and centre it, on the theory that a
 * 20:9 phone leaves an unused letterbox column down each side for the controls to live in.
 * The columns are real but they are nowhere near big enough. Measured on a 1080x2400 phone:
 *
 * ```
 * world drawn 1920 px wide  ->  240 px of margin per side
 * controls need             ->  451 px (stick) + 468 px (action arc) = 919 px
 * deficit                                                            = 439 px
 * ```
 *
 * So the two clusters ate 4.4 and 4.7 world units off the left and right edges — 23% of the
 * level's width, permanently under a thumb. That is where Echo Runner keeps its exits: 25 of
 * the 30 levels had their exit portal or a pressure plate underneath a button.
 *
 * It cannot be fixed by sliding the camera. Shifting the view fully left clears the action arc
 * but buries 8.6 units under the stick; the clusters together are wider than all the slack
 * there is. The only honest fix is to stop drawing the world underneath them, which is what
 * [playfield] computes. The cost is a world rendered at ~77% of screen height on a modern
 * phone. For a game whose core act is reading a whole room and planning four timelines through
 * it, a complete small room beats a large one with its goal hidden.
 *
 * ## A useful consequence
 *
 * Because the reservation is derived from `controlScale`, the settings slider that shrinks the
 * buttons now *grows the play area* — at 0.75x the world renders at ~88% of screen height. The
 * trade is the player's to make, which is better than the engine picking for them.
 *
 * Hit areas may still extend past what is reserved here, and that is deliberate: the world
 * itself is not touch-interactive, so a generous invisible target overlapping the play field
 * costs nothing, while a thumb that misses JUMP by 6 px costs a take.
 */
object ControlLayout {
    // Mirrors of the sizes used by the composables below. TouchControls and ActionCluster read
    // these same constants, so the reservation cannot silently drift from what is drawn.
    const val STICK_DP = 150f
    const val EDGE_PAD_DP = 14f
    const val BOTTOM_PAD_DP = 18f

    const val JUMP_DP = 96f
    const val DASH_DP = 74f
    const val USE_DP = 62f
    const val GAP_DP = 9f
    const val ARC_INSET_DP = 11f

    /** Action arc: USE tucked inboard of DASH, JUMP owning the corner. 156 dp at 1x. */
    const val CLUSTER_W_DP = DASH_DP + ARC_INSET_DP + GAP_DP + USE_DP

    /** JUMP with DASH stacked above it. 179 dp at 1x. */
    const val CLUSTER_H_DP = JUMP_DP + GAP_DP + DASH_DP

    /**
     * Reserved for the HUD strip across the top. The HUD used to overlay the world too — the
     * take timer sat over the ceiling of every level. Measured from its tallest column (the
     * timer: 12 dp padding + 26 sp digits + label + 12 dp) with a little slack.
     */
    const val HUD_DP = 72f

    /**
     * Screen columns the stick and the arc claim, edge padding included, in pixels.
     *
     * Deliberately written as `pad * density + size * density` rather than
     * `(pad + size) * density`: the footprint helpers below build their edges the same way, and
     * the two must agree bit-for-bit. Folding the multiply produces a different float and the
     * playfield lands a hair inside the button.
     */
    fun stickReservePx(density: Float, controlScale: Float): Float =
        EDGE_PAD_DP * density + STICK_DP * controlScale * density

    fun clusterReservePx(density: Float, controlScale: Float): Float =
        EDGE_PAD_DP * density + CLUSTER_W_DP * controlScale * density

    /**
     * The rectangle the world is drawn into: the screen minus the HUD strip and minus both
     * control columns, with the world fitted uniformly inside what remains.
     *
     * The reservation does **not** depend on whether the controls are currently visible. A
     * story beat or the pause menu hides them for a moment, and a world that resized itself
     * every time a dialogue opened would be far worse than a fixed frame.
     */
    fun playfield(
        screenW: Float,
        screenH: Float,
        density: Float,
        controlScale: Float,
        leftHanded: Boolean = false,
    ): Viewport {
        val scale = controlScale.coerceIn(0.75f, 1.4f)
        val stick = stickReservePx(density, scale)
        val cluster = clusterReservePx(density, scale)
        // Left-handed play swaps which cluster is on which side, so the asymmetry flips with it.
        val leftInset = if (leftHanded) cluster else stick
        val rightInset = if (leftHanded) stick else cluster
        val top = HUD_DP * density

        val box = Viewport(
            left = leftInset,
            top = top,
            width = (screenW - leftInset - rightInset).coerceAtLeast(1f),
            height = (screenH - top).coerceAtLeast(1f),
        )
        return Viewport.fitWorld(box)
    }

    /** Footprint of the stick, for the overlap tests. Bottom-aligned, mirrored by handedness. */
    fun stickFootprint(
        screenW: Float,
        screenH: Float,
        density: Float,
        controlScale: Float,
        leftHanded: Boolean = false,
    ): Viewport = footprint(
        screenW, screenH, density,
        widthDp = STICK_DP * controlScale.coerceIn(0.75f, 1.4f),
        heightDp = STICK_DP * controlScale.coerceIn(0.75f, 1.4f),
        onRight = leftHanded,
    )

    /** Footprint of the action arc, for the overlap tests. */
    fun clusterFootprint(
        screenW: Float,
        screenH: Float,
        density: Float,
        controlScale: Float,
        leftHanded: Boolean = false,
    ): Viewport = footprint(
        screenW, screenH, density,
        widthDp = CLUSTER_W_DP * controlScale.coerceIn(0.75f, 1.4f),
        heightDp = CLUSTER_H_DP * controlScale.coerceIn(0.75f, 1.4f),
        onRight = !leftHanded,
    )

    private fun footprint(
        screenW: Float,
        screenH: Float,
        density: Float,
        widthDp: Float,
        heightDp: Float,
        onRight: Boolean,
    ): Viewport {
        val w = widthDp * density
        val h = heightDp * density
        val pad = EDGE_PAD_DP * density
        return Viewport(
            // Mirrors stickReservePx / clusterReservePx term by term, so an edge shared with the
            // playfield is the same float on both sides rather than the same number twice.
            left = if (onRight) screenW - pad - w else pad,
            top = screenH - BOTTOM_PAD_DP * density - h,
            width = w,
            height = h,
        )
    }
}
