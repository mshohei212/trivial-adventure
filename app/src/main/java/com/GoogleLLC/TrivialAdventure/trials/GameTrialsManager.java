package com.GoogleLLC.TrivialAdventure.trials;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;

/**
 * Single seam between the game and the Google Play Trials SDK (Try Before You Buy).
 *
 * <p>Nothing above this class ever touches an SDK type: the ViewModel and the Compose UI only
 * ever see {@link TrialAvailability} and {@link TrialOutcome}.
 *
 * <h2>Why reflection?</h2>
 *
 * <p>{@code play-trials-sdk.aar} is distributed to partners and is not published to Google's
 * Maven repo, so a direct compile-time reference would make the whole project unbuildable on any
 * machine that does not have the AAR. Binding reflectively means:
 *
 * <ul>
 *   <li>the sandbox compiles and runs on a bare emulator (reports
 *       {@link TrialAvailability.Kind#SDK_MISSING});</li>
 *   <li>dropping the AAR into {@code app/libs/} switches on the real paywall path with no code
 *       change;</li>
 *   <li>signature drift between AAR drops ({@code stopTrial(Activity)} vs
 *       {@code stopTrial(Context)} vs a singleton {@code getInstance(context).stopTrial()}) is
 *       absorbed in {@link PlayTrialsBinding} instead of breaking the build.</li>
 * </ul>
 *
 * <p>Once the coordinate is public this indirection can be deleted and replaced with the single
 * call it emulates:
 *
 * <pre>{@code
 * PlayTrials.stopTrial(activity);
 * }</pre>
 */
public final class GameTrialsManager {

    private static final String TAG = "GameTrialsManager";
    private static final String PREFS_NAME = "trivial_adventure_trials";
    private static final String KEY_LOCAL_UNLOCK = "local_unlock_override";

    private final Context appContext;
    private final SharedPreferences prefs;

    /** Resolved once per process; the classpath cannot change at runtime. */
    @Nullable
    private PlayTrialsBinding.Resolution resolution;

    /** Cached SDK entitlement answer; {@code null} means "not asked yet / no usable signal". */
    @Nullable
    private volatile Boolean cachedSdkEntitlement;

    public GameTrialsManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private synchronized PlayTrialsBinding.Resolution resolution() {
        if (resolution == null) {
            resolution = PlayTrialsBinding.resolve(appContext);
        }
        return resolution;
    }

    // ---------------------------------------------------------------------------------------
    // Entitlement
    // ---------------------------------------------------------------------------------------

    /**
     * QA override. Lets a tester simulate the post-purchase state without a real Play
     * transaction, and is what the app flips itself to once the paywall reports success.
     */
    public boolean isUnlockedLocally() {
        return prefs.getBoolean(KEY_LOCAL_UNLOCK, false);
    }

    public void setUnlockedLocally(boolean unlocked) {
        prefs.edit().putBoolean(KEY_LOCAL_UNLOCK, unlocked).apply();
    }

    /**
     * True when the milestone boss should behave like a normal boss instead of ending the trial.
     *
     * <p>The local QA override wins over the SDK so a tester can always force the "owns the game"
     * branch, even with a live trial build installed.
     */
    public boolean isFullVersionUnlocked() {
        if (isUnlockedLocally()) {
            return true;
        }
        return Boolean.TRUE.equals(querySdkEntitlement());
    }

    /**
     * Current trial posture, used to drive the status banner and the milestone branch.
     *
     * <p>Entitlement is checked <em>before</em> SDK presence: owning the game is the dominant
     * state, and a locally-unlocked install with no AAR is still "full version" as far as the
     * game logic is concerned. (Checking SDK presence first would make the QA unlock toggle look
     * inert.)
     */
    @NonNull
    public TrialAvailability availability() {
        if (isFullVersionUnlocked()) {
            return TrialAvailability.fullVersion();
        }

        PlayTrialsBinding.Resolution current = resolution();
        switch (current.state) {
            case MISSING:
                return TrialAvailability.sdkMissing();
            case INCOMPATIBLE:
                return TrialAvailability.sdkIncompatible(current.reason);
            case BOUND:
            default:
                // BOUND always carries a binding; the null check keeps the compiler happy.
                return current.binding == null
                        ? TrialAvailability.sdkMissing()
                        : TrialAvailability.trialActive(current.binding.describe());
        }
    }

    // ---------------------------------------------------------------------------------------
    // The milestone call
    // ---------------------------------------------------------------------------------------

