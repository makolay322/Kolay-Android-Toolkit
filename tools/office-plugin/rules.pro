# R8 rules for the old-Office plugin (tools/office-plugin/build.sh). Shrink only: no obfuscation, no optimisation,
# so POI behaves exactly as upstream; only code unreachable from PoiProvider is dropped.
-dontobfuscate
-dontoptimize
-ignorewarnings
-dontwarn **
-keep public class app.kolay.office.PoiProvider { public *; }
# POI instantiates records by reflection (record type -> constructor) and picks its logger by class name.
-keep class org.apache.poi.hssf.record.** { *; }
-keep class org.apache.poi.hslf.record.** { *; }
-keep class org.apache.poi.ddf.** { *; }
-keep class org.apache.poi.util.NullLogger { *; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
