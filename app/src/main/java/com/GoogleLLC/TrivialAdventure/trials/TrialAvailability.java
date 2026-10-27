package com.GoogleLLC.TrivialAdventure.trials;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Entitlement state of the current install, as far as the Play Trials SDK is concerned.
 *
 * <p>Modelled as an enum {@link Kind} plus a detail string rather than a class hierarchy. That
 * keeps it idiomatic Java, and it also means Kotlin call sites can {@code when} over
 * {@link #getKind()} exhaustively - Kotlin does not treat a Java class hierarchy as sealed, so
 * subclasses would have forced an {@code else} branch on every call site.
 */
public final class TrialAvailability {

    public enum Kind {
        /**
         * This install is currently inside the trial. Reaching the progression milestone
         * should terminate the trial via {@code PlayTrials.stopTrial(Context)}.
         */
        TRIAL_ACTIVE,

        /** The user owns the full game, so the milestone is just a normal boss fight. */
        FULL_VERSION,
    }

    private static final TrialAvailability TRIAL_ACTIVE =
            new TrialAvailability(Kind.TRIAL_ACTIVE);
    private static final TrialAvailability FULL_VERSION =
            new TrialAvailability(Kind.FULL_VERSION);

    private final Kind kind;

    private TrialAvailability(@NonNull Kind kind) {
        this.kind = kind;
    }

    @NonNull
    public static TrialAvailability trialActive() {
        return TRIAL_ACTIVE;
    }

    @NonNull
    public static TrialAvailability fullVersion() {
        return FULL_VERSION;
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    /** True only when a trial is actually in force and the milestone should stop it. */
    public boolean isTrialEnforced() {
        return kind == Kind.TRIAL_ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrialAvailability)) {
            return false;
        }
        TrialAvailability other = (TrialAvailability) o;
        return kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind);
    }

    @NonNull
    @Override
    public String toString() {
        return kind.name();
    }
}
