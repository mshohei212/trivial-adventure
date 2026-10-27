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

import com.google.android.play.trials.PlayTrials;

import java.lang.reflect.Field;

/**
 * Integrates the game with the Google Play Trials SDK (`com.google.android.play.trials.PlayTrials`).
 */
public final class GameTrialsManager {

    private static final String TAG = "GameTrialsManager";
    private static final String PREFS_NAME = "trivial_adventure_trials";
    private static final String KEY_LOCAL_UNLOCK = "local_unlock_override";

    /**
     * Classes injected into the APK by Google Play when Automatic Integrity Protection is enabled.
     */
    private static final String PLAY_TRIAL_CLIENT = "com.pairip.licensecheck.TrialClient";
    private static final String PLAY_LICENSE_CLIENT = "com.pairip.licensecheck.LicenseClient";
    private static final String LICENSE_STATE_FIELD = "licenseCheckState";
    private static final String STATE_FULL_CHECK_OK = "FULL_CHECK_OK";
    private static final String STATE_REPEATED_CHECK_REQUIRED = "REPEATED_CHECK_REQUIRED";

    private final Context appContext;
    private final SharedPreferences prefs;

    @Nullable
    private volatile Boolean cachedSdkEntitlement;

    public GameTrialsManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
     */
    public boolean isFullVersionUnlocked() {
        if (isUnlockedLocally()) {
            return true;
        }
        return Boolean.TRUE.equals(querySdkEntitlement());
    }

    /**
     * Current trial posture, used to drive the status banner and the milestone branch.
     */
    @NonNull
    public TrialAvailability availability() {
        if (isFullVersionUnlocked()) {
            return TrialAvailability.fullVersion();
        }
        return TrialAvailability.trialActive();
    }

    // ---------------------------------------------------------------------------------------
    // The milestone call
    // ---------------------------------------------------------------------------------------

    /**
     * Ends the progression trial and hands control to Play, which raises the system paywall over
     * the game.
     *
     * <p>Must be called on the main thread with the foreground {@link Activity}.
     */
    @MainThread
    @NonNull
    public TrialOutcome stopTrial(@NonNull Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            String thread = Thread.currentThread().getName();
            Log.e(TAG, "stopTrial() must be called on the main thread, but was called on '"
                    + thread + "'. Refusing to invoke the SDK; failing open.");
            return TrialOutcome.failed("called off the main thread (" + thread + ")");
        }

        String offendingProcess = secondaryProcessName();
        if (offendingProcess != null) {
            Log.w(TAG, "stopTrial() invoked from secondary process '" + offendingProcess
                    + "'. Play Trials expects the main process; the call may be ignored.");
        }

        if (isFullVersionUnlocked()) {
            Log.i(TAG, "stopTrial() skipped: full version already unlocked.");
            return TrialOutcome.alreadyUnlocked();
        }

        try {
            Log.i(TAG, "Progression milestone reached -> invoking PlayTrials.stopTrial(Activity)");
            PlayTrials.stopTrial(activity);

            if (isFullVersionUnlocked()) {
                Log.i(TAG, "stopTrial() resolved to full version unlocked.");
                return TrialOutcome.alreadyUnlocked();
            }
            if (!isEnforcementAvailable()) {
                Log.w(TAG, "stopTrial() called, but Play's licensing client is absent: this build "
                        + "was not installed by Play, so no paywall will appear. Failing open.");
                return TrialOutcome.notEnforced("not installed by Play - trial cannot be enforced");
            }

            cachedSdkEntitlement = null;
            return TrialOutcome.trialStopped();
        } catch (Throwable t) {
            Log.e(TAG, "stopTrial() failed; failing open so the player is not soft-locked.", t);
            String reason = t.getMessage() != null
                    ? t.getMessage()
                    : t.getClass().getSimpleName();
            return TrialOutcome.failed(reason);
        }
    }

    /**
     * Re-reads entitlement. Call on resume: the user may have completed (or abandoned) a
     * purchase while the game was backgrounded behind the paywall.
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
        if (Boolean.TRUE.equals(cached)) {
            return Boolean.TRUE;
        }

        Boolean entitled = queryPairipEntitlement();
        if (Boolean.TRUE.equals(entitled)) {
            cachedSdkEntitlement = Boolean.TRUE;
        }
        return entitled;
    }

    private static boolean isEnforcementAvailable() {
        try {
            Class.forName(PLAY_TRIAL_CLIENT);
            return !Boolean.TRUE.equals(queryPairipEntitlement());
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static Boolean queryPairipEntitlement() {
        try {
            Class<?> licenseClient = Class.forName(PLAY_LICENSE_CLIENT);
            Field stateField = licenseClient.getDeclaredField(LICENSE_STATE_FIELD);
            stateField.setAccessible(true);
            Object state = stateField.get(null);
            if (state == null) {
                return null;
            }
            String stateName = state.toString();
            if (STATE_FULL_CHECK_OK.equals(stateName)) {
                return Boolean.TRUE;
            }
            if (STATE_REPEATED_CHECK_REQUIRED.equals(stateName)) {
                return Boolean.FALSE;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

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
}
