# CineStream ProGuard & R8 Optimization Rules

# 1. WebView JavaScript Interface (Crucial for VideoExtractor)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 2. Domain & Data Models (Moshi / Firestore / JSON Serialization)
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** {
    <init>(...);
    <init>();
    *;
}
-keep class com.example.domain.models.** { *; }
-keep class com.example.data.remote.Tmdb** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# 3. Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Entity class * { *; }

# 4. Media3 & ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 6. Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# 7. Kotlin Coroutines & Reflection
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# 8. Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# 9. YouTube Player
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# 10. StartApp SDK
-keep class com.startapp.** { *; }
-dontwarn com.startapp.**

# 11. Cloudinary
-keep class com.cloudinary.** { *; }
-dontwarn com.cloudinary.**
