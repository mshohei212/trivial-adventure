package com.GoogleLLC.TrivialAdventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.GoogleLLC.TrivialAdventure.game.Encounter
import com.GoogleLLC.TrivialAdventure.game.Enemy
import com.GoogleLLC.TrivialAdventure.game.GameProgressStore
import com.GoogleLLC.TrivialAdventure.game.GameRules
import com.GoogleLLC.TrivialAdventure.game.GameUiState
import com.GoogleLLC.TrivialAdventure.game.LogKind
import com.GoogleLLC.TrivialAdventure.game.QuestLogEntry
import com.GoogleLLC.TrivialAdventure.trials.GameTrialsManager
import com.GoogleLLC.TrivialAdventure.trials.TrialAvailability
import com.GoogleLLC.TrivialAdventure.trials.TrialOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One-shot events the ViewModel cannot perform itself.
 *
 * [RequestStopTrial] exists because the Trials SDK needs a foreground `Activity`, which a
 * ViewModel must never hold. The Activity performs the call and reports the result back via
 * [MainViewModel.onTrialOutcome].
 */
sealed interface GameEffect {
    data object RequestStopTrial : GameEffect
    data class ShowToast(val message: String) : GameEffect
}

/**
 * Owns all Level/XP state and the progression rules, including the trial milestone branch.
 */
