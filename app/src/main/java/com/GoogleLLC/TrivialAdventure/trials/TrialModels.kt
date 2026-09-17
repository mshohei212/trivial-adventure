package com.GoogleLLC.TrivialAdventure.trials

/**
 * Entitlement state of the current install, as far as the Play Trials SDK is concerned.
 */
sealed interface TrialAvailability {

    /**
     * The Trials SDK is on the classpath and this install is still inside the trial.
     * Reaching the progression milestone should terminate the trial.
     *
     * @param binding human-readable description of the resolved SDK entry point, shown in the
     *   debug chip so QA can confirm which AAR build is wired up.
     */
    data class TrialActive(val binding: String) : TrialAvailability

    /** The SDK reports the user owns the full game, so the milestone is a normal boss fight. */
    data object FullVersion : TrialAvailability

    /**
     * `play-trials-sdk.aar` is not on the classpath. The game runs unrestricted so the rest of
     * the progression loop stays testable on a plain emulator.
     */
    data object SdkMissing : TrialAvailability

    /** The SDK is present but the entry point could not be bound (bad/unsupported AAR build). */
    data class SdkIncompatible(val reason: String) : TrialAvailability

    val isTrialEnforced: Boolean get() = this is TrialActive
}

/**
 * Result of asking the SDK to end the trial at a progression milestone.
 */
sealed interface TrialOutcome {

    /**
     * `stopTrial` succeeded. Play now owns the UX: the system paywall is raised over the game.
     * The game must NOT grant the milestone reward.
     */
    data object TrialStopped : TrialOutcome

    /** Nothing to stop: the user already owns the full game. Grant the reward. */
    data object AlreadyUnlocked : TrialOutcome

    /** No SDK on the classpath. Grant the reward so the sandbox stays playable. */
    data object SdkUnavailable : TrialOutcome

    /**
     * The SDK was invoked but threw. Treated as "fail open" by the caller: a broken trial
     * integration must never soft-lock the player behind a boss they cannot fight.
     */
    data class Failed(val reason: String) : TrialOutcome

    /** True when the reward should be granted and the player allowed to progress. */
    val shouldGrantReward: Boolean get() = this !is TrialStopped
}
