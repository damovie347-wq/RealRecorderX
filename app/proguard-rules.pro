# RecorderX release shrinking rules.
#
# The app deliberately has almost no reflection-based libraries (no Gson,
# Retrofit, Room, etc.), so the default AGP/R8 rules plus AndroidX's bundled
# consumer-rules already cover the vast majority of this project. What's left
# here is just the handful of RecorderX classes that are touched from places
# R8 can't trace statically.

# Keep the settings model + enums: their names/ordinals are persisted to
# SharedPreferences by ordinal-independent String keys, but keep them intact
# anyway so a future move to reflection-based (de)serialization stays safe.
-keep class com.recorderx.app.settings.** { *; }

# Keep the Service and its public lifecycle surface -- it's referenced from
# the manifest and started via reflection-adjacent Intent component resolution.
-keep class com.recorderx.app.service.RecordingService { *; }

# Standard, well-known Android warnings that are safe to ignore for a project
# with no annotation-processor-generated code.
-dontwarn org.jetbrains.annotations.**
