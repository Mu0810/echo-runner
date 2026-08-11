# Echo Runner — parallel build brief

You are implementing ONE module of this game. Find your section below.

## Common rules — apply to every module

Project root: `/Users/hello/.kiro/crew/workspace/echo-runner`

**Read these first. They are the FROZEN API. Do not modify them:**

```
app/src/main/java/com/echorunner/core/Geometry.kt      Vec2, Rect, overlaps, clamp, lerp, approach, damp, rectDistance
app/src/main/java/com/echorunner/core/GameConstants.kt all tuning + timestep
app/src/main/java/com/echorunner/core/InputState.kt    InputState, InputBuffer
app/src/main/java/com/echorunner/core/EchoFrame.kt     AnimState, EchoFlags, EchoRecording, EchoReplay
app/src/main/java/com/echorunner/core/LevelData.kt     LevelSpec + every *Spec
app/src/main/java/com/echorunner/core/WorldState.kt    WorldState, PlayerBody, EchoBody, all *State, pools, Camera
app/src/main/java/com/echorunner/core/Contracts.kt     SoundCue, AudioController, Haptics, progress types, ChallengeModifier
```

**Hard rules:**

- Create ONLY the files listed in your section. Do not create or modify any other file. Never touch `core/`.
- **Do NOT run `gradle` or `./gradlew`.** Eight other agents are working in this same
  project directory right now; concurrent Gradle builds corrupt the build directory. The
  parent agent compiles centrally and fixes any errors.
- Kotlin only. No third-party dependencies. Available: Kotlin stdlib, Android framework,
  AndroidX Compose, kotlinx-coroutines, `org.json`. minSdk 26.
- **The world is y-DOWN**: y=0 is the top, y=22.5 the bottom, gravity is **positive** y, a
  jump is **negative** y. Units are world units (~1 unit ≈ 1 tile), never pixels.
- Deterministic simulation: no `Math.random`, no wall-clock time in simulation code. Seeded
  `java.util.Random` only where a section explicitly says so.
- No allocation inside per-tick or per-frame loops. Iterate existing lists with indexed
  `for` loops; use the pooled arrays already in `WorldState`. An object allocated per entity
  per tick is exactly what causes GC hitches on mid-range phones.
- Production-quality real logic. No TODOs, no stubs, no placeholder bodies.
- Concise KDoc on public functions, explaining *why* where the reason is not obvious.

**Game concept you need in order to build a coherent module:** the player gets a
30-second "take". When it ends, everything they did replays forever as a translucent
"Echo" while they play a fresh 30 seconds alongside it. The world **resets** at the start
of every take and all Echoes replay from tick 0 in lockstep. Echoes are **kinematic
ghosts**: they never collide with geometry, but they *do* press plates, flip switches,
block bullets and attract enemies. A puzzle is solved by choreographing several
cooperating 30-second performances.

Player metrics: 0.8 × 1.55 units, runs 9.2 u/s, jumps ~3.6 units high, dashes 27 u/s for
0.16 s. A platform's **top edge is its `y`**, so a standing player's y is `platformY - 1.55`.

**Canonical tick order** (the parent's orchestrator calls systems in exactly this order —
write your functions so they are correct in this position):

1. sample input → 2. resolveSignals → 3. updateMovers → 4. updateDoors →
5. PlayerController.update (which ends by calling Physics.movePlayer) →
6. EchoManager.advance → 7. updatePlates → 8. updateSwitches → 9. updateLasers →
10. updateTurrets → 11. updateBullets → 12. updateWalkers → 13. updateTeleporters →
14. death checks → 15. exit check → 16. particles/camera → 17. record player frame

**Report when done:** files created, and the exact public signatures you exposed.

---

## Module A — Physics + Player controller

Create exactly:

### `app/src/main/java/com/echorunner/engine/Physics.kt` (`package com.echorunner.engine`)

```kotlin
object Physics {
    fun movePlayer(world: WorldState, dt: Float)
    fun solidOverlap(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean
    fun groundedCheck(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean
}
```

- Axis-separated swept AABB: move X, resolve X, then move Y, resolve Y. This is what
  prevents corner-snagging.
- Collide against: `world.level.platforms` with kind `SOLID` or `DECOR` (both fully solid);
  `world.doors` where `DoorState.solid` (use `door.spec.rect`); `world.movers` (current rect
  is `MoverState.x/y` + `spec.size.w/h`).
