# app/libs

Drop the partner-distributed **`play-trials-sdk.aar`** into this directory.

`app/build.gradle.kts` includes:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

so the AAR is picked up on the next sync with no further configuration.

## Behaviour without the AAR

The app still builds and runs. `GameTrialsManager` resolves the SDK entry point via
`Class.forName(...)`; when it is absent the manager reports
`TrialAvailability.SdkMissing` and the Level 5 boss falls through to the
"Full Game Unlocked! Boss Defeated!" path so the rest of the progression loop stays testable.

## Verifying the binding

Logcat tag `GameTrialsManager` prints the resolved class + method signature on first use:

```
adb logcat -s GameTrialsManager
```
