# Level plan — the remaining 22 levels

Read `LEVEL_FORMAT.md` first; it is the authoritative JSON contract, coordinate system, jump
budget and solvability rules. This file says *which* level teaches *what*, so the difficulty
ramp is coherent and no two levels are the same puzzle twice.

Eight levels already ship: `1-1 1-2 2-1 2-2 3-1 4-1 5-1 6-1`. **Do not modify them.** Read
`1-2.json` and `2-2.json` first — they set the quality bar, especially the `hint` field, which
must contain the full take-by-take solution with distances and timings.

## Engine features nothing currently uses

The shipped levels only exercise plain plates, ALL-mode doors, one timed switch, one walker,
one turret, one SIGNAL elevator and one laser. Everything below is already implemented and
tested but has never appeared in a level. Introducing them is the point of this batch:

| Feature | Spelling | Behaviour |
|---|---|---|
| Latching plate | `"latching": true` | Once pressed it stays on **permanently**. Lets one Echo arm something and walk away. |
| Echo-only plate | `"echoOnly": true` | The player's own body does **not** trigger it. Forces delegation. |
| Inverted signal | `{"ids":["s1"],"invert":true}` | Door is open until the signal fires, then shuts. Portcullis. |
| ANY mode | `{"ids":["a","b"],"mode":"ANY"}` | Either producer suffices. |
| LOOP mover | `"mode":"LOOP"` | Ping-pongs its path forever, no signal needed. A moving platform. |
| ONE_SHOT mover | `"mode":"ONE_SHOT"` | Runs its path once after its signal first fires, then stops. A one-way ride. |
| Teleporter | `teleporters: [{id,a,b}]` | Entering either pad emits from the other. Only the **player** teleports. |
| Bullet body-block | (emergent) | A bullet that hits an Echo dies. An Echo standing in a firing lane is cover. |

## Assignments

Every level: `takeSeconds` 30 unless the design needs otherwise, spawn on solid ground, a
reachable exit, and the full solution in `hint`. Chapters 1–2 stay gentle; 7 is the finale.

### Chapter 1 — Cold Start
- **`1-3.json`** · maxEchoes 1 · The plate is on a **raised ledge**, not the floor, so the Echo
  must be *placed* somewhere it took effort to reach. Teaches that an Echo can hold a position
  anywhere you could stand.
- **`1-4.json`** · maxEchoes 1 · First **latching** plate. Press once and it stays; the Echo taps
  it in passing rather than standing on it, and both bodies are then free. Make the difference
  from 1-2 unmistakable.

### Chapter 2 — Two Of You
- **`2-3.json`** · maxEchoes 1 · First **inverted** door: it starts open and a switch shuts it.
  The player must get through before their own Echo closes it behind them.
- **`2-4.json`** · maxEchoes 1 · First **ANY**-mode door: two widely separated switches, either
  one works. Teaches reading the condition instead of assuming ALL.
- **`2-5.json`** · maxEchoes 2 · First **echoOnly** plate: the player standing on it does
  nothing at all. The only way is to record a take standing there and let the Echo do it.

### Chapter 3 — Held Open
- **`3-2.json`** · maxEchoes 2 · Two plates plus the first **teleporter**. Note only the player
  teleports, never an Echo — build the puzzle around that asymmetry.
- **`3-3.json`** · maxEchoes 3 · Three plates at **three different heights**, so the choreography
  is vertical rather than a row along the floor.
- **`3-4.json`** · maxEchoes 3 · A plate *and* a timed switch that must overlap: one Echo holds,
  one Echo presses, the player runs.
- **`3-5.json`** · maxEchoes 4 · Four plates, ALL. The endurance test of the chapter.

### Chapter 4 — Interference
- **`4-2.json`** · maxEchoes 2 · **Two walkers** on separate patrol spans. Recall a walker locks
  onto whichever body it noticed first and is clamped to `minX..maxX` even while chasing.
- **`4-3.json`** · maxEchoes 2 · **Bullet body-blocking as the solution**: a turret lane the
  player cannot survive crossing, with an Echo parked in it as cover. Take care that the take
  which records the Echo does not itself require standing in the lane while the turret fires —
  use a `disabledWhen` plate or turret timing so the recording take survives.
- **`4-4.json`** · maxEchoes 3 · Walker + turret + plate combined.

### Chapter 5 — Something Follows
- **`5-2.json`** · maxEchoes 1 · First **LOOP** mover: a moving platform running on its own
  cycle, ferrying the player over a pit. Timing, not signalling.
- **`5-3.json`** · maxEchoes 2 · First **ONE_SHOT** mover, armed by a switch: a ride that
  happens once, so the Echo must trigger it at the right moment.
- **`5-4.json`** · maxEchoes 3 · Two elevators plus a laser cycle. Vertical timing under threat.

### Chapter 6 — The World Remembers
- **`6-2.json`** · maxEchoes 3 · Elevator + echoOnly plate + laser.
- **`6-3.json`** · maxEchoes 4 · Teleporter + walker + plates.
- **`6-4.json`** · maxEchoes 4 · A chain: each Echo unlocks the ground the *next* Echo needs, so
  the takes must be performed in one specific order.

### Chapter 7 — What You Leave Behind (new chapter, the finale)
- **`7-1.json`** · maxEchoes 4 · **Chained dependency**: Echo 1 enables where Echo 2 must stand,
  which enables Echo 3, and so on. State the order in the hint.
- **`7-2.json`** · maxEchoes 5 · Five timelines. Mixed plates, switches and an elevator.
- **`7-3.json`** · maxEchoes 5 · Five timelines with enemies and lasers as well.
- **`7-4.json`** · maxEchoes 5 · The last room. Every mechanic the game has, still solvable in
  six takes, and the hint must walk all six.

## Rules for this batch

1. **Write your JSON files first**, before any checking or reporting. A perfect analysis with no
   file on disk is a failed task.
2. **Do NOT run `gradle`, `./gradlew`, `kotlinc` or any Kotlin/Java compiler.** Previous attempts
   burned their whole time budget on standalone type-checks and produced nothing. `python3 -c
   "import json,sys; json.load(open('x.json'))"` to check JSON validity is fine and fast.
3. Touch **only** the level files assigned to you. Never edit `manifest.json` (the parent owns
   it), never edit another agent's level, never edit anything under `core/`.
4. Reason the solution through **before** placing geometry. Verify each required jump against the
   budget: under 3.4 units up, under 6.0 across. A platform's top edge is its `y`; a standing
   player's y is `platformY - 1.55`.
5. A level with `maxEchoes = N` must be solvable in **N+1** takes, and each take must be
   physically achievable inside `takeSeconds` at 9.2 u/s.
6. Give levels a short `dialogue` array (2–3 lines) where it earns one — spare, melancholy
   sci-fi, no purple prose. Not every level needs it; chapter openers do.
