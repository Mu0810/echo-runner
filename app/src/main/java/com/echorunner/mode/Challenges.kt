package com.echorunner.mode

import com.echorunner.core.ChallengeModifier
import com.echorunner.core.ChallengeSpec
import com.echorunner.core.LevelSpec

/**
 * Challenge Mode: story geometry, re-run under a restriction.
 *
 * A challenge never ships its own level. It names an existing story level and a
 * [ChallengeModifier], which is what keeps the mode from becoming a second content pipeline.
 */
object Challenges {

    // ---- Restriction families ---------------------------------------------------------
    // Declared once and shared, so the same restriction reads identically on every card it
    // appears on and there is one place to retune it.

    private val NO_JUMP = ChallengeModifier(
        id = "no-jump",
        label = "Grounded",
        description = "Jump is dead. Walk it.",
        jumpDisabled = true,
    )

    private val MEMORY_15 = ChallengeModifier(
        id = "memory-15",
        label = "15s memory",
        description = "Takes last 15 seconds.",
        takeSeconds = 15f,
    )

    private val MEMORY_20_TWO = ChallengeModifier(
        id = "memory-20",
        label = "20s memory",
        description = "Takes last 20 seconds.",
        takeSeconds = 20f,
    )

    private val MEMORY_15_TWO = ChallengeModifier(
        id = "memory-15-echo-2",
        label = "15s · 2 Echoes",
        description = "Two Echoes, 15 seconds each.",
        maxEchoes = 2,
        takeSeconds = 15f,
    )

    private val DASH_ONE = ChallengeModifier(
        id = "dash-1",
        label = "One dash",
        description = "One dash for the whole attempt.",
        dashBudget = 1,
    )

    private val DASH_THREE_BLIND = ChallengeModifier(
        id = "dash-3-blind",
        label = "Three dashes · blind",
        description = "Three dashes. Traps stay hidden.",
        dashBudget = 3,
        invisibleTraps = true,
    )

    private val BLIND_TWO = ChallengeModifier(
        id = "blind-echo-2",
        label = "Blind · 2 Echoes",
        description = "Traps only show up close.",
        maxEchoes = 2,
        invisibleTraps = true,
    )

    private val BLIND_DASH_TWO = ChallengeModifier(
        id = "blind-dash-2",
        label = "Blind · two dashes",
        description = "Hidden traps, two dashes.",
        dashBudget = 2,
        invisibleTraps = true,
    )

    private val NO_DASH_TWO = ChallengeModifier(
        id = "no-dash-echo-2",
        label = "No dash · 2 Echoes",
        description = "No dash at all. Two Echoes.",
        maxEchoes = 2,
        dashBudget = 0,
    )

    /**
     * The roster.
     *
     * ## Why each restriction sits on the level it does
     *
     * A restriction that makes a level unfinishable is not a challenge, it is a bug, so the
     * pairings are argued rather than sprinkled:
     *
     * - **Dash restrictions are safe everywhere.** Story levels are authored so every
     *   *required* jump is inside plain run-and-jump reach (under ~3.4 units up, ~6.5 across).
     *   Dash is therefore a comfort, never a dependency, and even `dashBudget = 0` leaves a
     *   level completable. That is why the dash cards are spread across the harder rooms.
     * - **Shortening the take is safe while the window still covers one objective.** Every
     *   take does one job — reach a plate, flip a switch, walk to the exit — and at
     *   9.2 units/second, 15 seconds crosses a 40-unit room three times over. It is used on
     *   the compact chapter 1–2 rooms. `3-1` and `4-1` get 20 seconds instead, because their
     *   Echoes must *arrive* before the player's final take can overlap them, and that arrival
     *   time eats into the window.
     * - **Capping Echoes is only applied where the cap matches the level's own allowance**
     *   (`4-1` and `5-1` ship with 2, `2-2` with 2). Cutting a level *below* its authored
     *   allowance is what breaks a hold-N-plates-at-once puzzle — `3-1` needs three
     *   simultaneous plates and `6-1` four cooperating timelines, so neither is ever capped.
     *   The cap is paired with a second restriction so the card is still a real challenge.
     * - **No-jump is attached to exactly one level, `1-2`.** It is the only room whose design
     *   is purely horizontal: it teaches delegation, not platforming — the plate is held by an
     *   Echo and the player *walks* out, with the vertical work starting in `2-2`'s ONE_WAY
     *   layering. Every other level has an authored gap or a height change and would be
     *   unfinishable without a jump.
     *
     * That last pairing is the one thing here that depends on level *content* rather than on
     * an authoring rule: if `1-2` is ever revised to need a hop, this entry has to move (the
     * horizontal sprint room `2-1` is the fallback) — nothing else in this list is affected.
     */
    val ALL: List<ChallengeSpec> = listOf(
        ChallengeSpec("1-2-grounded", "Grounded", "1-2", NO_JUMP),
        ChallengeSpec("1-2-fading", "Fading Memory", "1-2", MEMORY_15),
        ChallengeSpec("2-1-rationed", "Rationed", "2-1", DASH_ONE),
        ChallengeSpec("2-2-half-memory", "Half Memory", "2-2", MEMORY_15_TWO),
        ChallengeSpec("3-1-tight-loop", "Tight Loop", "3-1", MEMORY_20_TWO),
        ChallengeSpec("4-1-blind-spots", "Blind Spots", "4-1", BLIND_TWO),
        ChallengeSpec("4-1-short-fuse", "Short Fuse", "4-1", MEMORY_20_TWO),
        ChallengeSpec("5-1-dark-cycle", "Dark Cycle", "5-1", BLIND_DASH_TWO),
        ChallengeSpec("5-1-purist", "Purist", "5-1", NO_DASH_TWO),
        ChallengeSpec("6-1-iron", "Iron Discipline", "6-1", DASH_THREE_BLIND),
    )

    /**
     * Folds [modifier] into [level] and returns a **copy**; the input is never mutated, since
     * the same parsed [LevelSpec] instance is cached by the level repository and reused by
     * Story mode.
     *
     * Only [ChallengeModifier.maxEchoes] and [ChallengeModifier.takeSeconds] are handled here,
     * because they are the only two restrictions that live in the level *data*. The rest
     * (`jumpDisabled`, `dashBudget`, `invisibleTraps`) are behavioural: the player controller
     * and the renderer read them straight off the modifier every tick. Their absence below is
     * that split, not an omission.
     */
    fun apply(level: LevelSpec, modifier: ChallengeModifier): LevelSpec {
        if (modifier.maxEchoes == null && modifier.takeSeconds == null) return level
        return level.copy(
            maxEchoes = modifier.maxEchoes ?: level.maxEchoes,
            takeSeconds = modifier.takeSeconds ?: level.takeSeconds,
        )
    }

    /** Looks a challenge up by [ChallengeSpec.id]. Linear over a fixed, menu-sized list. */
    fun forId(id: String): ChallengeSpec? {
        for (i in ALL.indices) {
            if (ALL[i].id == id) return ALL[i]
        }
        return null
    }
}
