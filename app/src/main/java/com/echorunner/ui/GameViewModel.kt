package com.echorunner.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.echorunner.audio.AndroidHaptics
import com.echorunner.audio.AudioEngine
import com.echorunner.core.ChallengeModifier
import com.echorunner.core.GameMode
import com.echorunner.core.GamePhase
import com.echorunner.core.GameSettings
import com.echorunner.core.InputBuffer
import com.echorunner.core.LevelSpec
import com.echorunner.core.Rating
import com.echorunner.core.SoundCue
import com.echorunner.engine.GameEngine
import com.echorunner.level.Chapter
import com.echorunner.level.LevelRepository
import com.echorunner.mode.Challenges
import com.echorunner.mode.EndlessGenerator
import com.echorunner.save.ProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Where the player currently is. Hand-rolled routing: one sealed type beats a nav library here. */
sealed interface Screen {
    data object Menu : Screen
    data object Story : Screen
    data object ChallengeList : Screen
    data object Settings : Screen
    data class Play(
        val mode: GameMode,
        val levelId: String,
        val challengeId: String? = null,
    ) : Screen
}

/**
 * Owns the engine, the level library, audio and persistence, and exposes UI state as
 * [StateFlow]s.
 *
 * The engine and its [com.echorunner.core.WorldState] are intentionally **not** in a StateFlow:
 * they mutate 60 times a second, and pushing that through a snapshot-state pipeline would
 * allocate and recompose on every tick. The render surface instead subscribes to a single frame
 * counter and reads the world directly — see `GameSurface`. Everything that changes at *human*
 * speed (screen, settings, progress) does go through StateFlow.
 */
class GameViewModel(app: Application) : ViewModel() {

    val levels = LevelRepository(app.assets)
    private val store = ProgressStore(app)
    val audio = AudioEngine(app)
    private val haptics = AndroidHaptics(app)

    val engine = GameEngine(audio, haptics)
    val input = InputBuffer()

    private val _screen = MutableStateFlow<Screen>(Screen.Menu)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _settings = MutableStateFlow(store.load().settings)
    val settings: StateFlow<GameSettings> = _settings.asStateFlow()

    private val _progressVersion = MutableStateFlow(0)
    /** Bumped whenever persisted progress changes, so level lists recompose. */
    val progressVersion: StateFlow<Int> = _progressVersion.asStateFlow()

    /** Non-null while a level is loaded; drives HUD labels. */
    var currentLevel: LevelSpec? = null
        private set

    /**
     * Stars earned by the run that just finished, for the completion banner. Held here rather
     * than read back from the store because the store keeps the BEST result, and the banner has
     * to show what this attempt actually scored — including when that is worse than a past run.
     */
    var lastStars: Int = 0
        private set
    private var currentChallenge: ChallengeModifier = ChallengeModifier.NONE
    private var mode: GameMode = GameMode.STORY

    // Endless run state.
    var endlessRoom = 1
        private set
    var endlessScore = 0
        private set
    private var endlessSeed = 0L

    init {
        applySettingsToDevices(_settings.value)
    }

    fun chapters(): List<Chapter> = levels.chapters()

    fun isCompleted(levelId: String): Boolean = store.load().isCompleted(levelId)

    fun bestTakes(levelId: String): Int = store.load().record(levelId).bestTakes

    fun bestStars(levelId: String): Int = store.load().record(levelId).bestStars

    fun attempts(levelId: String): Int = store.load().record(levelId).attempts

    /**
     * Whether [levelId] can be opened.
     *
     * Completing the previous level unlocks this one, and so does having *tried* it
     * [ATTEMPTS_TO_UNLOCK] times. Strictly linear gating meant one unsolvable room hid every room
     * behind it — the player saw a wall of locked chips and no way forward, which is a far worse
     * failure than letting someone move past a puzzle that beat them.
     */
    fun isUnlocked(levelId: String): Boolean {
        val ids = levels.levelIds()
        val i = ids.indexOf(levelId)
        if (i <= 0) return true
        val prev = store.load().record(ids[i - 1])
        return prev.completed || prev.attempts >= ATTEMPTS_TO_UNLOCK
    }

    /** True once the current room has been tried enough that offering a way past it is fair. */
    fun canSkipCurrent(): Boolean {
        val id = currentLevel?.id ?: return false
        if (mode != GameMode.STORY) return false
        return store.load().record(id).attempts >= ATTEMPTS_TO_UNLOCK &&
            levels.nextLevelId(id) != null
    }

    /**
     * Moves past the current room without marking it solved, so its chip stays unstarred and the
     * player can come back. Only offered once [canSkipCurrent] holds.
     */
    fun skipCurrent() {
        val id = currentLevel?.id ?: return
        val next = levels.nextLevelId(id) ?: return openMenu()
        audio.play(SoundCue.UI_BACK)
        engine.setPaused(false)
        input.clear()
        startStory(next)
    }

