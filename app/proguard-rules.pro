-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Optional errorprone annotations referenced by dependencies; safe to strip.
-dontwarn com.google.errorprone.annotations.**

# WorkManager uses Room at runtime; its generated database implementation is created by reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
