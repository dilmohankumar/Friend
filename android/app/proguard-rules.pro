# WebRTC (JNI)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Socket.IO / engine.io
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# kotlinx.serialization: keep generated serializers for our DTOs
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.situationship.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.situationship.app.**$$serializer { *; }
# Retrofit service interfaces with suspend functions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
