# Tesseract / Leptonica: native code looks up these classes, fields and callbacks by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Readable stack traces in logcat (personal app; name obfuscation saves little here).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# smbj (Samba): its event bus (mbassador) finds @Handler methods by reflection, so those methods (and their annotations) and the
# bus itself are kept; the rest of smbj/asn-one has no reflection of its own (only SmbConfig's default Kerberos factory, which is
# replaced by NtlmAuthenticator) and is shrunk like any code (~300 KB less dex). BouncyCastle is used only through smbj's direct
# calls (BCSecurityProvider, no JCA lookup), so R8 may shrink it. Kerberos/GSS and slf4j bindings are absent on Android.
-keepclassmembers class * { @net.engio.mbassy.listener.Handler <methods>; }
-keepattributes *Annotation*
-keep class net.engio.mbassy.** { *; }
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**

# PdfBox-Android (PDF düzenle): the AAR's own rules keep its reflective parts. Its BouncyCastle build is excluded in favour of
# smbj's (same packages); the public-key (certificate) encryption parts it would need from bcpkix and the optional JPEG 2000
# decoder are absent, so those PDFs are not supported.
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
# The AAR's own rules are ignored (build.gradle: optimization.keepRules.ignoreFrom): they keep the whole tagged-PDF
# structure package (documentinterchange.**), which the editor never uses. Only its reflective part is needed here:
# SecurityHandlerFactory creates the (standard password) security handler through its constructor.
-keep class * extends com.tom_roush.pdfbox.pdmodel.encryption.SecurityHandler { public <init>(...); }
