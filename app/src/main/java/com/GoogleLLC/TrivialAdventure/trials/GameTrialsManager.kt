package com.GoogleLLC.TrivialAdventure.trials

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.edit
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val TAG = "GameTrialsManager"

/**
 * Single seam between the game and the Google Play Trials SDK (Try Before You Buy).
 *
 * Nothing above this class ever touches an SDK type: the ViewModel and the Compose UI only
 * ever see [TrialAvailability] and [TrialOutcome].
 *
 * ## Why reflection?
 *
 * `play-trials-sdk.aar` is distributed to partners and is not published to Google's Maven
 * repo, so a direct compile-time reference would make the whole project unbuildable on any
 * machine that does not have the AAR. Binding reflectively means:
 *
 *  * the sandbox compiles and runs on a bare emulator (reports [TrialAvailability.SdkMissing]);
 *  * dropping the AAR into `app/libs/` switches on the real paywall path with no code change;
 *  * signature drift between AAR drops (`stopTrial(Activity)` vs `stopTrial(Context)` vs a
 *    singleton `getInstance(context).stopTrial()`) is absorbed here instead of breaking the build.
 *
 * Once the coordinate is public this indirection can be deleted and replaced with the single
 * call it emulates:
 *
 * ```kotlin
 * PlayTrials.stopTrial(activity)
 * ```
 */
class GameTrialsManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Resolved once per process; the classpath cannot change at runtime. */
    private val resolution: Resolution by lazy { resolvePlayTrials(appContext) }

    /** Cached SDK entitlement answer; `null` means "not asked yet / no usable signal". */
    @Volatile
    private var cachedSdkEntitlement: Boolean? = null

    // ---------------------------------------------------------------------------------------
    // Entitlement
    // ---------------------------------------------------------------------------------------

    /**
     * QA override. Lets a tester simulate the post-purchase state without a real Play
     * transaction, and is what the app flips itself to once the paywall reports success.
     */
    var isUnlockedLocally: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_UNLOCK, false)
        set(value) {
            prefs.edit { putBoolean(KEY_LOCAL_UNLOCK, value) }
        }

    /**
     * True when the milestone boss should behave like a normal boss instead of ending the trial.
     *
     * The local QA override wins over the SDK so a tester can always force the "owns the game"
     * branch, even with a live trial build installed.
     */
    fun isFullVersionUnlocked(): Boolean {
        if (isUnlockedLocally) return true
        return querySdkEntitlement() == true
    }

    /**
     * Current trial posture, used to drive the status chip and the milestone branch.
     *
     * Entitlement is checked *before* SDK presence: owning the game is the dominant state, and
     * a locally-unlocked install with no AAR is still "full version" as far as the game logic
     * is concerned. (Checking SDK presence first would make the QA unlock toggle look inert.)
     */
    fun availability(): TrialAvailability {
        if (isFullVersionUnlocked()) return TrialAvailability.FullVersion

        return when (val current = resolution) {
            is Resolution.Missing -> TrialAvailability.SdkMissing
            is Resolution.Incompatible -> TrialAvailability.SdkIncompatible(current.reason)
            is Resolution.Bound -> TrialAvailability.TrialActive(current.binding.description)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The milestone call
    // ---------------------------------------------------------------------------------------

    /**
     * Ends the progression trial and hands control to Play, which raises the system paywall
     * over the game.
     *
     * Must be called on the main thread with the foreground [Activity]: the SDK needs a live
     * window token to attach the purchase flow to.
     *
     * @return [TrialOutcome.TrialStopped] when Play has taken over (do **not** grant the
     *   reward), otherwise a fail-open outcome and the caller should let the player through.
     */
    @MainThread
    fun stopTrial(activity: Activity): TrialOutcome {
        // Owning the game means there is no trial left to stop.
        if (isFullVersionUnlocked()) {
            Log.i(TAG, "stopTrial() skipped: full version already unlocked.")
            return TrialOutcome.AlreadyUnlocked
        }

        val binding = when (val current = resolution) {
            is Resolution.Bound -> current.binding
            is Resolution.Missing -> {
                Log.w(TAG, "stopTrial() skipped: Play Trials SDK not on classpath.")
                return TrialOutcome.SdkUnavailable
            }
            is Resolution.Incompatible -> {
                Log.e(TAG, "stopTrial() skipped: incompatible SDK (${current.reason}).")
                return TrialOutcome.Failed(current.reason)
            }
        }

        return try {
            Log.i(TAG, "Progression milestone reached -> invoking ${binding.description}")
            binding.stopTrial(activity)
            // Play now owns the screen and entitlement may change behind us, so drop the cache.
            cachedSdkEntitlement = null
            TrialOutcome.TrialStopped
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.e(TAG, "stopTrial() failed; failing open so the player is not soft-locked.", cause)
            TrialOutcome.Failed(cause.message ?: cause::class.java.simpleName)
        }
    }

    /**
     * Re-reads entitlement from the SDK. Call on resume: the user may have completed (or
     * abandoned) a purchase while the game was backgrounded behind the paywall.
     */
    fun refreshEntitlement(): Boolean {
        cachedSdkEntitlement = null
        return isFullVersionUnlocked()
    }

    /** Clears the QA override. Used by the "Reset sandbox" control. */
    fun resetLocalOverrides() {
        prefs.edit { remove(KEY_LOCAL_UNLOCK) }
        cachedSdkEntitlement = null
    }

    private fun querySdkEntitlement(): Boolean? {
        cachedSdkEntitlement?.let { return it }
        val binding = (resolution as? Resolution.Bound)?.binding ?: return null
        val entitled = try {
            binding.isFullVersion()
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "Entitlement query failed; assuming the trial is still active.", cause)
            null
        }
        cachedSdkEntitlement = entitled
        return entitled
    }

    private companion object {
        const val PREFS_NAME = "trivial_adventure_trials"
        const val KEY_LOCAL_UNLOCK = "local_unlock_override"
    }
}

