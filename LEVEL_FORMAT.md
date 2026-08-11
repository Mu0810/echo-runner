# Level JSON contract

Authoritative format for `app/src/main/assets/levels/*.json`. The Kotlin schema is
`app/src/main/java/com/echorunner/core/LevelData.kt` — read it; this file is how that schema
is spelled in JSON. Parser and level authors must both obey it exactly.

## Shorthands accepted everywhere

- **rect**: `{"x":0,"y":20,"w":40,"h":2.5}` or `[0,20,40,2.5]`
- **point**: `{"x":3,"y":18.45}` or `[3,18.45]`
- **signal**: `"p1"` (single id, mode ALL) or
  `{"ids":["p1","p2"],"mode":"ALL","invert":false}` — `mode` is `ALL` or `ANY`

## Coordinate system — get this right or levels are unplayable

World is **40 wide × 22.5 tall**, **y-DOWN**: `y=0` is the top of the screen, `y=22.5` the
bottom. Gravity is +y.

A rect is anchored at its **top-left**. So a platform's **top surface is its `y`**, and a
player standing on it has `y = platformY - 1.55`.

Player is 0.8 × 1.55 units, runs 9.2 u/s, **jump apex ≈ 3.67 units**, air time ≈ 0.68 s so a
running jump clears **≈ 6.3 units** of gap. Budget under 3.4 up and 6.0 across to leave
margin. A wall taller than 3.67 is impassable — that is how you force an Echo to open a door.

Falling below y=22.5 is death, so a gap in the floor is a pit with no hazard needed (add a
`PIT` hazard anyway when you want it to read visually).

## Top-level object

```json
{
  "id": "1-2",                  // REQUIRED, must equal the filename stem
  "name": "Hold The Line",      // REQUIRED
  "chapter": 1,                 // REQUIRED
  "takeSeconds": 30,            // optional, default 30
  "maxEchoes": 1,               // optional, default 1. 0 = must solve in one take
  "spawn": [3, 18.45],          // REQUIRED, put it ON solid ground
  "exit": [35, 17.5, 1.6, 2.5], // REQUIRED
  "exitCondition": null,        // optional signal; null/absent = exit always open
  "hint": "...",                // optional. PUT THE INTENDED SOLUTION HERE.
  "parTakes": 2,                // optional, default 1
  "dialogue": [ {"speaker":"ECHO-0","text":"..."} ],
  "platforms": [], "hazards": [], "plates": [], "switches": [],
  "doors": [], "movers": [], "lasers": [], "turrets": [],
  "walkers": [], "teleporters": []
}
```

## Entity objects

```json
platforms:   {"rect":[0,20,40,2.5], "kind":"SOLID"}        // SOLID | ONE_WAY | DECOR
hazards:     {"rect":[12,21,4,1.5], "kind":"SPIKE"}        // SPIKE | PIT | CRUSHER
plates:      {"id":"p1","rect":[8,19.6,1.8,0.4],"latching":false,"echoOnly":false}
switches:    {"id":"s1","rect":[6,18.6,1.0,1.4],"holdSeconds":5,"startsOn":false}
doors:       {"id":"d1","rect":[30,15.5,1.2,4.5],"opensWhen":"p1","travelSeconds":0.35}
movers:      {"id":"m1","size":{"w":4,"h":0.5},"path":[[20,18],[20,12]],
              "speed":3,"mode":"SIGNAL","activeWhen":"p1","dwellSeconds":0.25,"startNode":0}
lasers:      {"id":"l1","rect":[22,16,0.3,4],"onSeconds":1.6,"offSeconds":1.4,
              "phase":0,"disabledWhen":null}
turrets:     {"id":"t1","rect":[38,17.8,1.2,1.2],"dirX":-1,"intervalSeconds":1.4,
              "bulletSpeed":11,"range":26,"disabledWhen":null}
walkers:     {"id":"w1","spawn":[24,18.7],"minX":20,"maxX":30,"speed":2.6,
              "alertRange":6.5,"chaseMultiplier":1.7}
teleporters: {"id":"tp1","a":[5,17,1.2,3],"b":[33,13,1.2,3],"cooldownSeconds":0.6}
```

Notes that bite:

- **`movers.size` only uses `w`/`h`.** Position comes from `path`; `x`/`y` in `size` are
  ignored. Accept `{"w":..,"h":..}` or `[0,0,w,h]`.
- **`MoverMode.SIGNAL` is the elevator**: advances while its signal holds, reverses when it
  drops. `LOOP` ping-pongs forever. `ONE_SHOT` runs once after the signal first fires.
- A **plate** fires while any body rests on it — player, Echo *or* walker. `latching:true`
  latches on permanently once pressed. `echoOnly:true` ignores the player.
- A **switch** toggles on interact. `holdSeconds > 0` makes it lapse back off — that is the
  "race the door" timer.
- Walkers are clamped to `minX..maxX` even while chasing, so their patrol span must actually
  contain the ground they need.

## Solvability rules — non-negotiable

1. A level with `maxEchoes = N` must be solvable in **N+1 takes**.
2. The world **resets** at the start of each take; all Echoes replay from tick 0 in lockstep.
   An Echo does exactly what the player did on the take that produced it.
3. Echoes **do not collide with geometry** — they pass through doors and walls. They *do*
   press plates, flip switches, block bullets and draw enemies.
4. Write the take-by-take solution into `hint`. If you cannot write the solution, the level
   is not finished.
5. Check every required traversal against the jump budget above. Verify each platform the
   player must land on is reachable from the previous one.
