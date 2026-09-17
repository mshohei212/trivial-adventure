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
         * The Trials SDK is on the classpath and this install is still inside the trial.
         * Reaching the progression milestone should terminate the trial.
         */
        TRIAL_ACTIVE,

        /** The user owns the full game, so the milestone is just a normal boss fight. */
        FULL_VERSION,

        /**
         * {@code play-trials-sdk.aar} is not on the classpath. The game runs unrestricted so the
         * rest of the progression loop stays testable on a plain emulator.
         */
        SDK_MISSING,

        /** The SDK is present but its entry point could not be bound. */
        SDK_INCOMPATIBLE,
    }

    private static final TrialAvailability FULL_VERSION =
            new TrialAvailability(Kind.FULL_VERSION, "");
    private static final TrialAvailability SDK_MISSING =
            new TrialAvailability(Kind.SDK_MISSING, "");

    private final Kind kind;
    private final String detail;

    private TrialAvailability(@NonNull Kind kind, @NonNull String detail) {
        this.kind = kind;
        this.detail = detail;
    }

    /**
     * @param binding human-readable description of the resolved SDK entry point, shown in the
     *                debug banner so QA can confirm which AAR build is wired up.
     */
    @NonNull
    public static TrialAvailability trialActive(@NonNull String binding) {
        return new TrialAvailability(Kind.TRIAL_ACTIVE, binding);
    }

    @NonNull
    public static TrialAvailability fullVersion() {
        return FULL_VERSION;
    }

    @NonNull
    public static TrialAvailability sdkMissing() {
        return SDK_MISSING;
    }

    @NonNull
    public static TrialAvailability sdkIncompatible(@NonNull String reason) {
        return new TrialAvailability(Kind.SDK_INCOMPATIBLE, reason);
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    /**
     * The bound method signature for {@link Kind#TRIAL_ACTIVE}, or the failure reason for
     * {@link Kind#SDK_INCOMPATIBLE}. Empty otherwise.
     */
    @NonNull
    public String getDetail() {
        return detail;
    }

    /** True only when a trial is actually in force and the milestone should stop it. */
    public boolean isTrialEnforced() {
        return kind == Kind.TRIAL_ACTIVE;
    }

    // equals/hashCode matter: this is held inside the Compose UI state, and StateFlow skips
    // equal values. Without them, every refresh would emit a "new" state and recompose.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrialAvailability)) {
            return false;
        }
        TrialAvailability other = (TrialAvailability) o;
        return kind == other.kind && detail.equals(other.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, detail);
    }

    @NonNull
    @Override
    public String toString() {
        return detail.isEmpty() ? kind.name() : kind.name() + "(" + detail + ")";
    }
}