    /**
     * Ends the progression trial and hands control to Play, which raises the system paywall over
     * the game.
     *
     * <p>Must be called on the main thread with the foreground {@link Activity}: the SDK needs a
     * live window token to attach the purchase flow to. The Play Trials integration requirements
     * state this explicitly, so it is enforced at runtime below and not merely annotated --
     * {@link MainThread} is a lint hint that vanishes at compile time.
     *
     * @return {@link TrialOutcome.Kind#TRIAL_STOPPED} when Play has taken over (do <b>not</b>
     *     grant the reward), otherwise a fail-open outcome and the caller should let the player
     *     through.
     */
    @MainThread
    @NonNull
    public TrialOutcome stopTrial(@NonNull Activity activity) {
        // Requirement: the call must originate on the main application thread. Calling anyway
        // from a worker would be undefined behaviour in the SDK, so refuse and fail open -- the
        // tester still advances, and the mistake is obvious in logcat.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            String thread = Thread.currentThread().getName();
            Log.e(TAG, "stopTrial() must be called on the main thread, but was called on '"
                    + thread + "'. Refusing to invoke the SDK; failing open.");
            return TrialOutcome.failed("called off the main thread (" + thread + ")");
        }

        // Requirement: the call must not come from an isolated/secondary process. An Activity
        // cannot exist in an isolated process, so this only catches a multi-process setup where
        // the SDK was bound in a process other than the main one.
        String offendingProcess = secondaryProcessName();
        if (offendingProcess != null) {
            Log.w(TAG, "stopTrial() invoked from secondary process '" + offendingProcess
                    + "'. Play Trials expects the main process; the call may be ignored.");
        }

        // Owning the game means there is no trial left to stop.
        if (isFullVersionUnlocked()) {
            Log.i(TAG, "stopTrial() skipped: full version already unlocked.");
            return TrialOutcome.alreadyUnlocked();
        }

        PlayTrialsBinding.Resolution current = resolution();
        if (current.state == PlayTrialsBinding.State.MISSING) {
            Log.w(TAG, "stopTrial() skipped: Play Trials SDK not on classpath.");
            return TrialOutcome.sdkUnavailable();
        }
        if (current.state == PlayTrialsBinding.State.INCOMPATIBLE || current.binding == null) {
            Log.e(TAG, "stopTrial() skipped: incompatible SDK (" + current.reason + ").");
            return TrialOutcome.failed(current.reason);
        }

        try {
            Log.i(TAG, "Progression milestone reached -> invoking " + current.binding.describe());
            current.binding.stopTrial(activity);

            // Always call stopTrial -- that is what a shipping game does, and it is what Play
            // records. But the call cannot report that nothing happened, so decide the local
            // UX separately: with no licensing client there will be no paywall, and withholding
            // the reward here would strand the player at a boss with a blank screen.
            if (!PlayTrialsBinding.isEnforcementAvailable()) {
                Log.w(TAG, "stopTrial() called, but Play's licensing client is absent: this build "
                        + "was not installed by Play, so no paywall will appear. Failing open.");
                return TrialOutcome.notEnforced("not installed by Play - trial cannot be enforced");
            }

            // Play now owns the screen and entitlement may change behind us, so drop the cache.
            cachedSdkEntitlement = null;
            return TrialOutcome.trialStopped();
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            Log.e(TAG, "stopTrial() failed; failing open so the player is not soft-locked.", cause);
            String reason = cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return TrialOutcome.failed(reason);
        }
    }

    /**
     * Re-reads entitlement from the SDK. Call on resume: the user may have completed (or
     * abandoned) a purchase while the game was backgrounded behind the paywall.
     */
    public boolean refreshEntitlement() {
        cachedSdkEntitlement = null;
        return isFullVersionUnlocked();
    }

    /** Clears the QA override. Used by the "Reset sandbox" control. */
    public void resetLocalOverrides() {
        prefs.edit().remove(KEY_LOCAL_UNLOCK).apply();
        cachedSdkEntitlement = null;
    }

    @Nullable
    private Boolean querySdkEntitlement() {
        Boolean cached = cachedSdkEntitlement;
        if (cached != null) {
            return cached;
        }

        PlayTrialsBinding.Resolution current = resolution();
        if (current.state != PlayTrialsBinding.State.BOUND || current.binding == null) {
            return null;
        }

        Boolean entitled;
        try {
            entitled = current.binding.isFullVersion();
        } catch (Throwable t) {
            Log.w(TAG, "Entitlement query failed; assuming the trial is still active.", unwrap(t));
            entitled = null;
        }
        cachedSdkEntitlement = entitled;
        return entitled;
    }

    /**
     * Name of the current process when it is <em>not</em> the app's main process, else null.
     *
     * <p>Play Trials must be driven from the main process. The main process is the one named
     * after the package; anything else came from an {@code android:process} attribute. Returns
     * null when the name cannot be determined (below API 28), since guessing would produce a
     * misleading warning.
     */
    @Nullable
    private String secondaryProcessName() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        String processName = Application.getProcessName();
        if (processName == null || processName.equals(appContext.getPackageName())) {
            return null;
        }
        return processName;
    }

    /** Reflection wraps the real failure; unwrap it so the log is useful. */
    @NonNull
    private static Throwable unwrap(@NonNull Throwable t) {
        if (t instanceof InvocationTargetException) {
            Throwable target = ((InvocationTargetException) t).getTargetException();
            if (target != null) {
                return target;
            }
        }
        return t;
    }
}
