# R8 keep rules for Callix PBX.
#
# The release build shrinks the app. Three things here are reached in ways R8
# cannot see, and shrinking them produces a crash or, worse, a silently empty
# field -- so each one is pinned deliberately.

# ── Linphone ───────────────────────────────────────────────────────────────────
# The Java bindings are entered from native code (JNI) by class and method name,
# and a few are resolved reflectively. R8 sees no Java caller for most of them.
-keep class org.linphone.** { *; }
-keep interface org.linphone.** { *; }
-dontwarn org.linphone.**

# ── Gson / Retrofit models ─────────────────────────────────────────────────────
# Gson maps JSON to fields through @SerializedName. R8 cannot see that link, and a
# removed or renamed field shows up as a value that is simply missing at runtime --
# no crash, just wrong data, which is the hardest kind of bug to find.
-keep class com.callix.pbx.data.model.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Retrofit's own generics plumbing.
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp probes for optional TLS providers that are not present on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Tink (via androidx.security:security-crypto) ───────────────────────────────
# EncryptedSharedPreferences is built on Tink, which instantiates its primitives by
# reflection from class names stored in the keyset. Shrink those and the encrypted
# preference file cannot be opened at all.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── Logging ────────────────────────────────────────────────────────────────────
# Timber's debug logging is compiled away in release: the calls have no side effect,
# so keeping them would only force the arguments to be evaluated.
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}

# ── Defensive ──────────────────────────────────────────────────────────────────
# These libraries are no longer direct dependencies. If a transitive reference
# reappears, it must not fail the build.
-dontwarn androidx.room.**
-dontwarn androidx.work.**
-dontwarn com.bumptech.glide.**
-dontwarn com.airbnb.lottie.**
