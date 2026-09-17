# The Trials SDK is resolved reflectively at runtime by GameTrialsManager. R8 must not
# rename or strip it when the AAR is on the classpath, otherwise Class.forName() fails.
-keep class com.google.android.play.trials.** { *; }
-keep class com.google.android.play.core.trials.** { *; }
-dontwarn com.google.android.play.trials.**
-dontwarn com.google.android.play.core.trials.**
