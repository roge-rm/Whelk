# sshj and BouncyCastle find algorithms by name through reflection.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
# The terminal's JNI.
-keep class com.termux.terminal.JNI { *; }
# Vault classes are read and written by kotlinx.serialization.
-keepclassmembers class com.rm.whelk.core.** { *** Companion; }
-keep class com.rm.whelk.core.**$$serializer { *; }

# GSS-API and JAAS, which sshj can use but Whelk doesn't.
-dontwarn javax.security.auth.**
# yubikit reads its CBOR and JSON through reflection.
-keep class com.yubico.yubikit.** { *; }
-dontwarn com.yubico.yubikit.**
