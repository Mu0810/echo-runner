# Play Store listing — Echo Runner

Copy-paste source for the Play Console entry. Character limits are Play's own; counts are
noted so nothing gets silently truncated at submission.

---

## App name  (limit 30)

```
Echo Runner
```

`11 / 30`

Alternative if "Echo Runner" collides with an existing listing:

```
Echo Runner: World Remembers
```

`28 / 30`

---

## Short description  (limit 80)

```
Solve each room with the versions of yourself you recorded 30 seconds ago.
```

`73 / 80`

This is the line that appears under the icon in search results, so it states the mechanic
rather than the genre — "puzzle platformer" tells a browser nothing that the screenshots don't.

---

## Full description  (limit 4000)

```
You get thirty seconds.

Then everything you just did happens again, without you. Your movements replay as an Echo — a
translucent recording of yourself that walks the same path, presses the same plate, stands
exactly where you stood. And while it does, you get another thirty seconds.

Every room in Echo Runner is built for a crew. You are the only one here. So you become the
crew: one version holds the pressure plate, another flips the switch across the room, a third
stands where the guard can see it — and the last one walks out through the door they opened.

THIRTY ROOMS, ONE IDEA AT A TIME
Seven chapters that each teach a single new thing and then ask you to combine it. Pressure
plates that only read a recording. Doors that shut behind you. Lifts that need weight you
cannot carry and ride at the same time. Guards that follow whichever version of you they
noticed first. By the final chapter you are choreographing five timelines at once and
planning three takes ahead.

ENDLESS ECHO
Procedurally generated rooms that keep escalating. Every room is solvable by construction —
the route is laid before the hazards are, so a room is never impossible, only harder. Scored
on speed, survival, and how few Echoes you needed.

CHALLENGES
Rooms you have already solved, made harder. No jumping. One dash. Fifteen seconds of memory
instead of thirty. Traps you cannot see until you are close.

RATED ON ELEGANCE, NOT SPEED
Three stars means you solved the room in the intended number of takes without losing one.
There is no timer bonus. Rushing a thirty-second recording does not make a plan better.

BUILT SMALL AND OFFLINE
Under a megabyte. No internet permission, no accounts, no ads, no purchases, no tracking.
Your progress is saved on your device and goes nowhere. Plays in landscape with large touch
controls, or with a Bluetooth keyboard or controller.

The facility recorded everything you ever did in it. That is the only reason you can leave.
```

`~1,760 / 4000`

Every claim above is verifiable in the build: no `INTERNET` permission, no ads or billing
libraries, no analytics, progress stored only in the app's private files directory, and the
release APK is 960 KB. Do not add claims that need a screenshot to disprove.

---

## Categorisation

| Field | Value |
|---|---|
| App or game | Game |
| Category | Puzzle |
| Tags | Puzzle, Platformer, Singleplayer, Offline, Indie |
| Contains ads | **No** |
| In-app purchases | **No** |

---

## Data safety declaration

The honest answers, all of which the build supports:

- **Does your app collect or share any user data?** — **No.**
- **Is all user data encrypted in transit?** — N/A, no data leaves the device.
- **Do you provide a way for users to request data deletion?** — N/A. Uninstalling removes the
  save file; there is no server copy.

Supporting facts if Play asks: the manifest requests only `VIBRATE`; there is no `INTERNET`
permission, so the app is physically incapable of transmitting anything. Progress lives in
`filesDir/progress.json` inside the app sandbox.

A privacy policy URL is still **required** even for zero-collection apps. A short static page
stating "this app collects no data, requests no network access, and stores progress only on
your device" satisfies it.

---

## Content rating questionnaire

Answer honestly and the rating comes out at the lowest tier:

- Violence: **none**. Hazards kill the player character; there are no depictions of injury,
  blood, or weapons used against people. Turrets fire at a geometric figure.
- Sexuality, drugs, gambling, profanity: **none**.
- User-generated content, social features, location, personal info sharing: **none**.

---

## Graphics checklist

| Asset | Requirement | File |
|---|---|---|
| App icon | 512×512, 32-bit PNG | `store/icon-512.png` |
| Feature graphic | 1024×500, no alpha | `store/feature-1024x500.jpg` |
| Phone screenshots | 2–8, landscape here | `store/shot-*.png` (gitignored; regenerate with adb) |
| Promo video | YouTube URL, optional | none — Play does not accept the GIFs in `docs/` |

**Screenshot ordering matters more than the copy.** Put a room with two or more Echoes visible
on plates first — that image is the entire pitch. The current captures show level 1-1, which by
design has no Echoes and therefore sells nothing.

---

## Before you submit

- Bump `versionCode` in `app/build.gradle.kts`. It is `1`; Play permanently rejects a reused
  code, and this is the most common first-upload rejection.
- Upload the **AAB**, not the APK: `./gradlew bundleRelease`.
- Play App Signing will re-sign for distribution, which makes `echo-runner-release.jks` your
  *upload* key. Back it up before you need it.
