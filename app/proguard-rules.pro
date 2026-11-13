# The app is optimised and shrunk for speed, but class names are kept: several libraries look
# classes up by name, and readable names make crash reports usable.
-dontobfuscate

# kotlinx.serialization: keep generated serializers for the data model.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep class app.mygachalist.data.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit service interface and coroutine return types (R8 full mode).
-keep interface app.mygachalist.data.remote.** { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# WorkManager creates the worker by class name.
-keep class app.mygachalist.work.RefreshWorker { <init>(...); }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