// ===========================================================================================
// Reflective binding to play-trials-sdk.aar
// ===========================================================================================

private sealed interface Resolution {
    data class Bound(val binding: PlayTrialsBinding) : Resolution
    data object Missing : Resolution
    data class Incompatible(val reason: String) : Resolution
}

/**
 * Known entry-point class names across SDK drops, most-likely first. The first one that loads
 * wins; if none load, the AAR simply is not on the classpath.
 */
private val ENTRY_POINT_CLASSES = listOf(
    "com.google.android.play.trials.PlayTrials",
    "com.google.android.play.trials.PlayTrialsClient",
    "com.google.android.play.core.trials.PlayTrials",
    "com.google.android.play.tbyb.PlayTrials",
)

/** Method names that terminate a progression trial, most-likely first. */
private val STOP_TRIAL_METHODS = listOf(
    "stopTrial",
    "endTrial",
    "finishTrial",
    "notifyTrialComplete",
    "onTrialMilestoneReached",
)

/** Static factories used by singleton-style SDK builds. */
private val FACTORY_METHODS = listOf("getInstance", "create", "newInstance", "getClient")

/** Entitlement probes: method name -> how to read the result, least ambiguous first. */
private val ENTITLEMENT_PROBES = listOf(
    "isFullVersionUnlocked" to EntitlementSemantics.TRUE_MEANS_UNLOCKED,
    "isFullVersion" to EntitlementSemantics.TRUE_MEANS_UNLOCKED,
    "isPurchased" to EntitlementSemantics.TRUE_MEANS_UNLOCKED,
    "isLicensed" to EntitlementSemantics.TRUE_MEANS_UNLOCKED,
    "isTrialActive" to EntitlementSemantics.TRUE_MEANS_TRIAL,
    "isInTrial" to EntitlementSemantics.TRUE_MEANS_TRIAL,
    "isTrial" to EntitlementSemantics.TRUE_MEANS_TRIAL,
    "getTrialState" to EntitlementSemantics.PARSE_STATE,
    "getTrialStatus" to EntitlementSemantics.PARSE_STATE,
)

/**
 * A method is safely invocable here only if every parameter is Context-assignable, because a
 * Context (or Activity) is the only argument this shim is able to supply.
 */
private fun Method.takesOnlyContexts(): Boolean =
    parameterTypes.all { Context::class.java.isAssignableFrom(it) }

/**
 * Builds the argument array for a Context-only method. Prefers [activity] wherever the
 * parameter permits it, since the SDK needs a window token to attach the paywall.
 */
private fun Method.contextArgs(activity: Activity?, appContext: Context): Array<Any> =
    parameterTypes.map { param ->
        if (Activity::class.java.isAssignableFrom(param)) {
            activity ?: error("$name requires an Activity but none was supplied")
        } else {
            // Activity is-a Context, so pass it through when we have one.
            activity ?: appContext
        }
    }.toTypedArray()

/**
 * Locates the SDK entry point and binds the methods this game needs.
 *
 * Resolution order:
 *  1. a static `stopTrial(...)` (utility-style API), otherwise
 *  2. an instance `stopTrial(...)` reached through a static factory or a no-arg constructor.
 */
