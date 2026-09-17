package com.GoogleLLC.TrivialAdventure

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.GoogleLLC.TrivialAdventure.ui.GameScreen
import com.GoogleLLC.TrivialAdventure.ui.theme.TrivialAdventureTheme
import kotlinx.coroutines.launch

/**
 * Host for the Compose UI and the only component allowed to talk to [GameTrialsManager] with
 * an `Activity`.
 *
 * The Trials SDK needs a foreground Activity to attach the paywall to, and a ViewModel must
 * never hold one, so the milestone call is modelled as:
 *
 * ```
 * ViewModel --GameEffect.RequestStopTrial--> Activity --stopTrial(this)--> SDK
 * ViewModel <--------onTrialOutcome()-------- Activity <----TrialOutcome---- SDK
 * ```
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    private val trialsManager by lazy { (application as TrivialAdventureApp).trialsManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        collectEffects()

        setContent {
            TrivialAdventureTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                GameScreen(
                    state = state,
                    onPrimaryAction = viewModel::onPrimaryAction,
                    onToggleUnlock = viewModel::setLocalUnlock,
                    onReset = viewModel::resetSandbox,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have purchased the game on the paywall we launched, so re-check
        // entitlement every time we come back to the foreground.
        viewModel.onResume()
    }

    private fun collectEffects() {
        lifecycleScope.launch {
            // STARTED (not CREATED): stopTrial() needs a visible window to attach the paywall.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        GameEffect.RequestStopTrial -> {
                            // ---- THE MILESTONE CALL ----
                            // Terminates the progression trial; Play raises the system paywall.
                            val outcome = trialsManager.stopTrial(this@MainActivity)
                            viewModel.onTrialOutcome(outcome)
                        }

                        is GameEffect.ShowToast ->
                            Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
