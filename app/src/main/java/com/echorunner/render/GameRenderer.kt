package com.echorunner.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.echorunner.core.AnimState
import com.echorunner.core.DoorState
import com.echorunner.core.EchoBody
import com.echorunner.core.GameConstants
import com.echorunner.core.GameSettings
import com.echorunner.core.HazardKind
import com.echorunner.core.HazardSpec
import com.echorunner.core.LaserState
import com.echorunner.core.MoverState
import com.echorunner.core.PlateState
import com.echorunner.core.PlatformKind
import com.echorunner.core.PlayerBody
import com.echorunner.core.Rect
import com.echorunner.core.TeleporterState
import com.echorunner.core.TurretState
import com.echorunner.core.WalkerState
import com.echorunner.core.Viewport
import com.echorunner.core.WorldState
import com.echorunner.core.clamp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one simulation frame onto a Compose [DrawScope].
 *
 * ## Why the world→screen mapping is done by hand
 *
 * The obvious approach is `scale(k) { ... }` around the whole scene, but every neon edge in
 * this game is a **fixed-width** line: a 1.7 dp bright top edge is the entire visual language,
 * and under a canvas scale of ~50 px/unit that stroke would either be authored in absurd
 * fractional world units or come out 50× too thick. Mapping positions manually through
 * [wx]/[wy]/[wu] keeps positions in world units and line weights in device pixels, which is
 * exactly the split the art direction needs.
 *
 * ## Allocation policy
 *
 * `Color`, `Offset`, `Size` and `CornerRadius` are value classes, so they are free to build
 * per call. `Brush`, `Stroke`, `PathEffect` and `Path` are real objects, so they live in
 * fields and are rebuilt only when the viewport size or density changes — a rotation, not a
 * frame. Body geometry is written into scratch fields ([bx]/[by]/[bw]/[bh]) rather than
 * returned as a `Rect`, because squash/stretch is computed for the player plus up to five
 * Echoes plus their trails every frame.
 *
 * The renderer holds no gameplay state and never mutates [WorldState]; a take reset needs no
 * cooperation from it.
 */
class GameRenderer {

    // ---- Per-frame world→screen transform ---------------------------------------------

    /** Device pixels per world unit. */
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    // ---- Size/density-dependent object cache ------------------------------------------

    private var cachedW = -1f
    private var cachedH = -1f
    private var cachedDensity = -1f

    private var hairPx = 1f
    private var edgePx = 2f
    private var boldPx = 3f

    private var hairStroke = Stroke(width = 1f)
    private var edgeStroke = Stroke(width = 2f)
    private var boldStroke = Stroke(width = 3f, cap = StrokeCap.Round)

    /** Dash pattern for ONE_WAY platforms and Echo-only plates. Scale-dependent. */
    private var dashEffect: PathEffect? = null

    private var vignette: Brush? = null

    private val fogBrushes = arrayOfNulls<Brush>(FOG_LAYERS)
    private val fogRadii = FloatArray(FOG_LAYERS)

    /**
     * Backdrop wash. Size-independent: `verticalGradient` with default bounds resolves against
     * the draw area at paint time, so one instance serves every viewport.
     */
    private val backdrop: Brush =
        Brush.verticalGradient(listOf(Palette.void, Palette.voidLift))

    // ---- Reusable geometry ------------------------------------------------------------

    private val hazardPath = Path()

    /** Scratch body rect in world units, filled by [shapeBody]. */
    private var bx = 0f
    private var by = 0f
    private var bw = 0f
    private var bh = 0f

    /** Rounded-body corner radius in px, refreshed once per frame. */
    private var bodyCorner = CornerRadius.Zero

    // ---- Colours Palette deliberately does not expose ---------------------------------

    /**
     * Body interior. A shade *below* `Palette.platformFill` so a player standing against a
     * slab still separates from it — the outline does the work, but the core must not merge.
     */
    private val bodyCore = Color(0xFF060810)

    /** Machinery housings: turrets, laser emitters, door frames. */
    private val housing = Color(0xFF0C1220)