class MainViewModel(
    private val trialsManager: GameTrialsManager,
    private val progressStore: GameProgressStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _effects = Channel<GameEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var nextLogId = 0L

    /** Separate from [nextLogId] so the scene animates once per fight, not once per log line. */
    private var nextEncounterId = 0L

    private var entitlementPollJob: Job? = null

    init {
        val (level, xp) = progressStore.load()
        _uiState.value = GameUiState(
            level = level,
            xp = xp,
            trialAvailability = trialsManager.availability(),
        )
        log(startupMessage(_uiState.value.trialAvailability), LogKind.SYSTEM)
    }

    // ---------------------------------------------------------------------------------------
    // Player input
    // ---------------------------------------------------------------------------------------

    /** Handles the single primary button: either "Go on Quest" or "Fight Boss". */
    fun onPrimaryAction() {
        val state = _uiState.value
        // Guard against double-taps while the paywall request is in flight.
        if (state.isResolvingTrial) return

        if (state.isBossLevel) fightBoss(state.level) else goOnQuest(state)
    }

    private fun goOnQuest(state: GameUiState) {
        val gained = Random.nextInt(GameRules.QUEST_XP_RANGE.first, GameRules.QUEST_XP_RANGE.last + 1)
        val enemy = Enemy.entries.random()

        val progression = GameRules.applyXp(level = state.level, xp = state.xp, gained = gained)

        commit(
            level = progression.level,
            xp = progression.xp,
            encounter = Encounter(id = nextEncounterId++, enemy = enemy, xpGained = gained),
        )
        log("Defeated a ${enemy.displayName}, +$gained XP", LogKind.QUEST)
        progression.levelUps.forEach { reached ->
            if (GameRules.isBossLevel(reached)) {
                log("Level $reached! A boss bars the way: ${_uiState.value.bossName}", LogKind.LEVEL_UP)
            } else {
                log("Level up! You are now level $reached", LogKind.LEVEL_UP)
            }
        }
    }

    /**
     * Boss handling, including the trial milestone.
     *
     * At the milestone level the decision of *what should actually happen* is intentionally
     * delegated to [GameTrialsManager]: it is the only component that knows whether the SDK is
     * present and whether the user already owns the game. The ViewModel just asks the Activity
     * to make the call and then reacts to the [TrialOutcome].
     */
    private fun fightBoss(level: Int) {
        // At-or-beyond, not equality. If the player somehow arrives past the milestone without
        // having stopped the trial -- restored save data, a QA jump, a future change to the
        // level-up loop -- an equality check would silently disarm the paywall forever. Every
        // boss from the milestone onwards re-asserts it.
        if (level < GameRules.TRIAL_MILESTONE_LEVEL) {
            defeatBoss(level)
            return
        }

        _uiState.update { it.copy(isResolvingTrial = true) }
        log("${_uiState.value.bossName} blocks the vault door. Checking your licence...", LogKind.TRIAL)
        viewModelScope.launch { _effects.send(GameEffect.RequestStopTrial) }
    }

    /**
     * Result of the milestone call, reported by the Activity.
     *
     * Only [TrialOutcome.Kind.TRIAL_STOPPED] withholds the reward; every other outcome fails open
     * so a missing or broken SDK can never soft-lock the tester at the boss.
     */
    fun onTrialOutcome(outcome: TrialOutcome) {
        val level = _uiState.value.level
        _uiState.update {
            it.copy(isResolvingTrial = false, trialAvailability = trialsManager.availability())
        }

        when (outcome.kind) {
            TrialOutcome.Kind.TRIAL_STOPPED -> {
                // Play owns the screen now. No XP, no level: the player stays at the milestone
                // so that returning from the paywall resumes exactly here.
                log("Trial ended at the progression milestone. Play paywall requested.", LogKind.TRIAL)
                awaitAsyncFullVersionCheck(milestoneLevel = level)
            }

            TrialOutcome.Kind.ALREADY_UNLOCKED -> {
                _effects.trySend(GameEffect.ShowToast(UNLOCKED_TOAST))
                log("Full version detected - no trial to stop.", LogKind.TRIAL)
                defeatBoss(level)
            }

            TrialOutcome.Kind.NOT_ENFORCED -> {
                _effects.trySend(GameEffect.ShowToast(UNLOCKED_TOAST))
                log(
                    "stopTrial() called, but this build was not installed by Play " +
                        "(${outcome.reason}) - no paywall. Failing open.",
                    LogKind.TRIAL,
                )
                defeatBoss(level)
            }

            TrialOutcome.Kind.FAILED -> {
                _effects.trySend(GameEffect.ShowToast(UNLOCKED_TOAST))
                log("stopTrial() failed (${outcome.reason}); failing open.", LogKind.TRIAL)
                defeatBoss(level)
            }
        }
    }

    private fun defeatBoss(level: Int) {
        val bossName = _uiState.value.bossName
        commit(
            level = level + 1,
            xp = 0,
            encounter = Encounter(id = nextEncounterId++, enemy = null, xpGained = 0, isBoss = true),
        )
        log("$bossName defeated! Advanced to level ${level + 1}", LogKind.BOSS)
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle / QA hooks
    // ---------------------------------------------------------------------------------------

    /**
     * Re-checks entitlement whenever the game returns to the foreground: the user may have
     * just bought the game on the paywall we launched, or Play's asynchronous licensing check
     * may still be in flight right after cold start.
     */
    fun onResume() {
        if (!checkAndApplyEntitlement(isBackgroundPoll = false)) {
            awaitAsyncFullVersionCheck(milestoneLevel = null)
        }
    }

    private fun checkAndApplyEntitlement(isBackgroundPoll: Boolean): Boolean {
        val wasUnlocked =
            _uiState.value.trialAvailability.kind == TrialAvailability.Kind.FULL_VERSION
        trialsManager.refreshEntitlement()
        val availability = trialsManager.availability()
        _uiState.update { it.copy(trialAvailability = availability) }

        if (!wasUnlocked && availability.kind == TrialAvailability.Kind.FULL_VERSION) {
            if (isBackgroundPoll) {
                log("Full version license verified. Progression is unrestricted.", LogKind.SYSTEM)
            } else {
                log("Purchase detected - full version unlocked. The boss is now beatable.", LogKind.TRIAL)
                _effects.trySend(GameEffect.ShowToast("Thanks for buying! Full game unlocked."))
            }
        }
        return availability.kind == TrialAvailability.Kind.FULL_VERSION
    }

    /**
     * Play's injected `LicenseClient` binds to `com.android.vending.licensing.ILicensingService`
     * asynchronously on startup and when `stopTrial` is invoked. Poll briefly so that once
     * `FULL_CHECK_OK` arrives (~30-100ms later), the UI and milestone gate immediately reflect
     * full-version ownership.
     */
    private fun awaitAsyncFullVersionCheck(milestoneLevel: Int?) {
        entitlementPollJob?.cancel()
        entitlementPollJob = viewModelScope.launch {
            repeat(12) {
                delay(250L)
                if (trialsManager.refreshEntitlement()) {
                    val availability = trialsManager.availability()
                    val wasUnlocked =
                        _uiState.value.trialAvailability.kind == TrialAvailability.Kind.FULL_VERSION
                    _uiState.update { it.copy(trialAvailability = availability) }
                    if (milestoneLevel != null && _uiState.value.level == milestoneLevel) {
                        _effects.trySend(GameEffect.ShowToast(UNLOCKED_TOAST))
                        log("Full version detected - no trial to stop.", LogKind.TRIAL)
                        defeatBoss(milestoneLevel)
                    } else if (!wasUnlocked) {
                        log("Full version license verified. Progression is unrestricted.", LogKind.SYSTEM)
                    }
                    return@launch
                }
            }
        }
    }

    /** QA control: simulate owning / not owning the game without a real transaction. */
    fun setLocalUnlock(unlocked: Boolean) {
        trialsManager.isUnlockedLocally = unlocked
        _uiState.update { it.copy(trialAvailability = trialsManager.availability()) }
        log(
            if (unlocked) "QA: forced full-version entitlement." else "QA: cleared entitlement override.",
            LogKind.SYSTEM,
        )
    }

    /** QA control: back to level 1 with the trial re-armed, ready for another paywall run. */
    fun resetSandbox() {
        progressStore.clear()
        trialsManager.resetLocalOverrides()
        nextLogId = 0
        nextEncounterId = 0
        _uiState.value = GameUiState(trialAvailability = trialsManager.availability())
        log("Sandbox reset. Trial re-armed at level ${GameRules.TRIAL_MILESTONE_LEVEL}.", LogKind.SYSTEM)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun commit(level: Int, xp: Int, encounter: Encounter) {
        _uiState.update { it.copy(level = level, xp = xp, lastEncounter = encounter) }
        progressStore.save(level, xp)
    }

    private fun log(text: String, kind: LogKind) {
        val entry = QuestLogEntry(id = nextLogId++, text = text, kind = kind)
        _uiState.update { state ->
            // Newest first, bounded so a long grind session cannot grow the list forever.
            state.copy(log = (listOf(entry) + state.log).take(MAX_LOG_ENTRIES))
        }
    }

    private fun startupMessage(availability: TrialAvailability): String = when (availability.kind) {
        TrialAvailability.Kind.TRIAL_ACTIVE ->
            "Trial active. Trial ends at level ${GameRules.TRIAL_MILESTONE_LEVEL}."
        TrialAvailability.Kind.FULL_VERSION -> "Full version. Progression is unrestricted."
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 40
        private const val UNLOCKED_TOAST = "Full Game Unlocked! Boss Defeated!"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as TrivialAdventureApp
                MainViewModel(app.trialsManager, app.progressStore)
            }
        }
    }
}
