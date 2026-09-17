package com.GoogleLLC.TrivialAdventure.game

import com.GoogleLLC.TrivialAdventure.BuildConfig
import com.GoogleLLC.TrivialAdventure.trials.TrialAvailability

/** Tunable rules for the progression loop. */
object GameRules {

    /** XP required to advance one level. */
    const val XP_PER_LEVEL = 100

    /** Every Nth level is a boss level (5, 10, 15, ...). */
    const val BOSS_LEVEL_INTERVAL = 5

    /** XP granted by a single quest. */
    val QUEST_XP_RANGE = 20..40

    /**
     * The level whose boss ends the Play Trial. This is the *first* boss level, so a tester
     * reaches the paywall in roughly 12-20 taps.
     *
     * Sourced from `BuildConfig` so QA can re-point the milestone from Gradle without touching
     * game logic.
     */
    const val TRIAL_MILESTONE_LEVEL = BuildConfig.TRIAL_MILESTONE_LEVEL

    fun isBossLevel(level: Int): Boolean = level % BOSS_LEVEL_INTERVAL == 0

    /**
     * Applies quest XP, rolling it over into levels.
     *
     * Levelling stops as soon as a boss level is reached: the boss gates progression, so any
     * surplus XP is banked at the boss level instead of carrying the player past the paywall.
     * This is what guarantees a tester always *lands on* level 5 rather than skipping it.
     *
     * Pure function - no Android or SDK dependencies - so it is directly unit testable.
     */
    fun applyXp(level: Int, xp: Int, gained: Int): Progression {
        var newLevel = level
        var newXp = xp + gained
        val levelUps = mutableListOf<Int>()

        while (newXp >= XP_PER_LEVEL) {
            newXp -= XP_PER_LEVEL
            newLevel++
            levelUps += newLevel
            if (isBossLevel(newLevel)) break
        }

        return Progression(level = newLevel, xp = newXp, levelUps = levelUps)
    }
}

/** Outcome of [GameRules.applyXp]. */
data class Progression(
    val level: Int,
    val xp: Int,
    /** Levels reached by this single XP award, in order. */
    val levelUps: List<Int>,
)

/** Category of a log line, used purely for colouring the list. */
enum class LogKind { QUEST, LEVEL_UP, BOSS, TRIAL, SYSTEM }

data class QuestLogEntry(
    val id: Long,
    val text: String,
    val kind: LogKind,
)

/**
 * The bestiary. Each entry has a matching sprite in `ui/art/GameArt.kt`, so adding a creature
 * means adding a branch there too - the `when` is exhaustive, so the compiler will tell you.
 */
enum class Enemy(val displayName: String) {
    SLIME("Slime"),
    GOBLIN("Goblin Scout"),
    RAT("Giant Rat"),
    BANDIT("Hedge Bandit"),
    WISP("Wisp"),
    SKELETON("Rusted Skeleton"),
    CRAB("Mud Crab"),
    CHICKEN("Feral Chicken"),
}

/**
 * The most recent fight, used to drive the scene panel.
 *
 * @param id monotonically increasing, so the UI can animate on change even when the same
 *   creature is drawn twice in a row.
 * @param enemy `null` for a boss fight.
 */
data class Encounter(
    val id: Long,
    val enemy: Enemy?,
    val xpGained: Int,
    val isBoss: Boolean = false,
)

/**
 * Everything the Compose layer needs to render a frame. Deliberately free of SDK and
 * framework types so it stays trivially previewable and testable.
 */
data class GameUiState(
    val level: Int = 1,
    val xp: Int = 0,
    val log: List<QuestLogEntry> = emptyList(),
    /** True while the paywall request is in flight, to stop double-taps. */
    val isResolvingTrial: Boolean = false,
    val trialAvailability: TrialAvailability = TrialAvailability.sdkMissing(),
    /** Drives the scene panel art; `null` before the first quest. */
    val lastEncounter: Encounter? = null,
) {

    val isBossLevel: Boolean get() = GameRules.isBossLevel(level)

    /**
     * True when this boss is the trial milestone AND the trial is still enforceable.
     * Once the user owns the game (or the SDK is absent) the milestone is an ordinary boss.
     */
    val isTrialMilestone: Boolean
        get() = level == GameRules.TRIAL_MILESTONE_LEVEL &&
            isBossLevel &&
            trialAvailability.isTrialEnforced

    val xpProgress: Float get() = (xp.toFloat() / GameRules.XP_PER_LEVEL).coerceIn(0f, 1f)

    val primaryActionLabel: String get() = if (isBossLevel) "Fight Boss" else "Go on Quest"

    /** Flavour name for the current boss, e.g. "Gatekeeper of the Vault" at level 5. */
    val bossName: String get() = bossNameFor(level)

    private companion object {
        fun bossNameFor(level: Int): String = when (level) {
            5 -> "Gatekeeper of the Vault"
            10 -> "Duke of Dust"
            15 -> "The Hollow Knight-ish Thing"
            else -> "Boss of Floor $level"
        }
    }
}