    /** Stars earned across Story mode, and the ceiling. Drives the main-menu progress line. */
    fun starTotals(): Pair<Int, Int> {
        val progress = store.load()
        val ids = levels.levelIds()
        val earned = ids.sumOf { progress.record(it).bestStars }
        return earned to ids.size * Rating.MAX_STARS
    }

    fun challengeDone(id: String): Boolean = id in store.load().challengesCompleted

    // ---- navigation ------------------------------------------------------------------

    fun openMenu() {
        audio.play(SoundCue.UI_BACK)
        currentLevel = null
        _screen.value = Screen.Menu
    }

    fun openStory() { tap(); _screen.value = Screen.Story }
    fun openChallenges() { tap(); _screen.value = Screen.ChallengeList }
    fun openSettings() { tap(); _screen.value = Screen.Settings }

    /** PLAY: resume at the first unfinished level, or the very first one. */
    fun quickPlay() {
        val next = levels.levelIds().firstOrNull { !isCompleted(it) } ?: levels.levelIds().first()
        startStory(next)
    }

    fun startStory(levelId: String) {
        tap()
        armTutorial(levelId)
        mode = GameMode.STORY
        currentChallenge = ChallengeModifier.NONE
        val spec = levels.load(levelId)
        currentLevel = spec
        lastStars = 0
        // Counted before play, not after: the escape hatch has to work for someone who opens a
        // room, sees no way through it, and backs out without dying or running the clock down.
        store.updateLevel(levelId) { it.copy(attempts = it.attempts + 1) }
        bumpProgress()
        engine.load(spec, ChallengeModifier.NONE)
        _screen.value = Screen.Play(GameMode.STORY, levelId)
    }

    fun startChallenge(challengeId: String) {
        tap()
        val challenge = Challenges.forId(challengeId) ?: return
        mode = GameMode.CHALLENGE
        currentChallenge = challenge.modifier
        val spec = Challenges.apply(levels.load(challenge.levelId), challenge.modifier)
        currentLevel = spec
        engine.load(spec, challenge.modifier)
        _screen.value = Screen.Play(GameMode.CHALLENGE, challenge.levelId, challengeId)
    }

    fun startEndless(fromRoom: Int = 1) {
        tap()
        mode = GameMode.ENDLESS
        currentChallenge = ChallengeModifier.NONE
        if (fromRoom <= 1) {
            endlessScore = 0
            // One seed per run: rooms stay reproducible within a run but differ between runs.
            endlessSeed = System.nanoTime()
        }
        endlessRoom = fromRoom.coerceAtLeast(1)
        val spec = EndlessGenerator.generate(endlessRoom, endlessSeed)
        currentLevel = spec
        engine.load(spec, ChallengeModifier.NONE)
        _screen.value = Screen.Play(GameMode.ENDLESS, spec.id)
    }

    // ---- in-level ---------------------------------------------------------------------

    fun dismissIntro() { tap(); engine.beginPlaying() }

    fun togglePause() {
        val world = engine.world ?: return
        val next = !world.paused
        engine.setPaused(next)
        input.clear()
        audio.play(if (next) SoundCue.UI_BACK else SoundCue.UI_TAP)
    }

    fun restartTake() { tap(); input.clear(); engine.restartTake() }
    fun restartLevel() { tap(); input.clear(); engine.restartLevel() }
    fun bankTake() { engine.endTakeNow() }

    // ---- first-run coach ---------------------------------------------------------------

    private val coach = CoachMachine()
    private val _tutorial = MutableStateFlow(TutorialStep.NONE)
    val tutorial: StateFlow<TutorialStep> = _tutorial.asStateFlow()

    /**
     * Advances the coach when the player *does* the thing, never on a timer.
     *
     * Fed once per simulation step with that step's sampled input, so the held-jump threshold is
     * counted in ticks and means the same on a 60 Hz phone as on a 120 Hz one. The decision logic
     * lives in [CoachMachine] rather than here so it can be tested without a device.
     */
    fun tickTutorial(jumpHeld: Boolean) {
        if (coach.step == TutorialStep.NONE || coach.step == TutorialStep.DONE) return
        val w = engine.world ?: return
        val completed = coach.tick(
            distanceFromSpawn = abs(w.player.x - w.level.spawn.x),
            onGround = w.player.onGround,
            jumpHeld = jumpHeld,
            echoCount = w.echoCount,
        )
        if (completed) store.save(store.load().copy(tutorialDone = true))
        if (_tutorial.value != coach.step) _tutorial.value = coach.step
    }

    /** Clears the closing card. Progress was already persisted when the last step completed. */
    fun dismissTutorial() {
        coach.dismiss()
        _tutorial.value = coach.step
    }

