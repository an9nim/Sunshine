# Room and Compose ship consumer rules with the libraries. BouncyCastle is
# referenced directly, so R8 keeps the low-level X25519/HKDF classes it uses.
-keepattributes *Annotation*

# Room looks up the generated database implementation by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# BouncyCastle's jar also contains JCE provider code with optional JDK
# dependencies that Android does not ship. The app uses only the lightweight
# org.bouncycastle.crypto API.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn java.beans.**

# Kotlin coroutines ships its own consumer rules (MainDispatcherFactory and
# volatile fields). Keep the Android dispatcher factory name stable as a
# defensive measure for ServiceLoader-based lookup.
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory
