#!/usr/bin/env bash
# Builds the old-Office plugin (Apache POI 4.1.2 + PoiProvider) as one dex file for Kolay's viewer.
# Usage: tools/office-plugin/build.sh [out_dir]   (default: build/office-plugin)
# Needs: JDK 17 (javac/java), Android SDK with platforms/android-34 and build-tools/34.0.0 (d8.jar also contains R8).
#   JAVA_HOME / ANDROID_HOME as for the app build. Network: Maven Central (jars are checked against the pinned SHA-256 below).
# Output: kolay-office-plugin.dex + its size and SHA-256. Those two values are pinned in app/.../viewer/OfficePlugin.java;
# a rebuilt dex is only accepted by the app after updating them (and uploading it to a NEW office-plugin-N release).
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
OUT=$(mkdir -p "${1:-build/office-plugin}" && cd "${1:-build/office-plugin}" && pwd)
: "${ANDROID_HOME:?set ANDROID_HOME}"
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java; JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
BT=$ANDROID_HOME/build-tools/34.0.0
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
for f in "$BT/lib/d8.jar" "$ANDROID_JAR"; do [ -f "$f" ] || { echo "missing $f"; exit 1; }; done

M=https://repo1.maven.org/maven2
JARS="org/apache/poi/poi/4.1.2/poi-4.1.2.jar ab1612406541968434044b2defad58aa8b657cad073baa22a04faaf9d7fb9d1c
org/apache/poi/poi-scratchpad/4.1.2/poi-scratchpad-4.1.2.jar 4ad6a0579a0a216ff951a80f11c648792268189591fe86015b9d197d650424f3
commons-codec/commons-codec/1.13/commons-codec-1.13.jar 61f7a3079e92b9fdd605238d0295af5fd11ac411a0a0af48deace1f6c5ffa072
org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar 1df8b9430b5c8ed143d7815e403e33ef5371b2400aadbe9bda0883762e0846d1
org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar 1e56d7b058d28b65abd256b8458e3885b674c1d588fa43cd7d1cbb9c7ef2b308
com/zaxxer/SparseBitSet/1.2/SparseBitSet-1.2.jar 91e6b318c901a0f2dd1f6ce781d62474435ae627d22fbac9b21bbc39ffd804b6"

CACHE=$OUT/jars; W=$OUT/work
rm -rf "$W"; mkdir -p "$CACHE" "$W/classes" "$W/applib"
CP=""
while read -r path sha; do
  f=$CACHE/$(basename "$path")
  if [ ! -f "$f" ]; then curl -fsSL "$M/$path" -o "$f.part"; mv "$f.part" "$f"; fi
  [ "$(sha256sum "$f" | cut -d' ' -f1)" = "$sha" ] || { echo "SHA-256 mismatch: $f"; rm -f "$f"; exit 1; }
  CP=$CP${CP:+:}$f
done <<< "$JARS"

# Adapter, compiled against the app's own viewer classes (taken from the app sources; NOT packed into the plugin:
# at run time they come from the app's class loader, the plugin loader's parent).
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -Xlint:-options -cp "$ANDROID_JAR:$CP" -sourcepath app/src/main/java \
  -d "$W/classes" tools/office-plugin/src/app/kolay/office/PoiProvider.java
mv "$W/classes/app/kolay/app" "$W/applib/app.kolay.app.tmp"; mkdir -p "$W/applib/app/kolay"; mv "$W/applib/app.kolay.app.tmp" "$W/applib/app/kolay/app"
(cd "$W/classes" && "${JAVA_HOME:+$JAVA_HOME/bin/}jar" cf "$W/adapter.jar" .)
(cd "$W/applib" && "${JAVA_HOME:+$JAVA_HOME/bin/}jar" cf "$W/applib.jar" .)

# Shrink (R8, class files out: also used by the desktop smoke test), then dex for API 29+.
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --classfile --no-minification \
  --lib "$ANDROID_JAR" --lib "$W/applib.jar" --pg-conf tools/office-plugin/rules.pro \
  --output "$W/plugin-shrunk.jar" "$W/adapter.jar" ${CP//:/ } 2> "$W/r8.log" || { cat "$W/r8.log"; exit 1; }
mkdir -p "$W/dex"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" --classpath "$W/applib.jar" \
  --output "$W/dex" "$W/plugin-shrunk.jar"
[ "$(ls "$W/dex" | wc -l)" = 1 ] || { echo "more than one dex file: $(ls "$W/dex")"; exit 1; }
cp "$W/dex/classes.dex" "$OUT/kolay-office-plugin.dex"
cp "$W/plugin-shrunk.jar" "$W/applib.jar" "$OUT/"
echo "$OUT/kolay-office-plugin.dex"
echo "size   $(stat -c %s "$OUT/kolay-office-plugin.dex")"
echo "sha256 $(sha256sum "$OUT/kolay-office-plugin.dex" | cut -d' ' -f1)"
