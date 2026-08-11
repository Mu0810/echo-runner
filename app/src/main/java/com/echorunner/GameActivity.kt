package com.echorunner

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echorunner.render.Palette
import com.echorunner.ui.ChallengeScreen
import com.echorunner.ui.EchoRunnerTheme
import com.echorunner.ui.GameScreen
import com.echorunner.ui.GameViewModel
import com.echorunner.ui.MainMenuScreen
import com.echorunner.ui.Screen
import com.echorunner.ui.SettingsScreen
import com.echorunner.ui.StoryScreen

/**
 * The game's single activity.
 *
 * Locked to landscape and marked `configChanges` in the manifest, so a rotation or a keyboard
 * appearing never tears down the activity mid-run. That matters more here than in a normal app:
 * recreating the activity would drop the recorded Echoes, which are the player's actual
 * progress within a level and are deliberately not persisted.
 */
class GameActivity : ComponentActivity() {

    private val vm: GameViewModel by viewModels { GameViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // A dropped frame from the screen dimming mid-jump is worse than the battery cost.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        setContent {
            EchoRunnerTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.void)
                ) {
                    when (val s = screen) {
                        Screen.Menu -> MainMenuScreen(vm)
                        Screen.Story -> StoryScreen(vm)
                        Screen.ChallengeList -> ChallengeScreen(vm)
                        Screen.Settings -> SettingsScreen(vm, settings)
                        is Screen.Play -> GameScreen(vm, s, settings)
                    }
                }
            }
        }
    }

    /** Hides the status and navigation bars; swiping brings them back transiently. */
    private fun goImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumeApp()
    }

    override fun onPause() {
        super.onPause()
        vm.onPauseApp()
    }

    // ---- physical / Bluetooth keyboard -----------------------------------------------
    //
    // Mapped to the same InputBuffer the touch controls write to, so neither path is
    // privileged and both can be used interchangeably (handy for testing on an emulator,
    // where there is no thumb).

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount > 0) return true // ignore auto-repeat
        return when (keyCode) {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> { vm.input.moveX = -1f; true }
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.input.moveX = 1f; true }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_A -> { vm.input.pressJump(); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_BUTTON_X -> {
                vm.input.pressDash(); true
            }
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_B -> {
                vm.input.pressInteract(); true
            }
            KeyEvent.KEYCODE_R -> { vm.restartTake(); true }
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_BUTTON_START -> { vm.togglePause(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.input.moveX = 0f; true }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_A -> { vm.input.releaseJump(); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_BUTTON_X -> {
                vm.input.releaseDash(); true
            }
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_B -> {
                vm.input.releaseInteract(); true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
}
