package com.GoogleLLC.TrivialAdventure.trials;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A resolved, invocable handle on the Play Trials SDK entry point.
 *
 * <p>Package-private: everything outside this package goes through {@link GameTrialsManager}.
 *
 * <p>Resolution tries two shapes, in order:
 * <ol>
 *   <li>a static {@code stopTrial(...)} (utility-style API);</li>
 *   <li>an instance {@code stopTrial(...)} reached through a static factory or a no-arg
 *       constructor (singleton-style API).</li>
 * </ol>
 */
final class PlayTrialsBinding {

    private static final String TAG = "GameTrialsManager";

    /**
     * Known entry-point class names across SDK drops, most-likely first. The first one that
     * loads wins; if none load, the AAR simply is not on the classpath.
     */
    private static final String[] ENTRY_POINT_CLASSES = {
            "com.google.android.play.trials.PlayTrials",
            "com.google.android.play.trials.PlayTrialsClient",
            "com.google.android.play.core.trials.PlayTrials",
            "com.google.android.play.tbyb.PlayTrials",
    };

    /** Method names that terminate a progression trial, most-likely first. */
    private static final String[] STOP_TRIAL_METHODS = {
            "stopTrial",
            "endTrial",
            "finishTrial",
            "notifyTrialComplete",
            "onTrialMilestoneReached",
    };

    /** Static factories used by singleton-style SDK builds. */
    private static final String[] FACTORY_METHODS = {
            "getInstance", "create", "newInstance", "getClient",
    };

    /** Entitlement probes: method name to result interpretation, least ambiguous first. */
    private static final Map<String, EntitlementSemantics> ENTITLEMENT_PROBES =
            new LinkedHashMap<>();

    static {
        ENTITLEMENT_PROBES.put("isFullVersionUnlocked", EntitlementSemantics.TRUE_MEANS_UNLOCKED);
        ENTITLEMENT_PROBES.put("isFullVersion", EntitlementSemantics.TRUE_MEANS_UNLOCKED);
        ENTITLEMENT_PROBES.put("isPurchased", EntitlementSemantics.TRUE_MEANS_UNLOCKED);
        ENTITLEMENT_PROBES.put("isLicensed", EntitlementSemantics.TRUE_MEANS_UNLOCKED);
        ENTITLEMENT_PROBES.put("isTrialActive", EntitlementSemantics.TRUE_MEANS_TRIAL);
        ENTITLEMENT_PROBES.put("isInTrial", EntitlementSemantics.TRUE_MEANS_TRIAL);
        ENTITLEMENT_PROBES.put("isTrial", EntitlementSemantics.TRUE_MEANS_TRIAL);
        ENTITLEMENT_PROBES.put("getTrialState", EntitlementSemantics.PARSE_STATE);
        ENTITLEMENT_PROBES.put("getTrialStatus", EntitlementSemantics.PARSE_STATE);
    }

    // -------------------------------------------------------------------------------------
    // Resolution result
    // -------------------------------------------------------------------------------------

    enum State { BOUND, MISSING, INCOMPATIBLE }

    static final class Resolution {
        final State state;
        @Nullable final PlayTrialsBinding binding;
        @NonNull final String reason;

        private Resolution(State state, @Nullable PlayTrialsBinding binding, @NonNull String reason) {
            this.state = state;
            this.binding = binding;
            this.reason = reason;
        }

        static Resolution bound(@NonNull PlayTrialsBinding binding) {
            return new Resolution(State.BOUND, binding, "");
        }

        static Resolution missing() {
            return new Resolution(State.MISSING, null, "");
        }

        static Resolution incompatible(@NonNull String reason) {
            return new Resolution(State.INCOMPATIBLE, null, reason);
        }
    }

    // -------------------------------------------------------------------------------------
    // Instance
    // -------------------------------------------------------------------------------------

    private final Class<?> entryPoint;
    /** {@code null} for a static/utility-style API, otherwise the singleton instance. */
    @Nullable private final Object receiver;
    private final Method stopTrialMethod;
    @Nullable private final EntitlementProbe entitlementProbe;
    private final Context appContext;

    private PlayTrialsBinding(
            Class<?> entryPoint,
            @Nullable Object receiver,
            Method stopTrialMethod,
            @Nullable EntitlementProbe entitlementProbe,
            Context appContext) {
        this.entryPoint = entryPoint;
        this.receiver = receiver;
        this.stopTrialMethod = stopTrialMethod;
        this.entitlementProbe = entitlementProbe;
        this.appContext = appContext;
    }