private fun resolvePlayTrials(appContext: Context): Resolution {
    val loader = GameTrialsManager::class.java.classLoader
        ?: return Resolution.Incompatible("no class loader available")

    val entryPoint = ENTRY_POINT_CLASSES.firstNotNullOfOrNull { name ->
        try {
            Class.forName(name, /* initialize = */ true, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (t: Throwable) {
            // The class exists but its static initializer failed. That is a genuine
            // integration problem, not a missing AAR, so surface it in the log.
            Log.e(TAG, "Found $name but failed to initialize it", t)
            null
        }
    } ?: run {
        Log.i(TAG, "Play Trials SDK not found on classpath; running unrestricted.")
        return Resolution.Missing
    }

    val methods = entryPoint.methods

    fun findStop(static: Boolean): Method? = STOP_TRIAL_METHODS.firstNotNullOfOrNull { name ->
        methods.firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) == static && it.takesOnlyContexts()
        }
    }

    // --- Path 1: static utility API -------------------------------------------------------
    val staticStop = findStop(static = true)
    if (staticStop != null) {
        return Resolution.Bound(
            PlayTrialsBinding(
                entryPoint = entryPoint,
                receiver = null,
                stopTrialMethod = staticStop,
                entitlementProbe = findEntitlementProbe(methods, static = true),
                appContext = appContext,
            )
        )
    }

    // --- Path 2: singleton API ------------------------------------------------------------
    val instanceStop = findStop(static = false)
        ?: return Resolution.Incompatible(
            "${entryPoint.name} exposes no ${STOP_TRIAL_METHODS.first()}(Context/Activity)"
        )

    val receiver = instantiate(entryPoint, methods, appContext)
        ?: return Resolution.Incompatible(
            "${entryPoint.name}.${instanceStop.name} is an instance method but no usable " +
                "factory or no-arg constructor was found"
        )

    return Resolution.Bound(
        PlayTrialsBinding(
            entryPoint = entryPoint,
            receiver = receiver,
            stopTrialMethod = instanceStop,
            entitlementProbe = findEntitlementProbe(methods, static = false),
            appContext = appContext,
        )
    )
}

/**
 * Obtains a singleton instance via a static factory (with or without a Context parameter),
 * falling back to a public no-arg constructor.
 */
private fun instantiate(entryPoint: Class<*>, methods: Array<Method>, appContext: Context): Any? {
    val factory = FACTORY_METHODS.firstNotNullOfOrNull { name ->
        methods.firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) && it.takesOnlyContexts()
        }
    }

    if (factory != null) {
        runCatching { factory.invoke(null, *factory.contextArgs(activity = null, appContext = appContext)) }
            .onFailure { Log.w(TAG, "Factory ${factory.name} failed", it) }
            .getOrNull()
            ?.let { return it }
    }

    return runCatching { entryPoint.getDeclaredConstructor().newInstance() }
        .onFailure { Log.w(TAG, "No usable no-arg constructor on ${entryPoint.name}", it) }
        .getOrNull()
}

private fun findEntitlementProbe(methods: Array<Method>, static: Boolean): EntitlementProbe? {
    val probe = ENTITLEMENT_PROBES.firstNotNullOfOrNull { (name, semantics) ->
        methods.firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) == static && it.takesOnlyContexts()
        }?.let { EntitlementProbe(it, semantics) }
    }
    if (probe == null) {
        Log.w(TAG, "SDK exposes no entitlement probe; relying on the local unlock flag only.")
    }
    return probe
}

private class EntitlementProbe(val method: Method, val semantics: EntitlementSemantics)

/**
 * A resolved, invocable handle on the SDK's trial entry point.
 *
 * @param receiver `null` for a static/utility-style API, otherwise the singleton instance.
 */
private class PlayTrialsBinding(
    private val entryPoint: Class<*>,
    private val receiver: Any?,
    private val stopTrialMethod: Method,
    private val entitlementProbe: EntitlementProbe?,
    private val appContext: Context,
) {

    /** e.g. `PlayTrials.stopTrial(Activity) [static]`. Logged and shown in the QA chip. */
    val description: String by lazy {
        val params = stopTrialMethod.parameterTypes.joinToString { it.simpleName }
        val kind = if (receiver == null) "static" else "singleton"
        "${entryPoint.simpleName}.${stopTrialMethod.name}($params) [$kind]"
    }

    fun stopTrial(activity: Activity) {
        stopTrialMethod.invoke(receiver, *stopTrialMethod.contextArgs(activity, appContext))
    }

    /** @return `true`/`false` when the SDK answers, `null` when it exposes no usable signal. */
    fun isFullVersion(): Boolean? {
        val probe = entitlementProbe ?: return null
        // No Activity here; entitlement checks only ever need a Context.
        val raw = probe.method.invoke(receiver, *probe.method.contextArgs(null, appContext))
            ?: return null
        return probe.semantics.interpret(raw)
    }
}

/** How to read the value returned by an entitlement probe. */
private enum class EntitlementSemantics {
    /** `true` == the user owns the full game. */
    TRUE_MEANS_UNLOCKED,

    /** `true` == still in trial, so "unlocked" is the inverse. */
    TRUE_MEANS_TRIAL,

    /** Enum/int/string state object; inspect its string form. */
    PARSE_STATE;

    fun interpret(raw: Any): Boolean? = when (this) {
        TRUE_MEANS_UNLOCKED -> raw as? Boolean
        TRUE_MEANS_TRIAL -> (raw as? Boolean)?.not()
        PARSE_STATE -> {
            val token = raw.toString().uppercase()
            when {
                UNLOCKED_TOKENS.any(token::contains) -> true
                TRIAL_TOKENS.any(token::contains) -> false
                else -> null
            }
        }
    }

    private companion object {
        val UNLOCKED_TOKENS = listOf("FULL", "PURCHASED", "UNLOCKED", "OWNED", "ENTITLED")
        val TRIAL_TOKENS = listOf("TRIAL", "LOCKED", "EXPIRED")
    }
}
