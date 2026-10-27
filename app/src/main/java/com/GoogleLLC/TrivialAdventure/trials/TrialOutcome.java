package com.GoogleLLC.TrivialAdventure.trials;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Result of asking the SDK to end the trial at a progression milestone.
 */
public final class TrialOutcome {

    public enum Kind {
        /**
         * {@code stopTrial} succeeded. Play now owns the UX: the system paywall is raised over
         * the game. The game must NOT grant the milestone reward.
         */
        TRIAL_STOPPED,

        /** Nothing to stop: the user already owns the full game. Grant the reward. */
        ALREADY_UNLOCKED,

        /**
         * {@code PlayTrials.stopTrial} was called, but Play's licensing client is not active
         * in this build, so no paywall can appear. Grant the reward.
         */
        NOT_ENFORCED,

        /**
         * The SDK was invoked but threw. Treated as "fail open" by the caller: a broken trial
         * integration must never soft-lock the player behind a boss they cannot fight.
         */
        FAILED,
    }

    private static final TrialOutcome TRIAL_STOPPED = new TrialOutcome(Kind.TRIAL_STOPPED, "");
    private static final TrialOutcome ALREADY_UNLOCKED =
            new TrialOutcome(Kind.ALREADY_UNLOCKED, "");

    private final Kind kind;
    private final String reason;

    private TrialOutcome(@NonNull Kind kind, @NonNull String reason) {
        this.kind = kind;
        this.reason = reason;
    }

    @NonNull
    public static TrialOutcome trialStopped() {
        return TRIAL_STOPPED;
    }

    @NonNull
    public static TrialOutcome alreadyUnlocked() {
        return ALREADY_UNLOCKED;
    }

    @NonNull
    public static TrialOutcome notEnforced(@NonNull String reason) {
        return new TrialOutcome(Kind.NOT_ENFORCED, reason);
    }

    @NonNull
    public static TrialOutcome failed(@NonNull String reason) {
        return new TrialOutcome(Kind.FAILED, reason);
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    /** Populated for {@link Kind#FAILED} and {@link Kind#NOT_ENFORCED}. */
    @NonNull
    public String getReason() {
        return reason;
    }

    /** True when the reward should be granted and the player allowed to progress. */
    public boolean shouldGrantReward() {
        return kind != Kind.TRIAL_STOPPED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrialOutcome)) {
            return false;
        }
        TrialOutcome other = (TrialOutcome) o;
        return kind == other.kind && reason.equals(other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, reason);
    }

    @NonNull
    @Override
    public String toString() {
        return reason.isEmpty() ? kind.name() : kind.name() + "(" + reason + ")";
    }
}
