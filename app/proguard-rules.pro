# Tesseract / Leptonica: native code looks up these classes, fields and callbacks by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Readable stack traces in logcat (personal app; name obfuscation saves little here).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# smbj (Samba): its event bus (mbassador) finds @Handler methods by reflection; keep smbj and the bus whole. BouncyCastle is used
# only through smbj's direct calls (BCSecurityProvider, no JCA lookup), so R8 may shrink it. Kerberos/GSS and slf4j bindings are absent on Android.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