    /**
     * Renders [world] at interpolation factor [alpha] (0 = previous tick, 1 = current).
     *
     * [timeSeconds] is a render clock, used only for effects that must keep breathing while
     * the simulation is frozen (grid drift, fog, portal pulse, Echo distortion). It never
     * feeds back into gameplay, so a paused world still looks alive.
     */
    fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        world: WorldState,
        alpha: Float,
        settings: GameSettings,
        timeSeconds: Float,
        playfield: Viewport,
    ) {
        with(scope) {
            if (size.width < 1f || size.height < 1f) return
            if (playfield.width < 1f || playfield.height < 1f) return
            ensureCache(size.width, size.height, density)
            computeTransform(world, playfield)
            bodyCorner = CornerRadius(wu(0.15f))

            val frac = clamp(alpha, 0f, 1f)
            val camX = world.camera.x

            // The void fills the entire screen so the reserved margins read as depth rather
            // than as black bars, but everything that belongs to the room is clipped to the
            // playfield — an unclipped grid would draw floor-looking lines in a region the
            // player can never reach, which is exactly the mistake the old camera clamp was
            // there to prevent.
            drawRect(brush = backdrop)
            clipRect(playfield.left, playfield.top, playfield.right, playfield.bottom) {
                drawGrid(camX, timeSeconds)
                if (!settings.reducedEffects) drawFog(camX, timeSeconds)

                drawTeleporters(world, timeSeconds)
                drawPlates(world, timeSeconds)
                drawLasers(world)
                drawMovers(world, frac)
                drawPlatforms(world)
                drawHazards(world)
                drawDoors(world)
                drawTurrets(world, timeSeconds)
                drawWalkers(world, frac)
                drawBullets(world)
                drawExit(world, timeSeconds)
                drawEchoes(world, frac, settings, timeSeconds)
                drawPlayer(world.player, frac)
                drawParticles(world, settings)
            }
            drawPlayfieldEdge(playfield, timeSeconds)

            vignette?.let { drawRect(brush = it) }
        }
    }

    // ---- Transform --------------------------------------------------------------------

    /**
     * Maps world units into the reserved [playfield] rectangle.
     *
     * The world used to be fitted to screen *height* and centred on the full canvas, which put
     * 4.4 units of level under the stick and 4.7 under the action arc — including the exit
     * portal on 22 of the 30 levels. [ControlLayout] now hands down a box that excludes the
     * controls and the HUD, and the whole room is fitted inside it.
     *
     * Because the fit is uniform and the box always receives all [GameConstants.WORLD_W] units,
     * there is no horizontal follow to do: every level is one screen by design. `camera.x` still
     * drives parallax, and shake is still applied here rather than to bodies, per Camera's
     * contract that the simulation never moves for a visual effect.
     */
    private fun DrawScope.computeTransform(world: WorldState, playfield: Viewport) {
        scale = playfield.width / GameConstants.WORLD_W
        originX = playfield.left
        originY = playfield.top

        // Shake is render-only, per Camera's contract: bodies are never moved.
        originX += world.camera.shakeOffsetX * scale
        originY += world.camera.shakeOffsetY * scale
    }

    /**
     * Draws the boundary of the room: a hairline edge, a breathing bloom just inside it, and
     * four corner brackets.
     *
     * Reserving margins for the controls means the world stops short of the screen, and an
     * unmarked stop looks like a rendering bug. Marking it turns the same pixels into a
     * containment field — the room is a chamber you are sealed inside for thirty seconds,
     * which is what the fiction says it is. The brackets pulse on the same clock as the exit
     * portal so the whole frame feels like one machine.
     */
    private fun DrawScope.drawPlayfieldEdge(playfield: Viewport, timeSeconds: Float) {
        val pulse = 0.5f + 0.5f * sin(timeSeconds * 1.1f)
        val topLeft = Offset(playfield.left, playfield.top)
        val size = Size(playfield.width, playfield.height)

        drawRect(
            color = Palette.gridLine.copy(alpha = 0.55f + 0.15f * pulse),
            topLeft = topLeft,
            size = size,
            style = Stroke(width = hairPx),
        )

        // Bracket length scales with the room so it reads the same on any device.
        val arm = playfield.width * 0.045f
        val corners = listOf(
            Triple(playfield.left, playfield.top, 1f to 1f),
            Triple(playfield.right, playfield.top, -1f to 1f),
            Triple(playfield.left, playfield.bottom, 1f to -1f),
            Triple(playfield.right, playfield.bottom, -1f to -1f),
        )
        val bracket = Palette.player.copy(alpha = 0.20f + 0.16f * pulse)
        for ((x, y, dir) in corners) {
            val (dx, dy) = dir
            drawLine(
                color = bracket,
                start = Offset(x, y),
                end = Offset(x + arm * dx, y),
                strokeWidth = edgePx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = bracket,
                start = Offset(x, y),
                end = Offset(x, y + arm * dy),
                strokeWidth = edgePx,
                cap = StrokeCap.Round,
            )
        }
    }

    private fun wx(worldX: Float): Float = originX + worldX * scale
    private fun wy(worldY: Float): Float = originY + worldY * scale

    private fun wu(units: Float): Float = units * scale

    // ---- Cache ------------------------------------------------------------------------

    private fun ensureCache(w: Float, h: Float, density: Float) {
        if (w == cachedW && h == cachedH && density == cachedDensity) return
        cachedW = w
        cachedH = h
        cachedDensity = density

        // Line weights in dp, so a neon edge is the same visual thickness on every screen.
        hairPx = max(1f, 1.0f * density)
        edgePx = max(1.2f, 1.7f * density)
        boldPx = max(1.8f, 2.6f * density)
        hairStroke = Stroke(width = hairPx)
        edgeStroke = Stroke(width = edgePx)
        boldStroke = Stroke(width = boldPx, cap = StrokeCap.Round)

        val unit = h / GameConstants.WORLD_H
        dashEffect = PathEffect.dashPathEffect(floatArrayOf(unit * 0.30f, unit * 0.26f), 0f)

        vignette = Brush.radialGradient(
            0.52f to Color.Transparent,
            1f to Palette.void.copy(alpha = 0.78f),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = max(w, h) * 0.62f,
        )

        for (i in 0 until FOG_LAYERS) {
            val radius = h * FOG_SIZE[i]
            fogRadii[i] = radius
            fogBrushes[i] = Brush.radialGradient(
                0f to Palette.fog.copy(alpha = FOG_ALPHA[i]),
                0.55f to Palette.fog.copy(alpha = FOG_ALPHA[i] * 0.34f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = radius,
            )
        }
    }

    // ---- Backdrop ---------------------------------------------------------------------

    /**
     * Two grid layers at different parallax depths. Depth, not density, is what makes the
     * void read as a space rather than a flat fill, so both layers stay near-invisible.
     */
    private fun DrawScope.drawGrid(camX: Float, time: Float) {
        gridLayer(spacing = 5f, parallax = 0.20f, camX = camX, drift = time * 0.05f, alpha = 0.60f)
        gridLayer(spacing = 2.5f, parallax = 0.42f, camX = camX, drift = time * 0.11f, alpha = 0.28f)
    }

    private fun DrawScope.gridLayer(
        spacing: Float,
        parallax: Float,
        camX: Float,
        drift: Float,
        alpha: Float,
    ) {
        val step = wu(spacing)
        if (step < 8f) return

        // Screen-space, not world-space: a backdrop layer moves at a *fraction* of the
        // camera's rate, which is the whole point of parallax.
        val phaseX = wrap(-camX * parallax + drift, spacing) * scale
        var x = phaseX - step
        while (x <= size.width) {
            drawLine(
                color = Palette.gridLine,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = hairPx,
                alpha = alpha,
            )
            x += step
        }

        val phaseY = wrap(drift * 0.5f, spacing) * scale
        var y = phaseY - step
        while (y <= size.height) {
            drawLine(
                color = Palette.gridLine,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = hairPx,
                alpha = alpha * 0.75f,
            )
            y += step
        }
    }

    /** Three slow radial washes. Cached brushes, positioned by canvas translation. */
    private fun DrawScope.drawFog(camX: Float, time: Float) {
        for (i in 0 until FOG_LAYERS) {
            val brush = fogBrushes[i] ?: continue
            val rate = FOG_RATE[i]
            val cx = size.width * (FOG_SEED_X[i] + 0.07f * sin(time * rate * TAU)) -
                camX * FOG_PARALLAX * scale
            val cy = size.height * (FOG_SEED_Y[i] + 0.05f * cos(time * rate * TAU * 0.75f))
            translate(cx, cy) {
                drawCircle(brush = brush, radius = fogRadii[i], center = Offset.Zero)
            }
        }
    }

    // ---- Puzzle furniture -------------------------------------------------------------

    private fun DrawScope.drawTeleporters(world: WorldState, time: Float) {
        for (i in world.teleporters.indices) {
            val tp: TeleporterState = world.teleporters[i]
            val ready = tp.cooldown <= 0f
            val pulse = 0.5f + 0.5f * sin(time * 1.9f)
            drawPad(tp.spec.a, ready, pulse)
            drawPad(tp.spec.b, ready, pulse)
        }
    }

    private fun DrawScope.drawPad(r: Rect, ready: Boolean, pulse: Float) {
        val glow = if (ready) 0.16f + 0.07f * pulse else 0.05f
        softGlow(
            cx = wx(r.centerX),
            cy = wy(r.y),
            radius = wu(max(r.w, 1.2f) * 0.9f),
            color = Palette.teleporter,
            peak = glow,
        )
        drawRect(
            color = Palette.teleporter,
            topLeft = Offset(wx(r.x), wy(r.y)),
            size = Size(wu(r.w), wu(r.h)),
            alpha = 0.10f,
        )
        drawLine(
            color = Palette.teleporter,
            start = Offset(wx(r.x), wy(r.y)),
            end = Offset(wx(r.right), wy(r.y)),
            strokeWidth = edgePx,
            alpha = if (ready) 0.85f + 0.15f * pulse else 0.30f,
        )
    }

    private fun DrawScope.drawPlates(world: WorldState, time: Float) {
        for (i in world.plates.indices) {
            val plate: PlateState = world.plates[i]
            val r = plate.spec.rect
            val on = plate.pressed || plate.latched
            // Sink deepens with the number of bodies standing on it, so a two-Echo hold reads
            // differently from a one-Echo hold at a glance.
            val sink = if (on) wu(0.05f + 0.022f * min(plate.contacts, 3)) else 0f
            val tint = if (on) Palette.plateOn else Palette.plateOff

            // Housing stays put; only the cap sinks.
            drawRect(
                color = Palette.platformFill,
                topLeft = Offset(wx(r.x), wy(r.y)),
                size = Size(wu(r.w), wu(r.h)),
            )
            val capTop = wy(r.y) + sink
            drawRect(
                color = tint,
                topLeft = Offset(wx(r.x), capTop),
                size = Size(wu(r.w), max(hairPx, wu(r.h) * 0.6f)),
                alpha = if (on) 0.22f else 0.10f,
            )
            drawLine(
                color = tint,
                start = Offset(wx(r.x), capTop),
                end = Offset(wx(r.right), capTop),
                strokeWidth = edgePx,
                alpha = if (on) 0.95f else 0.55f,
                // Echo-only plates are dashed: the affordance has to say "not for you" before
                // the player wastes a take standing on it.
                pathEffect = if (plate.spec.echoOnly) dashEffect else null,
            )
            if (plate.spec.latching) {
                // A second rule under the cap marks a permanent latch.
                drawLine(
                    color = tint,
                    start = Offset(wx(r.x + r.w * 0.15f), capTop + edgePx * 1.8f),
                    end = Offset(wx(r.right - r.w * 0.15f), capTop + edgePx * 1.8f),
                    strokeWidth = hairPx,
                    alpha = if (on) 0.6f else 0.3f,
                )
            }
            if (on) {
                softGlow(
                    cx = wx(r.centerX),
                    cy = capTop,
                    radius = wu(max(r.w, 1f) * 0.8f),
                    color = Palette.plateOn,
                    peak = 0.15f + 0.03f * sin(time * 3.1f),
                )
            }
        }
    }

    /**
     * Beams draw only while lethal; the emitter housings are always visible so a dark corridor
     * still tells the player where a beam *will* be. Duty-cycle state comes straight from
     * [LaserState.on] — the renderer never re-derives it.
     */
    private fun DrawScope.drawLasers(world: WorldState) {
        for (i in world.lasers.indices) {
            val laser: LaserState = world.lasers[i]
            val r = laser.spec.rect
            val horizontal = r.w >= r.h

            if (laser.on) {
                // Outer bloom: expand across the beam only, so a thin beam stays thin.
                for (k in 3 downTo 1) {
                    val grow = wu(0.09f * k)
                    val acrossX = if (horizontal) 0f else grow
                    val acrossY = if (horizontal) grow else 0f
                    drawRect(
                        color = Palette.laser,
                        topLeft = Offset(wx(r.x) - acrossX, wy(r.y) - acrossY),
                        size = Size(wu(r.w) + acrossX * 2f, wu(r.h) + acrossY * 2f),
                        alpha = 0.05f / k + 0.02f,
                    )
                }
                drawRect(
                    color = Palette.laser,
                    topLeft = Offset(wx(r.x), wy(r.y)),
                    size = Size(wu(r.w), wu(r.h)),
                    alpha = 0.45f,
                )
                // White-hot centre line: the beam is light, so its core is not amber.
                if (horizontal) {
                    val cy = wy(r.centerY)
                    drawLine(
                        color = Palette.platformEdge,
                        start = Offset(wx(r.x), cy),
                        end = Offset(wx(r.right), cy),
                        strokeWidth = hairPx,
                        alpha = 0.9f,
                    )
                } else {
                    val cx = wx(r.centerX)
                    drawLine(
                        color = Palette.platformEdge,
                        start = Offset(cx, wy(r.y)),
                        end = Offset(cx, wy(r.bottom)),
                        strokeWidth = hairPx,
                        alpha = 0.9f,
                    )
                }
            }

            val emitterAlpha = if (laser.on) 0.95f else 0.35f
            if (horizontal) {
                drawEmitter(r.x, r.centerY, emitterAlpha)
                drawEmitter(r.right, r.centerY, emitterAlpha)
            } else {
                drawEmitter(r.centerX, r.y, emitterAlpha)
                drawEmitter(r.centerX, r.bottom, emitterAlpha)
            }
        }
    }

    private fun DrawScope.drawEmitter(worldX: Float, worldY: Float, alpha: Float) {
        val half = wu(0.22f)
        drawRect(
            color = housing,
            topLeft = Offset(wx(worldX) - half, wy(worldY) - half),
            size = Size(half * 2f, half * 2f),
        )
        drawRect(
            color = Palette.laser,
            topLeft = Offset(wx(worldX) - half, wy(worldY) - half),
            size = Size(half * 2f, half * 2f),
            alpha = alpha,
            style = hairStroke,
        )
    }

    private fun DrawScope.drawMovers(world: WorldState, frac: Float) {
        for (i in world.movers.indices) {
            val mover: MoverState = world.movers[i]
            val x = mover.renderX(frac)
            val y = mover.renderY(frac)
            val w = mover.spec.size.w
            val h = mover.spec.size.h
            slab(x, y, w, h, fillAlpha = 1f, edgeAlpha = 1f)

            // Two lit struts under the ends read as machinery rather than terrain, and brighten
            // while the platform is actually travelling.
            val moving = mover.dx != 0f || mover.dy != 0f
            val strut = if (moving) 0.7f else 0.28f
            val bottom = wy(y + h)
            val inset = wu(min(0.25f, w * 0.2f))
            drawLine(
                color = Palette.platformEdge,
                start = Offset(wx(x) + inset, bottom),
                end = Offset(wx(x) + inset, bottom + wu(0.18f)),
                strokeWidth = hairPx,
                alpha = strut,
            )
            drawLine(
                color = Palette.platformEdge,
                start = Offset(wx(x + w) - inset, bottom),
                end = Offset(wx(x + w) - inset, bottom + wu(0.18f)),
                strokeWidth = hairPx,
                alpha = strut,
            )
        }
    }

    private fun DrawScope.drawPlatforms(world: WorldState) {
        val platforms = world.level.platforms
        for (i in platforms.indices) {
            val p = platforms[i]
            val r = p.rect
            when (p.kind) {
                PlatformKind.SOLID -> slab(r.x, r.y, r.w, r.h, fillAlpha = 1f, edgeAlpha = 1f)
                PlatformKind.DECOR -> {
                    // Identical physics, dimmer edge: scenery should not read as a route.
                    slab(r.x, r.y, r.w, r.h, fillAlpha = 1f, edgeAlpha = 0.4f)
                    drawLine(
                        color = Palette.platformEdge,
                        start = Offset(wx(r.x), wy(r.y + r.h * 0.5f)),
                        end = Offset(wx(r.right), wy(r.y + r.h * 0.5f)),
                        strokeWidth = hairPx,
                        alpha = 0.08f,
                    )
                }
                PlatformKind.ONE_WAY -> {
                    // Barely-there body plus a dashed lip: the dash is the affordance for
                    // "you can come up through this".
                    drawRect(
                        color = Palette.platformFill,
                        topLeft = Offset(wx(r.x), wy(r.y)),
                        size = Size(wu(r.w), wu(r.h)),
                        alpha = 0.35f,
                    )
                    drawLine(
                        color = Palette.platformEdge,
                        start = Offset(wx(r.x), wy(r.y)),
                        end = Offset(wx(r.right), wy(r.y)),
                        strokeWidth = edgePx,
                        alpha = 0.75f,
                        pathEffect = dashEffect,
                    )
                }
            }
        }
    }

    /**
     * Dark slab with one bright top edge, plus two decaying lines below it that fake the light
     * bleeding onto the slab face. Three `drawLine` calls cost less than a per-platform
     * gradient brush and read the same at this scale.
     */
    private fun DrawScope.slab(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fillAlpha: Float,
        edgeAlpha: Float,
    ) {
        if (w <= 0f || h <= 0f) return
        val left = wx(x)
        val right = wx(x + w)
        val top = wy(y)
        drawRect(
            color = Palette.platformFill,
            topLeft = Offset(left, top),
            size = Size(wu(w), wu(h)),
            alpha = fillAlpha,
        )
        drawLine(
            color = Palette.platformEdge,
            start = Offset(left, top),
            end = Offset(right, top),
            strokeWidth = edgePx,
            alpha = edgeAlpha,
        )
        drawLine(
            color = Palette.platformEdge,
            start = Offset(left, top + edgePx * 1.4f),
            end = Offset(right, top + edgePx * 1.4f),
            strokeWidth = hairPx,
            alpha = edgeAlpha * 0.22f,
        )
        drawLine(
            color = Palette.platformEdge,
            start = Offset(left, top + edgePx * 2.6f),
            end = Offset(right, top + edgePx * 2.6f),
            strokeWidth = hairPx,
            alpha = edgeAlpha * 0.09f,
        )
    }

    private fun DrawScope.drawHazards(world: WorldState) {
        val hazards = world.level.hazards
        for (i in hazards.indices) {
            val hazard: HazardSpec = hazards[i]
            val r = hazard.rect
            when (hazard.kind) {
                HazardKind.SPIKE -> teeth(r, pointUp = true)
                HazardKind.CRUSHER -> {
                    slab(r.x, r.y, r.w, r.h, fillAlpha = 1f, edgeAlpha = 0.35f)
                    teeth(r, pointUp = false)
                }
                HazardKind.PIT -> {
                    // No teeth: a pit is an absence. Mark the threshold and fade downward.
                    drawRect(
                        color = Palette.void,
                        topLeft = Offset(wx(r.x), wy(r.y)),
                        size = Size(wu(r.w), wu(r.h)),
                        alpha = 0.9f,
                    )
                    for (k in 0 until 3) {
                        drawLine(
                            color = Palette.hazard,
                            start = Offset(wx(r.x), wy(r.y) + k * edgePx * 1.6f),
                            end = Offset(wx(r.right), wy(r.y) + k * edgePx * 1.6f),
                            strokeWidth = if (k == 0) edgePx else hairPx,
                            alpha = 0.5f / (k + 1),
                            pathEffect = dashEffect,
                        )
                    }
                }
            }
        }
    }

    /**
     * A row of triangles built into one [Path] — dark fill, amber outline. Outlining rather
     * than filling with amber is deliberate: a filled row of saturated triangles would be the
     * brightest thing on screen, and the player must be the brightest thing on screen.
     */
    private fun DrawScope.teeth(r: Rect, pointUp: Boolean) {
        val toothW = 0.5f
        val count = max(1, (r.w / toothW).toInt())
        val step = r.w / count
        val height = min(if (pointUp) r.h else r.h * 0.5f, 0.55f)
        val baseY = if (pointUp) r.bottom else r.y
        val apexY = if (pointUp) r.bottom - height else r.y + height

        hazardPath.reset()
        for (k in 0 until count) {
            val x0 = r.x + k * step
            hazardPath.moveTo(wx(x0), wy(baseY))
            hazardPath.lineTo(wx(x0 + step * 0.5f), wy(apexY))
            hazardPath.lineTo(wx(x0 + step), wy(baseY))
            hazardPath.close()
        }
        drawPath(path = hazardPath, color = bodyCore, alpha = 0.95f)
        drawPath(path = hazardPath, color = Palette.hazard, alpha = 0.85f, style = hairStroke)
        drawLine(
            color = Palette.hazard,
            start = Offset(wx(r.x), wy(baseY)),
            end = Offset(wx(r.right), wy(baseY)),
            strokeWidth = hairPx,
            alpha = 0.5f,
        )
    }

    /**
     * Doors retract into their frame: drawn height is `rect.h * (1 - openAmount)`, anchored at
     * the top. The frame outline stays visible when fully open so the player can still read
     * where a door will reappear.
     */
    private fun DrawScope.drawDoors(world: WorldState) {
        for (i in world.doors.indices) {
            val door: DoorState = world.doors[i]
            val r = door.spec.rect
            val frameLeft = wx(r.x)
            val frameRight = wx(r.right)
            drawLine(
                color = Palette.platformEdge,
                start = Offset(frameLeft, wy(r.y)),
                end = Offset(frameLeft, wy(r.bottom)),
                strokeWidth = hairPx,
                alpha = 0.20f,
            )
            drawLine(
                color = Palette.platformEdge,
                start = Offset(frameRight, wy(r.y)),
                end = Offset(frameRight, wy(r.bottom)),
                strokeWidth = hairPx,
                alpha = 0.20f,
            )

            val h = r.h * (1f - clamp(door.openAmount, 0f, 1f))
            if (h <= 0.01f) continue
            drawRect(
                color = Palette.platformFill,
                topLeft = Offset(frameLeft, wy(r.y)),
                size = Size(wu(r.w), wu(h)),
            )
            val lip = wy(r.y + h)
            drawLine(
                color = Palette.platformEdge,
                start = Offset(frameLeft, lip),
                end = Offset(frameRight, lip),
                strokeWidth = edgePx,
                alpha = if (door.solid) 0.9f else 0.45f,
            )
        }
    }

    private fun DrawScope.drawTurrets(world: WorldState, time: Float) {
        for (i in world.turrets.indices) {
            val turret: TurretState = world.turrets[i]
            val r = turret.spec.rect
            // `evaluate(null)` means "always on" for opens-when style conditions, so a turret
            // with no disable condition must be tested for null explicitly rather than handed
            // straight to evaluate.
            val offline = turret.spec.disabledWhen != null && world.evaluate(turret.spec.disabledWhen)

            drawRect(
                color = housing,
                topLeft = Offset(wx(r.x), wy(r.y)),
                size = Size(wu(r.w), wu(r.h)),
            )
            drawRect(
                color = Palette.enemy,
                topLeft = Offset(wx(r.x), wy(r.y)),
                size = Size(wu(r.w), wu(r.h)),
                alpha = if (offline) 0.18f else 0.55f,
                style = hairStroke,
            )
            if (offline) continue

            val facingLeft = turret.spec.dirX < 0
            val muzzleX = if (facingLeft) r.x else r.right
            val muzzleW = wu(0.22f)
            val hot = turret.alerted
            val muzzleLeft = if (facingLeft) wx(muzzleX) - muzzleW else wx(muzzleX)
            drawRect(
                color = Palette.laser,
                topLeft = Offset(muzzleLeft, wy(r.centerY) - muzzleW * 0.5f),
                size = Size(muzzleW, muzzleW),
                alpha = if (hot) 0.95f else 0.5f,
            )
            if (hot) {
                softGlow(
                    cx = wx(muzzleX),
                    cy = wy(r.centerY),
                    radius = wu(0.9f),
                    color = Palette.enemy,
                    peak = 0.20f + 0.06f * sin(time * 9f),
                )
            }
        }
    }

    private fun DrawScope.drawWalkers(world: WorldState, frac: Float) {
        for (i in world.walkers.indices) {
            val walker: WalkerState = world.walkers[i]
            val x = walker.renderX(frac)
            val y = walker.renderY(frac)
            val left = wx(x)
            val top = wy(y)
            val w = wu(walker.w)
            val h = wu(walker.h)

            drawRoundRect(
                color = bodyCore,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = bodyCorner,
            )
            drawRoundRect(
                color = Palette.enemy,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = bodyCorner,
                style = edgeStroke,
                alpha = if (walker.alerted) 1f else 0.7f,
            )

            // Scanner slit on the facing side.
            val slitY = top + h * 0.3f
            val slitLen = w * 0.42f
            val slitStart = if (walker.dir >= 0) left + w - slitLen - hairPx else left + hairPx
            drawLine(
                color = if (walker.alerted) Palette.laser else Palette.enemy,
                start = Offset(slitStart, slitY),
                end = Offset(slitStart + slitLen, slitY),
                strokeWidth = edgePx,
                alpha = if (walker.alerted) 1f else 0.6f,
            )

            if (walker.alerted) {
                // Two short ticks above the head: legible at a glance, no icon, no text.
                val markY = top - wu(0.3f)
                val markW = w * 0.22f
                drawLine(
                    color = Palette.hazard,
                    start = Offset(left + w * 0.5f - markW * 1.6f, markY),
                    end = Offset(left + w * 0.5f - markW * 0.4f, markY),
                    strokeWidth = edgePx,
                    alpha = 0.9f,
                )
                drawLine(
                    color = Palette.hazard,
                    start = Offset(left + w * 0.5f + markW * 0.4f, markY),
                    end = Offset(left + w * 0.5f + markW * 1.6f, markY),
                    strokeWidth = edgePx,
                    alpha = 0.9f,
                )
                softGlow(
                    cx = left + w * 0.5f,
                    cy = top + h * 0.5f,
                    radius = wu(1.3f),
                    color = Palette.enemy,
                    peak = 0.14f,
                )
            }
        }
    }

    private fun DrawScope.drawBullets(world: WorldState) {
        val pool = world.bullets
        val w = wu(GameConstants.BULLET_W)
        val h = max(hairPx * 1.5f, wu(GameConstants.BULLET_H))
        for (i in 0 until pool.size) {
            if (!pool.alive[i]) continue
            val left = wx(pool.x[i])
            val top = wy(pool.y[i])
            val cy = top + h * 0.5f
            // Streak behind the bullet sells the speed without a particle per tick.
            val tailDir = if (pool.vx[i] < 0f) 1f else -1f
            drawLine(
                color = Palette.bullet,
                start = Offset(left + w * 0.5f, cy),
                end = Offset(left + w * 0.5f + tailDir * w * 2.4f, cy),
                strokeWidth = hairPx,
                alpha = 0.35f,
            )
            drawRect(
                color = Palette.bullet,
                topLeft = Offset(left, top),
                size = Size(w, h),
                alpha = 0.95f,
            )
        }
    }

    /**
     * The exit. Sealed it is a dim outline; once [WorldState.evaluate] passes on the exit
     * condition it gains a violet wash, a pulsing arch and rising interior shimmer — the
     * player must be able to tell "the way out is open now" from across the room, because that
     * is the payoff of the whole choreography.
     */
    private fun DrawScope.drawExit(world: WorldState, time: Float) {
        val r = world.level.exit
        val open = world.evaluate(world.level.exitCondition)
        val pulse = 0.5f + 0.5f * sin(time * (if (open) 2.1f else 0.8f))

        softGlow(
            cx = wx(r.centerX),
            cy = wy(r.centerY),
            radius = wu(max(r.w, r.h) * (1.0f + 0.18f * pulse)),
            color = Palette.exit,
            peak = if (open) 0.20f + 0.06f * pulse else 0.05f,
        )
        drawRoundRect(
            color = Palette.exit,
            topLeft = Offset(wx(r.x), wy(r.y)),
            size = Size(wu(r.w), wu(r.h)),
            cornerRadius = CornerRadius(wu(0.3f)),
            alpha = if (open) 0.12f else 0.04f,
        )
        drawRoundRect(
            color = Palette.exit,
            topLeft = Offset(wx(r.x), wy(r.y)),
            size = Size(wu(r.w), wu(r.h)),
            cornerRadius = CornerRadius(wu(0.3f)),
            style = if (open) boldStroke else hairStroke,
            alpha = if (open) 0.7f + 0.25f * pulse else 0.28f,
        )

        if (!open) return
        clipRect(left = wx(r.x), top = wy(r.y), right = wx(r.right), bottom = wy(r.bottom)) {
            for (k in 0 until 3) {
                val p = wrap(time * 0.35f + k * 0.34f, 1f)
                val y = wy(r.bottom) - wu(r.h) * p
                drawLine(
                    color = Palette.exit,
                    start = Offset(wx(r.x), y),
                    end = Offset(wx(r.right), y),
                    strokeWidth = hairPx,
                    alpha = 0.35f * (1f - p),
                )
            }
        }
    }

    // ---- Bodies -----------------------------------------------------------------------

    /**
     * Echoes. This is the game's signature image, so it is built from four cues that all say
     * *recording* rather than *second player*: a translucent body, a trail sampled from the
     * Echo's own past ticks, a horizontal distortion wobble, and drifting scan bands. Hue comes
     * from [Palette.echo] so four simultaneous timelines stay tellable apart.
     *
     * An exhausted Echo (holding its final pose) loses the wobble and the bands and dims —
     * "spent" has to look different from "still doing something", or the player cannot tell
     * whether the plate is still held.
     */
    private fun DrawScope.drawEchoes(
        world: WorldState,
        frac: Float,
        settings: GameSettings,
        time: Float,
    ) {
        for (i in world.echoes.indices) {
            val echo: EchoBody = world.echoes[i]
            val hue = if (settings.colorBlindSafe) Palette.echoSafe(echo.index) else Palette.echo(echo.index)
            val live = echo.active
            val wobble = if (live) sin(time * 5.3f + echo.index * 1.9f) * 0.032f else 0f
            val dim = if (live) 1f else 0.55f

            if (settings.showEchoTrails && live) {
                for (k in 1..3) {
                    val back = echo.tick - k * 2
                    val tx = echo.replay.renderX(back, frac) + wobble * (1f - k * 0.22f)
                    val ty = echo.replay.renderY(back, frac)
                    shapeBody(tx, ty, echo.replay.vy(back), echo.replay.animAt(back))
                    drawRoundRect(
                        color = hue,
                        topLeft = Offset(wx(bx), wy(by)),
                        size = Size(wu(bw), wu(bh)),
                        cornerRadius = bodyCorner,
                        alpha = 0.13f / k,
                    )
                }
            }

            val ex = echo.renderX(frac) + wobble
            val ey = echo.renderY(frac)
            val vy = echo.replay.vy(echo.tick)
            shapeBody(ex, ey, vy, echo.anim)

            // In colour-blind-safe mode each timeline also gets its own outline pattern, so the
            // identity of an Echo survives greyscale and every form of colour vision deficiency.
            if (settings.colorBlindSafe) {
                val dash = Palette.echoDashPattern(echo.index)
                drawRoundRect(
                    color = hue,
                    topLeft = Offset(wx(bx), wy(by)),
                    size = Size(wu(bw), wu(bh)),
                    cornerRadius = bodyCorner,
                    alpha = 0.95f * dim,
                    style = Stroke(
                        width = boldPx,
                        pathEffect = dash?.let {
                            PathEffect.dashPathEffect(FloatArray(it.size) { i -> wu(it[i]) }, 0f)
                        },
                    ),
                )
            }

            softGlow(
                cx = wx(bx + bw * 0.5f),
                cy = wy(by + bh * 0.5f),
                radius = wu(1.35f),
                color = hue,
                peak = 0.13f * dim,
            )
            drawRoundRect(
                color = hue,
                topLeft = Offset(wx(bx), wy(by)),
                size = Size(wu(bw), wu(bh)),
                cornerRadius = bodyCorner,
                alpha = 0.35f * dim,
            )
            drawRoundRect(
                color = hue,
                topLeft = Offset(wx(bx), wy(by)),
                size = Size(wu(bw), wu(bh)),
                cornerRadius = bodyCorner,
                style = hairStroke,
                alpha = 0.9f * dim,
            )
            if (live) scanBands(hue, time, echo.index)
            facingTick(hue, echo.facingRight, 0.8f * dim)
        }
    }

    /** Two bands drifting down the body: one bright, one a dropout. Reads as playback. */
    private fun DrawScope.scanBands(hue: Color, time: Float, index: Int) {
        val left = wx(bx)
        val right = wx(bx + bw)
        val top = wy(by)
        val height = wu(bh)
        clipRect(left = left, top = top, right = right, bottom = top + height) {
            val a = top + height * wrap(time * 0.5f + index * 0.31f, 1f)
            drawLine(
                color = hue,
                start = Offset(left, a),
                end = Offset(right, a),
                strokeWidth = hairPx,
                alpha = 0.55f,
            )
            val b = top + height * wrap(time * 0.5f + index * 0.31f + 0.5f, 1f)
            drawLine(
                color = bodyCore,
                start = Offset(left, b),
                end = Offset(right, b),
                strokeWidth = hairPx * 1.5f,
                alpha = 0.5f,
            )
        }
    }

    /**
     * The player: dark core, bright cyan outline, soft halo. The only fully opaque body on
     * screen, which is what keeps "which one is me" answerable in a four-Echo frame.
     */
    private fun DrawScope.drawPlayer(player: PlayerBody, frac: Float) {
        val px = player.renderX(frac)
        val py = player.renderY(frac)
        shapeBody(px, py, player.vy, player.anim)
        val alive = player.alive

        if (alive) {
            softGlow(
                cx = wx(bx + bw * 0.5f),
                cy = wy(by + bh * 0.5f),
                radius = wu(if (player.anim == AnimState.DASH) 2.0f else 1.5f),
                color = Palette.playerGlow,
                peak = if (player.anim == AnimState.DASH) 0.26f else 0.18f,
            )
        }
        drawRoundRect(
            color = bodyCore,
            topLeft = Offset(wx(bx), wy(by)),
            size = Size(wu(bw), wu(bh)),
            cornerRadius = bodyCorner,
            alpha = if (alive) 1f else 0.5f,
        )
        drawRoundRect(
            color = Palette.player,
            topLeft = Offset(wx(bx), wy(by)),
            size = Size(wu(bw), wu(bh)),
            cornerRadius = bodyCorner,
            style = edgeStroke,
            alpha = if (alive) 1f else 0.35f,
        )
        facingTick(Palette.playerGlow, player.facingRight, if (alive) 1f else 0.3f)
    }

    /** Short bright mark on the leading edge — the minimum needed to read facing. */
    private fun DrawScope.facingTick(color: Color, facingRight: Boolean, alpha: Float) {
        val y = wy(by + bh * 0.28f)
        val len = wu(bw * 0.34f)
        val x = if (facingRight) wx(bx + bw) - len - hairPx else wx(bx) + hairPx
        drawLine(
            color = color,
            start = Offset(x, y),
            end = Offset(x + len, y),
            strokeWidth = edgePx,
            alpha = alpha,
        )
    }

    /**
     * Writes the squash/stretch-adjusted body box into the scratch fields.
     *
     * Vertical velocity drives it — taller while rising, flatter while falling hard — and the
     * dash flattens and widens. The result is clamped hard: a 1.55-unit body has to keep
     * looking like the same character, and the feet stay planted (`by` is derived from the
     * bottom edge) so a stretched player never appears to sink into the floor it is standing
     * on. No allocation, because this runs for the player plus every Echo plus every trail
     * sample, every frame.
     */
    private fun shapeBody(x: Float, y: Float, vy: Float, anim: AnimState) {
        val lift = clamp(-vy / GameConstants.TERMINAL_VY, -1f, 1f)
        var sw = 1f - lift * 0.10f
        var sh = 1f + lift * 0.14f
        if (anim == AnimState.DASH) {
            sw *= 1.14f
            sh *= 0.9f
        }
        val w = GameConstants.PLAYER_W * clamp(sw, 0.85f, 1.18f)
        val h = GameConstants.PLAYER_H * clamp(sh, 0.85f, 1.18f)
        bw = w
        bh = h
        bx = x + (GameConstants.PLAYER_W - w) * 0.5f
        by = y + (GameConstants.PLAYER_H - h)
    }

    // ---- Particles and shared effects -------------------------------------------------

    private fun DrawScope.drawParticles(world: WorldState, settings: GameSettings) {
        val pool = world.particles
        // Reduced effects thins the pool by index rather than capping it, so the *mix* of
        // effects survives instead of the oldest emitter winning.
        val step = if (settings.reducedEffects) 2 else 1
        var i = 0
        while (i < pool.capacityCount) {
            val life = pool.life[i]
            if (life > 0f) {
                val maxLife = pool.maxLife[i]
                val t = if (maxLife > 0f) clamp(life / maxLife, 0f, 1f) else 0f
                drawCircle(
                    color = Palette.particleTint(pool.tint[i]),
                    radius = max(hairPx * 0.6f, wu(pool.size[i]) * (0.45f + 0.55f * t)),
                    center = Offset(wx(pool.x[i]), wy(pool.y[i])),
                    alpha = t * 0.85f,
                )
            }
            i += step
        }
    }

    /**
     * Soft radial wash built from concentric circles instead of a gradient brush.
     *
     * A `Brush.radialGradient` is an object, and a glow follows a moving body, so a brush per
     * glow would be an allocation per entity per frame. Five composited circles are visually
     * indistinguishable at these radii and cost nothing but fill rate.
     */
    private fun DrawScope.softGlow(
        cx: Float,
        cy: Float,
        radius: Float,
        color: Color,
        peak: Float,
        layers: Int = 5,
    ) {
        if (radius <= 1f || peak <= 0.002f) return
        for (i in layers downTo 1) {
            val f = i / layers.toFloat()
            val fade = 1f - f
            drawCircle(
                color = color,
                radius = radius * f,
                center = Offset(cx, cy),
                alpha = min(1f, peak * (fade * fade + 0.06f)),
            )
        }
    }

    private companion object {
        const val TAU = 6.2831855f

        const val FOG_LAYERS = 3

        /** Fractions of the viewport: seed positions, drift rates, alphas and radii. */
        val FOG_SEED_X = floatArrayOf(0.24f, 0.62f, 0.86f)
        val FOG_SEED_Y = floatArrayOf(0.34f, 0.68f, 0.22f)
        val FOG_RATE = floatArrayOf(0.031f, 0.019f, 0.043f)
        val FOG_ALPHA = floatArrayOf(0.20f, 0.14f, 0.10f)
        val FOG_SIZE = floatArrayOf(0.62f, 0.85f, 0.45f)

        /** Fog sits closer than the grid but far behind the level. */
        const val FOG_PARALLAX = 0.06f

        /** Positive modulo, for scrolling phases. Kotlin's `%` keeps the dividend's sign. */
        fun wrap(value: Float, period: Float): Float {
            val m = value % period
            return if (m < 0f) m + period else m
        }
    }
}
