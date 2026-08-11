package com.echorunner.core

/**
 * Star rating for a completed level.
 *
 * The score the game cares about is **takes**, not time: a level is solved elegantly when it
 * needs few Echoes, and `LevelSpec.parTakes` records the intended count. Rating on takes rather
 * than a stopwatch also keeps the game a puzzle rather than a speedrun — hurrying a 30-second
 * recording rarely makes a plan better, so a timer-based rating would reward the wrong thing.
 *
 * Deaths act as the tiebreaker because dying discards the take: it costs nothing on the
 * scoreboard, so without it a scrappy solve and a clean one would be indistinguishable.
 *
 * | Stars | Condition |
 * |---|---|
 * | 3 | solved within par **and** never died |
 * | 2 | solved within par (having died), or one take over par with no deaths |
 * | 1 | solved at all |
 *
 * Three stars is deliberately reachable on a first clean attempt — the intended solution IS par,
 * so a player who reasons it out correctly is rewarded rather than being asked to grind.
 */
object Rating {
    const val MAX_STARS = 3

    fun starsFor(takesUsed: Int, parTakes: Int, deaths: Int): Int {
        if (takesUsed <= 0) return 0
        // A level authored without a sensible par still has to rate something; treat par as at
        // least one take, otherwise every solve would look over-budget.
        val par = parTakes.coerceAtLeast(1)
        val clean = deaths <= 0
        return when {
            takesUsed <= par && clean -> 3
            takesUsed <= par -> 2
            takesUsed == par + 1 && clean -> 2
            else -> 1
        }
    }

    /** Compact label for the level-select chips, e.g. `★★☆`. */
    fun glyphs(stars: Int): String {
        val filled = stars.coerceIn(0, MAX_STARS)
        return "★".repeat(filled) + "☆".repeat(MAX_STARS - filled)
    }
}
