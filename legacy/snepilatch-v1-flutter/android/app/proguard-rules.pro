# Flutter specific rules
-keep class io.flutter.** { *; }
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.embedding.** { *; }

# audio_service plugin
-keep class com.ryanheise.audioservice.** { *; }
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
-keep class android.support.v4.media.session.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep attributes for debugging
-keepattributes SourceFile,LineNumberTable,*Annotation*

# Prevent stripping of AudioService components
-keep class com.ryanheise.audioservice.AudioService { *; }
-keep class com.ryanheise.audioservice.AudioServiceActivity { *; }
-keep class com.ryanheise.audioservice.MediaButtonReceiver { *; }
-keep class com.ryanheise.audioservice.AudioServiceFragment { *; }
-keep class com.ryanheise.audioservice.AudioServicePlugin { *; }

# Keep all services
-keep class * extends android.app.Service { *; }

# Keep broadcast receivers
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep media session callbacks
-keep class * extends android.media.session.MediaSession$Callback { *; }
-keep class * extends android.support.v4.media.session.MediaSessionCompat$Callback { *; }

# Keep notification related classes
-keep class androidx.core.app.NotificationCompat { *; }
-keep class androidx.core.app.NotificationCompat$* { *; }
-keep class android.app.Notification { *; }
-keep class android.app.Notification$* { *; }

# Keep media browser service
-keep class androidx.media.MediaBrowserServiceCompat { *; }
-keep class android.service.media.MediaBrowserService { *; }

# flutter_inappwebview plugin
-keep class com.pichillilorenzo.flutter_inappwebview.** { *; }

# General Android rules
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}