    /**
     * Arms the coach for the room being entered, resuming whatever lesson is still owed.
     *
     * The coach spans **two** rooms, which is not an arbitrary choice: 1-1 is authored with
     * `maxEchoes = 0`, a deliberate single-take room, so the banking lesson is physically
     * impossible there — an earlier version stalled on it forever and never marked itself done.
     * 1-2 is where the game hands over its first Echo, so that is where banking is taught.
     *
     * Lessons resume rather than reset, so a player who reaches 1-2 without having held a jump
     * still gets that lesson first, and the machine walks the rest of the way on its own.
     */
    private fun armTutorial(levelId: String) {
        val owed = when {
            store.load().tutorialDone -> TutorialStep.NONE
            levelId != FIRST_ROOM && levelId != ECHO_ROOM -> TutorialStep.NONE
            coach.step == TutorialStep.NONE || coach.step == TutorialStep.DONE -> TutorialStep.MOVE
            else -> coach.step
        }
        coach.arm(owed)
        _tutorial.value = coach.step
    }

    /** True when there is an Echo on the stack to take back. */
    fun canDiscardEcho(): Boolean = (engine.world?.echoes?.size ?: 0) > 0

    fun discardLastEcho() {
        tap()
        input.clear()
        engine.discardLastEcho()
    }

    /**
     * Called once when the engine reports the exit was reached.
     *
     * Records the result, then advances: Story walks the manifest order, Endless generates the
     * next room and accumulates score, Challenge returns to its list.
     */
    fun onLevelCompleted() {
        val world = engine.world ?: return
        val spec = currentLevel ?: return
        val takes = world.takeIndex + 1
        val seconds = world.totalSeconds
        val deaths = world.deaths

        when (mode) {
            GameMode.STORY -> {
                // Rated from THIS run's takes and deaths, not the stored totals: `deaths` in the
                // record is a lifetime counter, so it cannot say whether the best run was clean.
                // Undo is free for progression but priced for pride: unpicking a plan mid-solve
                // still clears the room and unlocks the next, but three stars should mean the
                // plan was right before it was committed to, so an undone run caps at two.
                val stars = Rating.starsFor(takes, spec.parTakes, deaths)
                    .let { if (world.undosUsed > 0) minOf(it, 2) else it }
                lastStars = stars
                store.updateLevel(spec.id) { existing ->
                    existing.copy(
                        completed = true,
                        bestTakes = takes,
                        bestSeconds = seconds,
                        deaths = existing.deaths + deaths,
                        bestStars = stars,
                    )
                }
                bumpProgress()
                val next = levels.nextLevelId(spec.id)
                if (next != null) startStory(next) else openMenu()
            }

            GameMode.ENDLESS -> {
                endlessScore += EndlessGenerator.scoreFor(endlessRoom, seconds, deaths, world.echoCount)
                val best = store.load().endless
                if (endlessScore > best.bestScore || endlessRoom > best.bestRooms) {
                    store.save(
                        store.load().copy(
                            endless = best.copy(
                                bestScore = maxOf(best.bestScore, endlessScore),
                                bestRooms = maxOf(best.bestRooms, endlessRoom),
                            )
                        )
                    )
                    bumpProgress()
                }
                startEndless(endlessRoom + 1)
            }

            GameMode.CHALLENGE -> {
                val id = (_screen.value as? Screen.Play)?.challengeId
                if (id != null) {
                    val p = store.load()
                    store.save(p.copy(challengesCompleted = p.challengesCompleted + id))
                    bumpProgress()
                }
                _screen.value = Screen.ChallengeList
            }
        }
    }

    /** True when the engine is sitting in the completion hold and the host should advance. */
    fun shouldAdvance(): Boolean {
        val w = engine.world ?: return false
        return w.phase == GamePhase.COMPLETE && w.phaseTimer <= 0f
    }

    // ---- settings --------------------------------------------------------------------

    fun updateSettings(transform: (GameSettings) -> GameSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        store.updateSettings { next }
        applySettingsToDevices(next)
        tap()
    }

    private fun applySettingsToDevices(s: GameSettings) {
        audio.setMusicEnabled(s.musicEnabled)
        audio.setSfxEnabled(s.sfxEnabled)
        haptics.setEnabled(s.hapticsEnabled)
    }

    fun onResumeApp() {
        if (_settings.value.musicEnabled) audio.startAmbient()
    }

    fun onPauseApp() {
        audio.stopAmbient()
        input.clear()
        // Pausing the world on background prevents the accumulator from having to swallow a
        // multi-second catch-up when the player returns.
        if (engine.world?.phase == GamePhase.PLAYING) engine.setPaused(true)
    }

    private fun tap() = audio.play(SoundCue.UI_TAP)
    private fun bumpProgress() { _progressVersion.value += 1 }

    override fun onCleared() {
        store.flush()
        audio.release()
    }

    companion object {
        /** The only room the coach runs in. */
        private const val FIRST_ROOM = "1-1"

        /**
         * Where the banking lesson happens.
         *
         * 1-1 is authored `maxEchoes = 0`, so it cannot host it — this is the first room that
         * grants an Echo at all, and it is named "Hold The Line" for exactly that reason.
         */
        private const val ECHO_ROOM = "1-2"
        /** Attempts after which the next room unlocks even without a clear. */
        const val ATTEMPTS_TO_UNLOCK = 3

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GameViewModel(app) as T
            }
    }
}
