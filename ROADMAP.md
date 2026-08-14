# Roadmap — after v0.2.1

Ordered by what the game is missing, not by what is fun to build. v0.2.1 is content-complete
(30 levels, 7 chapters, Endless, Challenges) and structurally verified, but three things stand
between it and a game someone would finish: recovery granularity, controls that sit on the
level, and the fact that 28 of 30 rooms have never been cleared by anything.

Effort is in half-days for one agent. Risk is the chance the change breaks something already
working.

---

## v0.3 — Make it finishable

### 1. Undo the last Echo · 2d · medium risk
**What.** A fourth verb between `restartTake()` and `restartLevel()`: drop the most recently
banked Echo and hand the take back.

**Why this is first.** `GameEngine` currently offers restart-take (free) and restart-level (lose
everything). In 7-2/7-3/7-4 the player banks five 30-second performances; if take three ends two
units off its mark, the only recovery is redoing all five. That is ~2.5 minutes of re-performing
choreography the player has already solved, and it is the most likely place a player quits. The
puzzle is the plan, so the punishment should land on the plan, not on the re-typing.

**Why it is cheap.** Echoes record *resolved state, not input* — a frozen `EchoReplay` is
independent of the world it replays into. So dropping one is genuinely just: pop replay k, reset
world to spawn, `rewindAll`, decrement `takeIndex`. No re-simulation, no divergence. The
architecture already paid for this feature; it was simply never exposed.

- `echo/EchoManager.kt` — `dropLast(world)`, mirroring `rewindAll`'s reset discipline
- `engine/GameEngine.kt` — `undoLastEcho()` guarding phase and `echoCount > 0`
- `ui/GameScreen.kt` — the HUD's `ECHOES` pip row becomes the affordance: long-press the last
  filled pip to drop it. No new button competing for the corner.
- `EchoLoopTest` — after an undo, the remaining Echoes must still be frame-exact and the dropped
  timeline must leave no residue in plate state

**One design decision to settle before writing it** (flagging, not assuming): does an undone take
still count toward `takesUsed` for the star rating? If it does not, three stars stops meaning
"solved it in par" and starts meaning "was willing to retry", which guts `Rating`'s stated
premise. Recommendation: undo is free for *progress* and counted for *rating* — the level opens
either way, the star reflects the plan.

### 2. Controls off the play field · 1.5d · low risk
**What.** Lay the touch controls out against the fitted world rect, and give the camera enough
slack that the cluster never sits over playable ground.

**Why.** The README already admits this one, and the arithmetic says the current mitigation
cannot close it. `GameRenderer.layout` height-fits (`scale = size.height / 22.5`), so a 2400×1080
phone sees 50 world units against a 40-unit world: ~5u of dead column per side, about 240px. The
arc cluster is wider than that, so at the right-hand edge of a level — where every exit portal
is — JUMP still overlaps the room. Arranging the buttons in an arc bought a third of the spread
and was not enough; the root cause is that the world has no horizontal slack to give.

**Fix, in order of preference.** Reserve a control gutter in `layout`: compute the cluster's
width in world units, widen the camera clamp by that much on each side (`halfView - gutter`), and
let the world sit inside the remaining span. The camera already clamps and already centres a
too-narrow world, so this is a change to two expressions, not a new system. Failing that, expose
the fitted rect to `TouchControls` and anchor against it — that stops the controls drifting but
still overlaps on rooms that use the full 40u.

- `render/GameRenderer.kt` — gutter in `layout`, exposed as a `Rect` for the UI layer
- `ui/Controls.kt` — anchor to that rect instead of `fillMaxSize()`
- Verify on 16:9, 19.5:9 and 20:9 at `controlScale` 0.75 and 1.4, on level 1-1 (exit at the right
  edge) and 7-4 (busiest room)

### 3. Executable solutions for all 30 levels · 2d · low risk, high payoff
**What.** Add an optional `solution` field to the level schema: a per-take input script. A test
replays it and asserts `COMPLETE`.