- `PlatformKind.ONE_WAY`: collide **only** when moving downward (`vy > 0`) and the player's
  **previous** bottom edge was at or above the platform's top edge — use `player.prevY`.
  Otherwise pass straight through.
- Substep motion when `|v * dt|` exceeds ~0.4 units in a tick so a dash cannot tunnel
  through thin geometry. Cap substeps at 8.
- Carried motion: if standing on a mover, add that mover's per-tick `dx`/`dy` to the player
  position *before* integrating velocity, and set `player.carrierId`. Re-check each tick;
  clear `carrierId` when no longer riding.
- Set `player.onGround`. Clamp x inside `0..WORLD_W`. Do **not** clamp the bottom — falling
  past `WORLD_H` is a pit death handled elsewhere.
- Write `player.prevX/prevY` at the **start** of `movePlayer`, since rendering interpolates
  prev → current.

### `app/src/main/java/com/echorunner/engine/PlayerController.kt` (`package com.echorunner.engine`)

```kotlin
object PlayerController {
    fun update(
        world: WorldState, input: InputState, prevInput: InputState,
        dt: Float, audio: AudioController, modifier: ChallengeModifier,
    )
}
```

Use only tuning values from `GameConstants`; do not invent magic numbers.

- Derive press **edges** by comparing `input` to `prevInput` (jump pressed =
  `input.jumpHeld && !prevInput.jumpHeld`). Same for dash and interact.
- Horizontal: accelerate toward `input.moveX * MOVE_SPEED` with `GROUND_ACCEL`/`AIR_ACCEL`;
  apply `GROUND_FRICTION`/`AIR_FRICTION` when input is neutral. Use `approach` from Geometry.
- Gravity `GRAVITY`, or `GRAVITY_JUMP_HOLD` while `vy < 0` and jump held (floatier rise).
  Clamp to `TERMINAL_VY`.
- Jump: coyote time (`COYOTE_SECONDS`) **and** jump buffering (`JUMP_BUFFER_SECONDS`).
  Variable height: on jump *release* while `vy < 0`, multiply `vy` by `JUMP_CUT_FACTOR`.
  Respect `modifier.jumpDisabled` (jump does nothing at all).
- Dash: `DASH_SPEED` for `DASH_SECONDS`, then `DASH_COOLDOWN_SECONDS`. Horizontal, in the
  input direction if non-zero else facing. Gravity suspended and `vy` forced to 0 during the
  dash so it reads as a clean burst. If `DASH_REFUND_ON_LAND`, clear the cooldown on landing.
  Respect `modifier.dashBudget` via `player.dashesUsed`.
- Set `facingRight`, `startedJump`, `startedDash`, `interacting` every tick — `startedX` true
  **only** on the tick the action begins.
- Set `player.anim`: `DASH` while dashing, `RISE` when `vy<0`, `FALL` when airborne and
  `vy>0`, `RUN` when grounded and moving, else `IDLE`.
- Audio: `JUMP` on jump, `DASH` on dash, `LAND` on the airborne→grounded transition, and
  `FOOTSTEP` on a cadence while running on the ground (~every 0.28 s). Keep the footstep
  accumulator in a `private var` on the object and reset it when not grounded.
- Emit jump/dash/land particles via `world.particles.emit(...)` with tint indices 0..3.
- Call `Physics.movePlayer` at the **end** of `update`.

---

## Module B — Echo recording + replay lifecycle

Read the long doc comment on `EchoRecording` in `EchoFrame.kt` explaining why this records
resolved **state** rather than input, and honour it: Echoes never run physics.

Create exactly:

### `app/src/main/java/com/echorunner/echo/EchoRecorder.kt` (`package com.echorunner.echo`)

```kotlin
class EchoRecorder {
    val ticks: Int
    fun beginTake()
    fun record(world: WorldState)
    fun finish(index: Int): EchoReplay
    fun discard()
}
```

Wraps ONE reusable `EchoRecording`, allocated in the constructor, never per take.
`record` appends one frame from `world.player`: x, y, vx, vy, startedJump, startedDash,
interacting, onGround, facingRight, anim.

### `app/src/main/java/com/echorunner/echo/EchoManager.kt` (`package com.echorunner.echo`)

