# Standard ProGuard rules for Android
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep our models if we had any, but for now we use JSON directly
# -keep class com.example.pikudalert.models.** { *; }

# Keep GMS (Google Play Services)
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep Kotlin specific
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Add any custom rules for libraries used here