**Why.** This is the biggest honesty gap in the project. `LevelAssetTest` proves 30 levels parse
and are structurally sane. `LevelClearabilityTest`'s bot can walk and jump but cannot ride a
lift, time a laser, use a teleporter or bait a walker — so every room in chapters 5–7 is
currently "well-formed", never "beatable". Two rooms have been cleared for real. Teaching the bot
those four skills is open-ended AI work with no completion criterion; transcribing the solution
that already exists in every level's `hint` field is bounded, and it converts 30 prose
solvability proofs into 30 regression tests. After this, any future physics tune that breaks a
room fails CI instead of shipping.

Schema: `"solution": [{"ticks": 1800, "inputs": "…"}]` — one entry per take, a compact run-length
encoding of `InputState` per tick. `LevelParser` ignores it at runtime; only the test reads it.

- `LEVEL_FORMAT.md` — document the field as part of the solvability contract
- `app/src/test/.../SolutionReplayTest.kt` — drive `GameEngine`, assert COMPLETE within
  `parTakes`, which also validates par rather than trusting it
- Author the 30 scripts in parallel; the `hint` fields already carry distances and timings
- CI: add to the existing level-validation job

---

## v0.4 — Reasons to open it again

### 4. Daily run · 1.5d · low risk
Seed `EndlessGenerator` from the UTC date so every player gets the same room sequence, keep a
local streak and a best-of history. The generator, scoring and `EndlessRecord` all already exist
— this is a seed, a date check and a screen. Cheapest retention mechanic available, and needs no
server, no account, no network permission.

### 5. Share a solve · 2d · low risk
A solve is already fully captured as echo buffers, which means the game can hand someone else a
watchable artefact of a plan. Encode the banked replays into a short base64 code; pasting it puts
the viewer in a spectate mode that replays the whole solve on that level. Bragging in a puzzle
game is "look at my *plan*", and this is the only mechanic that expresses it. Reuses the
`EchoReplay` packing already in `core/EchoFrame.kt`.

### 6. Custom levels by code · 3d · medium risk
Not an in-game editor — that is a large UI project for an unproven audience. Instead: accept a
compressed level JSON as a pasteable code, run it through the existing `LevelParser` and the
existing structural validator, and play it standalone. The format, the parser and the validator
are the three hard parts and all three already ship. Gate it behind a menu entry so a malformed
code can never touch campaign progress.

---

## v1.0 — Ship

### 7. Echoes readable without colour · 1d · low risk
`Palette` documents distinct per-timeline hues as "a mechanical requirement, not decoration" —
which is exactly why hue alone is the wrong encoding. With five Echoes distinguished only by hue,
a deuteranopic player cannot tell which timeline is which, and in Chapter 6 that information is
load-bearing. Add a second channel: the timeline's index drawn on the Echo, or a per-timeline
trail dash pattern. Cheap, and it is the difference between hard and impossible for ~8% of men.

### 8. Play Store submission · 1d · low risk
The signed AAB, the 512 icon, the feature graphic, four screenshots and the listing copy all
exist in `store/`. What remains is the console work: content rating questionnaire, data-safety
form, privacy policy URL, closed test track. **Blocker first:** `echo-runner-release.jks` still
exists only on this machine. Losing it means never updating the app under this package name
again. Back it up off-machine before anything else in this section.

### 9. Playtest the ramp · open-ended
Difficulty is validated structurally and now (item 3) empirically, but never for *feel*. Item 3
gives par-vs-actual for all 30 rooms, which is the first real signal about which rooms are
mis-tuned. Read it before hand-tuning anything.

---

## Deliberately not doing

- **Teaching the clearability bot to ride lifts and time lasers.** Unbounded, and item 3 gets the
  same guarantee for less.
- **In-game level editor.** Touch-based level authoring is its own product. Item 6 tests whether
  anyone wants custom content before paying for the editor.
- **Online leaderboards.** Needs a backend, an account system and a privacy policy with teeth.
  Item 4's local streak covers most of the motivation at none of the cost.
- **Recorded audio.** `AudioController` is the seam and synthesis sounds correct for the setting;
  swapping in licensed audio is a spend, not a gap.