```kotlin
object EchoManager {
    fun advance(world: WorldState, audio: AudioController)
    fun materialize(world: WorldState, replay: EchoReplay, audio: AudioController)
    fun rewindAll(world: WorldState)
}
```

- `advance`: for each `EchoBody`, store prev position, increment `tick`, read
  x/y/facing/anim from its replay. When `tick` passes `replay.length-1` the body holds its
  final pose and `active` goes false — play `SoundCue.ECHO_EXPIRE` **exactly once** on that
  transition, not every tick after.
- While active, emit a faint trail particle roughly every 3rd tick via
  `world.particles.emit`, tint derived from the Echo's index.
- Play `SoundCue.DASH` at volume ~0.35 when `replay.startedDash(tick)`, and `SoundCue.JUMP`
  quietly on `startedJump(tick)`, so Echoes are audible without drowning the player.
- `materialize`: append a new `EchoBody`, rewind it, play `SoundCue.ECHO_CREATED`, and
  `world.camera.kick(...)` for a physical beat.

---

## Module C — Puzzle object simulation

Create exactly `app/src/main/java/com/echorunner/entity/PuzzleSystems.kt`
(`package com.echorunner.entity`):

```kotlin
object PuzzleSystems {
    fun resolveSignals(world: WorldState)
    fun updatePlates(world: WorldState, audio: AudioController)
    fun updateSwitches(world: WorldState, dt: Float, audio: AudioController)
    fun updateDoors(world: WorldState, dt: Float, audio: AudioController)
    fun updateMovers(world: WorldState, dt: Float)
    fun updateLasers(world: WorldState, dt: Float, audio: AudioController)
    fun updateTeleporters(world: WorldState, dt: Float, audio: AudioController)
    fun laserLethalAt(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean
}
```

- **resolveSignals**: rebuild `world.signals` from current plate/switch state. A plate
  contributes `pressed || latched`; a switch contributes `on`. Clear and repopulate the
  existing map — do not allocate a new one.
- **updatePlates**: pressed when the player, any Echo, or any walker overlaps the rect. Use
  `overlapsPadded` with pad ~0.12 — the player's feet rest *on* the plate, not inside it, so
  a bare overlap test flickers. Respect `echoOnly`. Set `contacts` to the overlapping body
  count. `latching` plates set `latched` permanently. `PLATE_PRESS` on the transition only.
- **updateSwitches**: toggled when a body's interact fires while overlapping the rect padded
  by `INTERACT_REACH`. Player interacts when `world.player.interacting`; an Echo interacts
  when `echo.replay.interacting(echo.tick)`. Use `consumedThisTake` as an edge guard so a
  held press toggles once; clear it when no body is interacting in range. `holdSeconds > 0`
  makes it a timed switch that falls back off (`SWITCH_OFF`). `SWITCH_ON` when it turns on.
- **updateDoors**: animate `openAmount` toward 1 when `world.evaluate(spec.opensWhen)`, else
  toward 0, at `1/travelSeconds` per second. `DOOR_OPEN`/`DOOR_CLOSE` once per direction
  change, not every tick.
- **updateMovers**: store prev, advance along `spec.path`, then compute `dx`/`dy` as the
  per-tick delta (physics reads these to carry riders). Implement all three modes: `LOOP`
  ping-pongs the path continuously; `SIGNAL` advances only while `evaluate(activeWhen)` holds
  and reverses when it drops (this is the elevator); `ONE_SHOT` runs the path once after the
  signal first fires, then stops. Honour `dwellSeconds`. Guard paths with 0 or 1 nodes.
- **updateLasers**: advance `cycle` by dt, toggle `on` per the on/off duty cycle offset by
  `spec.phase`. Forced off while `evaluate(disabledWhen)`. `LASER_ON` when it switches on.
- **updateTeleporters**: player overlapping pad A with zero cooldown is moved centred on pad
  B and vice versa; set cooldown, play `TELEPORT`, emit particles at both pads, kick the
  camera lightly. **Also set `prevX/prevY` to the destination**, or the renderer interpolates
  a long smear across the screen.
- **laserLethalAt**: true when the AABB overlaps any laser with `on == true`.

---

## Module D — Enemy systems

**Design point that matters:** Echoes are full citizens to enemies. A walker chases the
**nearest body among {player, all Echoes}**. That is what makes "distract the enemy with an
Echo" a real mechanic. While chasing an Echo it must not also track the player.

