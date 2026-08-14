package com.echorunner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * The first-run coach.
 *
 * ## Why the game needs one at all
 *
 * v0.2.1 shipped a bug where a tapped jump rose 0.73 units against a 3.2-unit climb, so the first
 * room was unfinishable by anyone who tapped. The engine fix floored a tap at 3.80 units, but the
 * report underneath it was never really about physics: nothing in the game had ever said that
 * holding the button jumps higher. A player who taps and fails learns "this game is broken",
 * not "hold it longer".
 *
 * So each step is gated on the player *performing* the action, not on dismissing a card. You
 * cannot tap through this to get rid of it; the prompt for holding a jump clears when a jump is
 * genuinely held, which means the lesson has actually been transferred.
 *
 * Three steps only, and only in 1-1. The Echo mechanic is the game's one real idea and it teaches
 * itself the moment a player sees a translucent copy retrace their steps — what needs teaching is
 * the vocabulary that gets them to that moment: move, hold to jump, bank the take.
 */
enum class TutorialStep(val prompt: String, val detail: String) {
    /** Not running: either finished once, or this is not the tutorial room. */
    NONE("", ""),

    MOVE("DRAG THE STICK TO MOVE", "the room is thirty seconds long — that clock is your memory"),

    HOLD_JUMP(
        "HOLD JUMP — DON'T TAP IT",
        "a tap barely clears a step; holding carries you a floor and a half",
    ),

    BANK(
        "NOW PRESS BANK",
        "the take you just played becomes an Echo that repeats it forever",
    ),

    /** Held briefly after the last step so the loop is named before the coach disappears. */
    DONE(
        "THAT IS THE WHOLE GAME",
        "every take is remembered — solve each room with the versions of you that came before",
    ),
}

/**
 * The coach strip, pinned inside the top of the playfield.
 *
 * Sits below the HUD reservation rather than centred on screen: a modal in the middle of the room
 * would cover the geometry the instruction is talking about, and a player reading "hold jump"
 * needs to see the ledge they are aiming at.
 */
@Composable
fun TutorialCoach(step: TutorialStep, modifier: Modifier = Modifier) {
    if (step == TutorialStep.NONE) return
    val glow by animateFloatAsState(
        targetValue = if (step == TutorialStep.DONE) 1f else 0.82f,
        animationSpec = tween(420),
        label = "coachGlow",
    )
    val tint = if (step == TutorialStep.DONE) Ui.echo else Ui.accent

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .padding(top = ControlLayout.HUD_DP.dp + 14.dp)
                .background(Color(0xD9070C16), RoundedCornerShape(4.dp))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Step pips: three lessons, so the player can see the coach is finite.
                for (i in 0 until 3) {
                    val reached = step.ordinal > i + 1 || step == TutorialStep.DONE
                    val current = step.ordinal == i + 1
                    Box(
                        Modifier
                            .size(if (current) 7.dp else 5.dp)
                            .background(
                                color = when {
                                    current -> tint
                                    reached -> tint.copy(alpha = 0.55f)
                                    else -> Ui.textFaint.copy(alpha = 0.5f)
                                },
                                shape = CircleShape,
                            ),
                    )
                    if (i < 2) Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = step.prompt,
                    color = tint,
                    fontSize = 13.sp,
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(glow),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = step.detail,
                color = Ui.textDim,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The coach's state machine, deliberately free of Android and of the engine.
 *
 * Pulled out of the ViewModel so the teaching gates can be tested. They cannot be verified on a
 * device: `adb shell input` has no way to hold a Compose button for a measured duration, so the
 * hold-jump gate — the one that exists because of a shipped bug — would otherwise be the only
 * claim in this feature with no evidence behind it.
 */
class CoachMachine {

    var step: TutorialStep = TutorialStep.NONE
        private set

    /** Consecutive simulation ticks of held jump within one airborne stretch. */
    private var holdTicks = 0

    fun arm(startAt: TutorialStep) {
        step = startAt
        holdTicks = 0
    }

    /**
     * Advances on evidence the player performed the action.
     *
     * @return true on the tick the last lesson completes, so the caller can persist it once.
     */
    fun tick(
        distanceFromSpawn: Float,
        onGround: Boolean,
        jumpHeld: Boolean,
        echoCount: Int,
    ): Boolean {
        when (step) {
            TutorialStep.MOVE ->
                if (distanceFromSpawn >= MOVE_UNITS) step = TutorialStep.HOLD_JUMP

            TutorialStep.HOLD_JUMP -> {
                // Reset on landing *and* on release, so a flurry of taps can never accumulate
                // into one hold. The lesson is "keep it down", not "press it a lot".
                if (onGround || !jumpHeld) {
                    holdTicks = 0
                } else {
                    holdTicks++
                    if (holdTicks >= HOLD_TICKS) step = TutorialStep.BANK
                }
            }

            TutorialStep.BANK ->
                if (echoCount >= 1) {
                    step = TutorialStep.DONE
                    return true
                }

            else -> Unit
        }
        return false
    }

    fun dismiss() {
        if (step == TutorialStep.DONE) step = TutorialStep.NONE
    }

    private companion object {
        /** Far enough that a stray thumb twitch does not count as "I know how to move". */
        const val MOVE_UNITS = 3f

        /**
         * 15 ticks = 0.25 s at 60 Hz, comfortably past `MIN_JUMP_SECONDS` (0.18 s).
         *
         * The threshold has to sit above the engine's floored-tap window, or the lesson would
         * clear itself on exactly the input it exists to correct.
         */
        const val HOLD_TICKS = 15
    }
}
