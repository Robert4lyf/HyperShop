# Room's own consumer rules already keep what its generated code needs; only the
# paging bridge, which this app does not use, has to be silenced.
-dontwarn androidx.room.paging.**

# Garmin Connect IQ Mobile SDK. It reaches Garmin Connect Mobile over AIDL and
# reflects over its own types, so R8 must not rename or drop them.
-keep class com.garmin.android.connectiq.** { *; }
-dontwarn com.garmin.android.connectiq.**