    /** e.g. {@code PlayTrials.stopTrial(Activity) [static]}. Logged and shown in the QA banner. */
    @NonNull
    String describe() {
        StringBuilder params = new StringBuilder();
        Class<?>[] types = stopTrialMethod.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                params.append(", ");
            }
            params.append(types[i].getSimpleName());
        }
        return entryPoint.getSimpleName()
                + "." + stopTrialMethod.getName()
                + "(" + params + ")"
                + " [" + (receiver == null ? "static" : "singleton") + "]";
    }

    /**
     * Classes injected into the APK by Play at download time when Automatic Integrity Protection
     * is enabled. {@code TrialClient} forwards {@code stopTrial} to {@code LicenseClient}, which
     * tracks the live Play licensing state in {@code licenseCheckState}.
     */
    private static final String PLAY_TRIAL_CLIENT = "com.pairip.licensecheck.TrialClient";
    private static final String PLAY_LICENSE_CLIENT = "com.pairip.licensecheck.LicenseClient";
    private static final String LICENSE_STATE_FIELD = "licenseCheckState";
    private static final String STATE_FULL_CHECK_OK = "FULL_CHECK_OK";
    private static final String STATE_REPEATED_CHECK_REQUIRED = "REPEATED_CHECK_REQUIRED";

    /**
     * Whether Play can actually enforce a trial stop in this build.
     *
     * <p>{@code PlayTrials.stopTrial} resolves {@link #PLAY_TRIAL_CLIENT} reflectively and, if
     * it is missing, logs
     * <em>"...was not found, this may be a local build. Trial end only has an effect for
     * artifacts downloaded from Play"</em> and returns normally. Because it reports no failure,
     * a caller that trusted the return value would withhold the milestone reward and leave the
     * player facing a boss with no paywall. Testing for the class ourselves is the only way to
     * tell the two situations apart.
     *
     * <p>This is a heuristic on a Play implementation detail, so it is only ever used to decide
     * whether to <em>fail open</em> -- never to grant entitlement.
     */
    static boolean isEnforcementAvailable() {
        try {
            Class.forName(PLAY_TRIAL_CLIENT);
            return !Boolean.TRUE.equals(queryPairipEntitlement());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads Play's injected {@code LicenseClient.licenseCheckState} when the SDK AAR itself
     * exposes no public entitlement getter (as in {@code play-trials-sdk} 1.0.0).
     *
     * <ul>
     *   <li>{@code FULL_CHECK_OK}: Play licensing succeeded with no trial repeat metadata, i.e.
     *       the user owns the full version (purchased or Internal Testing license).</li>
     *   <li>{@code REPEATED_CHECK_REQUIRED}: Play licensing succeeded with trial repeat metadata,
     *       i.e. the user is in an active trial.</li>
     *   <li>{@code CHECK_REQUIRED} / {@code LOCAL_CHECK_OK} / {@code LOCAL_CHECK_REPORTED}: the
     *       asynchronous IPC with {@code com.android.vending} has not finished yet.</li>
     * </ul>
     */
    @Nullable
    static Boolean queryPairipEntitlement() {
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

    void stopTrial(@NonNull Activity activity) throws Exception {
        stopTrialMethod.invoke(receiver, contextArgs(stopTrialMethod, activity, appContext));
    }

    /** @return {@code true}/{@code false} when the SDK answers, {@code null} when it cannot. */
    @Nullable
    Boolean isFullVersion() throws Exception {
        if (entitlementProbe != null) {
            // No Activity here; entitlement checks only ever need a Context.
            Object raw = entitlementProbe.method.invoke(
                    receiver, contextArgs(entitlementProbe.method, null, appContext));
            if (raw != null) {
                Boolean interpreted = entitlementProbe.semantics.interpret(raw);
                if (interpreted != null) {
                    return interpreted;
                }
            }
        }
        return queryPairipEntitlement();
    }

    // -------------------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------------------

    @NonNull
    static Resolution resolve(@NonNull Context appContext) {
        ClassLoader loader = PlayTrialsBinding.class.getClassLoader();
        if (loader == null) {
            return Resolution.incompatible("no class loader available");
        }

        Class<?> entryPoint = null;
        for (String name : ENTRY_POINT_CLASSES) {
            try {
                entryPoint = Class.forName(name, /* initialize= */ true, loader);
                break;
            } catch (ClassNotFoundException e) {
                // Expected when this SDK drop uses a different package.
            } catch (Throwable t) {
                // The class exists but its static initializer failed. That is a genuine
                // integration problem, not a missing AAR, so surface it in the log.
                Log.e(TAG, "Found " + name + " but failed to initialize it", t);
            }
        }

        if (entryPoint == null) {
            Log.i(TAG, "Play Trials SDK not found on classpath; running unrestricted.");
            return Resolution.missing();
        }

        Method[] methods = entryPoint.getMethods();

        // --- Path 1: static utility API ---------------------------------------------------
        Method staticStop = findStopMethod(methods, /* wantStatic= */ true);
        if (staticStop != null) {
            return Resolution.bound(new PlayTrialsBinding(
                    entryPoint,
                    /* receiver= */ null,
                    staticStop,
                    findProbe(methods, /* wantStatic= */ true),
                    appContext));
        }

        // --- Path 2: singleton API --------------------------------------------------------
        Method instanceStop = findStopMethod(methods, /* wantStatic= */ false);
        if (instanceStop == null) {
            return Resolution.incompatible(
                    entryPoint.getName() + " exposes no " + STOP_TRIAL_METHODS[0] + "(Context/Activity)");
        }

        Object receiver = instantiate(entryPoint, methods, appContext);
        if (receiver == null) {
            return Resolution.incompatible(entryPoint.getName() + "." + instanceStop.getName()
                    + " is an instance method but no usable factory or no-arg constructor was found");
        }

        return Resolution.bound(new PlayTrialsBinding(
                entryPoint,
                receiver,
                instanceStop,
                findProbe(methods, /* wantStatic= */ false),
                appContext));
    }

    @Nullable
    private static Method findStopMethod(Method[] methods, boolean wantStatic) {
        for (String name : STOP_TRIAL_METHODS) {
            for (Method m : methods) {
                if (m.getName().equals(name)
                        && Modifier.isStatic(m.getModifiers()) == wantStatic
                        && takesOnlyContexts(m)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** An entitlement probe method paired with how to read its return value. */
    private static final class EntitlementProbe {
        final Method method;
        final EntitlementSemantics semantics;

        EntitlementProbe(Method method, EntitlementSemantics semantics) {
            this.method = method;
            this.semantics = semantics;
        }
    }

    @Nullable
    private static EntitlementProbe findProbe(Method[] methods, boolean wantStatic) {
        for (Map.Entry<String, EntitlementSemantics> entry : ENTITLEMENT_PROBES.entrySet()) {
            for (Method m : methods) {
                if (m.getName().equals(entry.getKey())
                        && Modifier.isStatic(m.getModifiers()) == wantStatic
                        && takesOnlyContexts(m)) {
                    return new EntitlementProbe(m, entry.getValue());
                }
            }
        }
        Log.w(TAG, "SDK exposes no entitlement probe; relying on the local unlock flag only.");
        return null;
    }

    /**
     * Obtains a singleton via a static factory (with or without a Context parameter), falling
     * back to a public no-arg constructor.
     */
    @Nullable
    private static Object instantiate(Class<?> entryPoint, Method[] methods, Context appContext) {
        for (String name : FACTORY_METHODS) {
            for (Method m : methods) {
                if (m.getName().equals(name)
                        && Modifier.isStatic(m.getModifiers())
                        && takesOnlyContexts(m)) {
                    try {
                        Object instance = m.invoke(null, contextArgs(m, null, appContext));
                        if (instance != null) {
                            return instance;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Factory " + m.getName() + " failed", t);
                    }
                }
            }
        }

        try {
            Constructor<?> ctor = entryPoint.getDeclaredConstructor();
            return ctor.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "No usable no-arg constructor on " + entryPoint.getName(), t);
            return null;
        }
    }

    /**
     * A method is safely invocable here only if every parameter is Context-assignable, because
     * a Context (or Activity) is the only argument this shim is able to supply.
     */
    private static boolean takesOnlyContexts(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (!Context.class.isAssignableFrom(type)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the argument array for a Context-only method. Prefers the Activity wherever the
     * parameter permits it, since the SDK needs a window token to attach the paywall.
     */
    private static Object[] contextArgs(Method method, @Nullable Activity activity, Context appContext) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (Activity.class.isAssignableFrom(types[i])) {
                if (activity == null) {
                    throw new IllegalStateException(
                            method.getName() + " requires an Activity but none was supplied");
                }
                args[i] = activity;
            } else {
                // Activity is-a Context, so pass it through when we have one.
                args[i] = activity != null ? activity : appContext;
            }
        }
        return args;
    }

    /** How to read the value returned by an entitlement probe. */
    enum EntitlementSemantics {
        /** {@code true} means the user owns the full game. */
        TRUE_MEANS_UNLOCKED,
        /** {@code true} means still in trial, so "unlocked" is the inverse. */
        TRUE_MEANS_TRIAL,
        /** Enum/int/string state object; inspect its string form. */
        PARSE_STATE;

        private static final String[] UNLOCKED_TOKENS =
                {"FULL", "PURCHASED", "UNLOCKED", "OWNED", "ENTITLED"};
        private static final String[] TRIAL_TOKENS = {"TRIAL", "LOCKED", "EXPIRED"};

        @Nullable
        Boolean interpret(@NonNull Object raw) {
            switch (this) {
                case TRUE_MEANS_UNLOCKED:
                    return raw instanceof Boolean ? (Boolean) raw : null;
                case TRUE_MEANS_TRIAL:
                    return raw instanceof Boolean ? !((Boolean) raw) : null;
                case PARSE_STATE:
                default:
                    String token = raw.toString().toUpperCase(java.util.Locale.ROOT);
                    for (String t : UNLOCKED_TOKENS) {
                        if (token.contains(t)) {
                            return Boolean.TRUE;
                        }
                    }
                    for (String t : TRIAL_TOKENS) {
                        if (token.contains(t)) {
                            return Boolean.FALSE;
                        }
                    }
                    return null;
            }
        }
    }
}