Create exactly `app/src/main/java/com/echorunner/entity/EnemySystems.kt`
(`package com.echorunner.entity`):

```kotlin
object EnemySystems {
    fun updateTurrets(world: WorldState, dt: Float, audio: AudioController)
    fun updateBullets(world: WorldState, dt: Float)
    fun updateWalkers(world: WorldState, dt: Float, audio: AudioController)
    fun lethalForPlayer(world: WorldState): Boolean
    fun hazardLethalAt(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean
}
```

- **updateTurrets**: count down `cooldown`. Fire when any body (player or Echo) is within
  `spec.range` on the firing side and roughly aligned vertically (within ~2.5 units of the
  turret's vertical span) — it shoots things in its lane, not across the level. Skip entirely
  while `evaluate(disabledWhen)`. On fire: spawn from the muzzle edge with
  `spec.dirX * spec.bulletSpeed`, play `TURRET_FIRE`, reset cooldown, emit a muzzle particle.
  Set `alerted` for the renderer.
- **updateBullets**: integrate by `vx*dt`. Kill on leaving world bounds, on hitting solid
  geometry (SOLID/DECOR platform, solid door, mover), or **on hitting an Echo** — an Echo can
  body-block a shot, which is a deliberate tactic. Emit an impact particle on death. Bullets
  pass through ONE_WAY platforms. Do not kill the player here.
- **updateWalkers**: patrol `minX..maxX` at `spec.speed`, flipping at the bounds. Find the
  nearest body among {player, Echoes} within `alertRange` using `rectDistance`. If found: set
  `alerted`, move toward it at `speed * chaseMultiplier`, set `distractionTimer` to
  `DISTRACTION_SECONDS`. While that timer runs, keep pursuing the **already chosen** target
  rather than re-evaluating every tick, so an Echo genuinely holds attention; decay by dt and
  return to patrol when it expires with nothing in range. **Clamp walkers to their patrol
  span even while chasing** — they are ground units on a ledge and letting them wander off
  makes levels unsolvable. `ENEMY_ALERT` once on the transition into alerted. Store prev each
  tick.
- **hazardLethalAt**: overlaps any `world.level.hazards` rect.
- **lethalForPlayer**: player overlaps any hazard, any live bullet, or any walker; **or** the
  player has fallen below `WORLD_H` (pit death). Do **not** include lasers — those belong to
  Module C's `laserLethalAt`, and the orchestrator combines both. Allocation-free.

---

## Module E — Level parser, repository, and level content

Read `LevelData.kt` field by field; it is the schema.

**Solvability is the hard requirement.** A level with `maxEchoes = N` must be solvable in
N+1 takes. Every required jump must be under ~3.4 units of height and ~6.5 units of gap.
Reason about the solution explicitly and write it into the level's `hint` field.

Create:

### `app/src/main/java/com/echorunner/level/LevelParser.kt` (`package com.echorunner.level`)

```kotlin
object LevelParser {
    fun parse(json: String): LevelSpec
    fun parseSignal(obj: Any?): SignalCondition?
}
```

Map every field. Missing **optional** fields fall back to the data-class defaults rather
than throwing. A missing or malformed **required** field throws
`IllegalArgumentException` naming the level id and the offending field, so a bad level fails
loudly at load instead of becoming a silently unplayable room. Accept rects as
`{"x":,"y":,"w":,"h":}` **or** `[x,y,w,h]`; points as `{"x":,"y":}` or `[x,y]`; a signal
condition as a plain string (single id) **or** `{"ids":[..],"mode":"ALL|ANY","invert":bool}`.

### `app/src/main/java/com/echorunner/level/LevelRepository.kt` (same package)

```kotlin
data class Chapter(val number: Int, val title: String, val subtitle: String, val levelIds: List<String>)

class LevelRepository(private val assets: android.content.res.AssetManager) {
    fun chapters(): List<Chapter>
    fun load(levelId: String): LevelSpec
    fun levelIds(): List<String>
    fun nextLevelId(current: String): String?
    fun indexOf(levelId: String): Int
}
```

Reads `assets/levels/manifest.json` for chapters and level order, and
`assets/levels/<id>.json` per level. Cache parsed levels in a `HashMap`. Fail with a clear
message naming the missing asset.

### `app/src/main/assets/levels/manifest.json` + eight level files

Teaching progression, all in `app/src/main/assets/levels/`:

| file | chapter | maxEchoes | teaches |
|---|---|---|---|
| `1-1.json` | 1 | 0 | pure movement, single take, a couple of gaps |
| `1-2.json` | 1 | 1 | **the core lesson**: exit needs a plate held; you cannot hold it and leave, so take 1 holds it and take 2 walks out |
| `2-1.json` | 2 | 1 | timed switch (`holdSeconds` ~4) opens a far door; Echo flips it, player sprints |
| `2-2.json` | 2 | 2 | two plates held simultaneously (door `ALL` over both) + a ONE_WAY platform for vertical layering |
| `3-1.json` | 3 | 3 | three spatially separated ALL-mode plates — real multi-Echo choreography |
| `4-1.json` | 4 | 2 | a walker guards the exit corridor; an Echo is bait. Include a turret whose bullet an Echo can body-block |
| `5-1.json` | 5 | 2 | `SIGNAL`-mode mover as an elevator (Echo holds the plate) + a laser duty cycle to time |
| `6-1.json` | 6 | 4 | graduation: plate + timed switch + elevator + laser, four cooperating timelines |

Give `1-1`, `2-1`, `3-1`, `4-1`, `5-1`, `6-1` a 2–3 line `dialogue` array (speaker + text),
spare and melancholy sci-fi — the world remembers what you did. No purple prose.

Every level needs a solid floor (or deliberate hazard pits), a spawn on solid ground, a
physically reachable exit, and `takeSeconds` 30 unless the design needs otherwise.

Also report a one-line intended solution per level.

---

## Module F — Neon renderer (Compose Canvas)

**Art direction — the user explicitly does not want generic or cluttered output.**
Futuristic minimalist sci-fi. Near-black void (`#05060B`). Platforms are dark slabs with a
single bright neon **top edge**, not filled bright shapes. Cyan `#22D3EE` is the player;
magenta `#E879F9` is the Echo; amber `#FBBF24` for hazards/enemies; violet for the exit.
Restraint is the point: glow comes from a few bright 1–2 px edges and soft radial washes over
a dark field, never from filling everything with saturated colour. Subtle animated
background: slow parallax grid plus 2–3 drifting fog washes. No clutter, no busy patterns, no
text drawn into the world.

Create exactly:

### `app/src/main/java/com/echorunner/render/Palette.kt` (`package com.echorunner.render`)

```kotlin
object Palette {
    val void: Color; val platformFill: Color; val platformEdge: Color
    val player: Color; val playerGlow: Color
    val hazard: Color; val exit: Color; val laser: Color
    val plateOn: Color; val plateOff: Color
    val enemy: Color; val bullet: Color
    val gridLine: Color; val fog: Color
    fun echo(index: Int): Color
    fun particleTint(index: Int): Color
}
```

`echo(index)` returns a distinguishable hue per Echo (magenta, teal, lime, orange, rose) so
the player can tell timelines apart at a glance. `particleTint` maps 0..7 to colours.

### `app/src/main/java/com/echorunner/render/GameRenderer.kt` (same package)

```kotlin
class GameRenderer {
    fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        world: WorldState,
        alpha: Float,
        settings: GameSettings,
        timeSeconds: Float,
    )
}
```

**Camera transform — implement exactly this, it affects playability:** scale so the world's
*height* fills the viewport height (`scale = size.height / WORLD_H`); do not letterbox.
Horizontally follow `world.camera.x`, clamped so the view never shows outside `0..WORLD_W`;
if the scaled world width is narrower than the viewport, centre it instead. Apply
`camera.shakeOffsetX/Y` as a render-only translation — never move bodies. Use private
world→screen helpers consistently.

**Draw order, back to front:** void; parallax grid (camera-offset, scrolling slowly with
`timeSeconds`); fog washes; teleporter pads; plates (pressed ones sink slightly and light
up); lasers (only when `on`; bright core + soft outer bloom, plus faint emitter housings when
off); movers; platforms (dark slab + bright top edge; ONE_WAY as a thin dashed line so the
affordance reads); hazards (rows of amber triangles); doors (height scales with
`1-openAmount` so they retract); turrets; walkers (small alert indicator when alerted);
bullets; exit (soft violet portal, pulsing, clearly brighter once reachable); Echoes; player;
particles; vignette.

**Echo appearance is the game's signature — get it right:** translucent body (~35% alpha) in
the Echo's hue, a brighter 1 px outline, a short motion trail sampled from its own replay at
`tick-2`, `tick-4`, `tick-6` with decreasing alpha, and a subtle horizontal distortion offset
that wobbles with `timeSeconds` so it reads as a *recording* rather than a second player.
Respect `settings.showEchoTrails`.

**Player:** solid dark core, bright cyan outline, soft radial glow. Subtle squash/stretch from
vertical velocity (taller rising, flatter on landing), clamped so it never looks broken. Small
facing indicator.

Particles: draw from `world.particles` using `life/maxLife` as alpha falloff and
`Palette.particleTint(tint[i])`. Respect `settings.reducedEffects` by drawing a fraction of
them and skipping fog.

Use interpolated positions everywhere (`player.renderX(alpha)`, `echo.renderX(alpha)`,
`mover.renderX(alpha)`, `walker.renderX(alpha)`) so motion is smooth above 60 Hz.

Hoist `Color`/`Brush` objects into `Palette` or class fields — one allocated per entity per
frame is a real frame-time cost.

---

## Module G — Procedural audio + haptics

**Why procedural:** we cannot ship licensed audio, and silent stubs would make the game feel
dead. Every cue is synthesised into a PCM buffer. `AudioController` is the seam — a later
move to recorded audio must touch only this module, never a call site.

Create exactly:

### `app/src/main/java/com/echorunner/audio/AudioEngine.kt` (`package com.echorunner.audio`)

```kotlin
class AudioEngine(context: android.content.Context) : AudioController
```

- Synthesise a 16-bit mono PCM `ShortArray` at 44100 Hz for **every** `SoundCue`, cached by
  ordinal. Do the work off the main thread (lazy background warmup inside the class).
- Build small primitives — sine, triangle, square, white noise, exponential decay envelope,
  pitch sweep, short bandpassed noise burst — and compose them per cue. Each cue must have
  character; do not reuse one beep:
  `JUMP` quick upward sweep, soft triangle · `LAND` low thud with a noise transient ·
  `DASH` descending filtered noise whoosh · `FOOTSTEP` very short quiet click (fires often,
  must be unobtrusive) · `SWITCH_ON`/`OFF` crisp two-tone click, up/down ·
  `PLATE_PRESS` soft mechanical thunk · `DOOR_OPEN`/`CLOSE` low sliding rumble with a slow
  amplitude ramp · `ECHO_CREATED` **the signature sound**: shimmering detuned dyad with a
  reversed-sounding swell, distinctly otherworldly · `ECHO_EXPIRE` the same idea decaying
  downward · `ENEMY_ALERT` two urgent rising blips · `TURRET_FIRE` dry percussive snap ·
  `LASER_ON` bright sustained buzz with slight amplitude modulation · `TELEPORT` fast rising
  sweep · `DEATH` short descending dissonant fall · `LEVEL_COMPLETE` clean ascending major
  triad · `UI_TAP`/`UI_BACK` soft muted blips, up/down · `COUNTDOWN_TICK` dry tick.
- Playback: a small pool of `AudioTrack`s for one-shots, round-robined, so overlapping cues
  do not cut each other off. Respect `sfxEnabled` and per-call volume. `play()` is called
  from the game loop and **must return immediately**.
- Ambient: `startAmbient`/`stopAmbient` run a looping generated pad on a background thread
  via a STREAM-mode `AudioTrack` — a slow evolving drone from 2–3 detuned low sine partials
  plus a very slow filtered noise wash, gentle LFO on amplitude. It must loop **seamlessly**
  (match start/end phase or crossfade the ends). Quiet and atmospheric, not melodic. Respect
  `musicEnabled`.
- `release()` stops the ambient thread and releases every track; safe to call twice. Guard
  all `AudioTrack` use against `IllegalStateException` so an audio-focus oddity on a real
  device can never crash the game.

### `app/src/main/java/com/echorunner/audio/AndroidHaptics.kt` (same package)

```kotlin
class AndroidHaptics(context: android.content.Context) : Haptics
```

`VibratorManager` on API 31+, deprecated `Vibrator` below, with `VibrationEffect`.
`LIGHT_TAP` a very short tick · `ECHO_CREATED` a distinctive double pulse · `DEATH` one sharp
buzz · `LEVEL_COMPLETE` a short rising two-pulse pattern. Respect `setEnabled`. Never crash
when the device has no vibrator.

---

## Module H — Progress persistence

Create exactly `app/src/main/java/com/echorunner/save/ProgressStore.kt`
(`package com.echorunner.save`):

```kotlin
class ProgressStore(context: android.content.Context) : ProgressRepository
```

- Persist the whole `GameProgress` as JSON at `context.filesDir/progress.json` — the
  manifest's backup rules already name that exact file, so do not rename it.
- **Atomic writes**: serialise to `progress.json.tmp`, flush, then rename over the real file.
  A game gets killed mid-write routinely, and a half-written save that throws on next launch
  would wipe the player's progress. On load, if the JSON is corrupt, log and return a fresh
  default `GameProgress` — never let a bad save brick the app.
- Keep an in-memory cached `GameProgress` so `load()` is cheap and the loop can read freely.
- `updateLevel`: read existing record (or fresh), transform, write back. **Semantics that
  matter:** `bestTakes` and `bestSeconds` only ever improve — a worse later run must not
  overwrite a better record; put that comparison in a helper callers can rely on and document
  it. `deaths` accumulate rather than replace.
- File I/O off the caller's thread (single-thread executor or `Dispatchers.IO`), coalescing
  rapid saves (~250 ms debounce) so completing a level does not stutter the frame. Expose one
  extra public `fun flush()` to force a synchronous flush — this is the only addition to the
  interface you may make.
- Round-trip every field losslessly; missing keys fall back to defaults so an older save
  still loads after a schema addition.

---

## Module I — Endless generator + Challenge definitions

Create exactly:

### `app/src/main/java/com/echorunner/mode/EndlessGenerator.kt` (`package com.echorunner.mode`)

```kotlin
object EndlessGenerator {
    fun generate(roomIndex: Int, seed: Long): LevelSpec
    fun scoreFor(roomIndex: Int, secondsTaken: Float, deaths: Int, echoesUsed: Int): Int
}
```

- **Deterministic**: the same `(roomIndex, seed)` always yields an identical room. Seed a
  `java.util.Random` from a mix of both. Never `Math.random`, never a time-based seed.
- **Always solvable — do not generate noise and hope.** Lay a guaranteed traversable route
  first (landing platforms from spawn to exit, each step within real jump reach: under ~3.4
  units up, ~6.5 across), then decorate with hazards, plates and enemies **only** where they
  do not block that route. Verify the route geometrically in code and discard any placement
  that lands on it. State the reachability invariant in a comment.
- Ramp difficulty with `roomIndex`: early rooms pure movement on wide platforms; then a
  plate-gated exit; then lasers; then walkers and turrets; later rooms combine them, shrink
  platforms, widen gaps and raise required Echoes. Cap at `GameConstants.MAX_ECHOES`.
- Unique id `"endless-<roomIndex>"`, solid floor or deliberate pits, reachable exit, sane
  `takeSeconds`.
- `scoreFor`: reward speed and few deaths, reward using **fewer** Echoes (elegance), and scale
  the base with `roomIndex` so deep rooms are worth more. Document the formula.

### `app/src/main/java/com/echorunner/mode/Challenges.kt` (same package)

```kotlin
object Challenges {
    val ALL: List<ChallengeSpec>
    fun apply(level: LevelSpec, modifier: ChallengeModifier): LevelSpec
    fun forId(id: String): ChallengeSpec?
}
```

- At least 8 entries reusing story level ids that exist: `1-2`, `2-1`, `2-2`, `3-1`, `4-1`,
  `5-1`, `6-1`. Cover every restriction in `ChallengeModifier`: max-2-Echoes, no-jumping,
  limited-dash, 15-second memory, invisible traps. Names and descriptions as terse challenge
  cards, not marketing copy.
- `apply`: return a **copy** with the modifier folded in — override `maxEchoes` and
  `takeSeconds` when specified. Do not mutate the input. The engine reads the remaining flags
  (`jumpDisabled`, `dashBudget`, `invisibleTraps`) directly off the modifier at runtime, so
  `apply` only handles those two overrides — say so in a comment so the split is not mistaken
  for an omission.
- **A no-jumping modifier must not be attached to a level that cannot be finished without
  jumping.** Reason about which levels tolerate which restriction and note that reasoning in
  a comment above `ALL`.
