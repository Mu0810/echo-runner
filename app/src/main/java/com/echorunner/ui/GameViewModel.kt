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
        mode = GameMode.STORY
        currentChallenge = ChallengeModifier.NONE
        val spec = levels.load(levelId)
        currentLevel = spec
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
                store.updateLevel(spec.id) { existing ->
                    existing.copy(
                        completed = true,
                        bestTakes = takes,
                        bestSeconds = seconds,
                        deaths = existing.deaths + deaths,
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
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GameViewModel(app) as T
            }
    }
}
