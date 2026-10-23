#!/bin/bash
# Builds Quake: Ray Traced for Android (arm64-v8a) into a signed APK, without Gradle.
#
# Required environment:
#   ANDROID_HOME    SDK with platforms;android-34, build-tools;35.0.0, ndk;27.2.12479018
#   RTGL1_SDK_PATH  RayTracedGL1 checkout: the "quake" branch ported to ray queries
#                   (ray-query-android branch, see android/README.md)
#   SDL2_SRC        SDL 2.30.x source checkout
#   LIBREQUAKE      LibreQuake's id1 folder (free game data, bundled as the default game)
#   RT_DATA         the ovrd folder of the official Quake: Ray Traced release
#                   (materials, lights, portals); its BC textures are converted
#                   for phones (BC -> RGBA8, needs `pip install texture2ddecoder`)
# Optional:
#   OGG_SRC, VORBIS_SRC  libogg / libvorbis sources: Ogg Vorbis music (id1/music/trackNN.ogg)
#   ADRENOTOOLS_SRC libadrenotools checkout (with submodules): custom GPU drivers such as Mesa Turnip
#   OUT             output folder (default: ./out)
#   KEYSTORE        signing keystore (default: a generated debug key in $OUT)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${OUT:-$PWD/out}"
NDK="$ANDROID_HOME/ndk/27.2.12479018"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
API=31
ABI=arm64-v8a
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.1-android1}"
TOOLCHAIN=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
           -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release)
JOBS="$(nproc)"

mkdir -p "$OUT"
B="$OUT/build"
mkdir -p "$B"

echo "== RayTracedGL1"
cmake -S "$RTGL1_SDK_PATH" -B "$B/rtgl1" "${TOOLCHAIN[@]}" -DRG_WITH_SURFACE_ANDROID=ON > "$B/rtgl1.log"
cmake --build "$B/rtgl1" -j"$JOBS" >> "$B/rtgl1.log"

echo "== RayTracedGL1 shaders"
rm -rf "$B/shaders"; mkdir -p "$B/shaders"
( cd "$RTGL1_SDK_PATH/Source/Shaders"
  INC=(-I. -I../Generated); for d in */; do [ -d "$d" ] && INC+=(-I"${d%/}"); done
  for f in *.comp *.vert *.frag; do
    glslc --target-env=vulkan1.2 "${INC[@]}" "$f" -o "$B/shaders/$f.spv"
  done )

echo "== SDL2"
cmake -S "$SDL2_SRC" -B "$B/sdl2" "${TOOLCHAIN[@]}" -DSDL_STATIC=OFF -DSDL_SHARED=ON -DSDL_TEST=OFF > "$B/sdl2.log"
cmake --build "$B/sdl2" -j"$JOBS" >> "$B/sdl2.log"

EXTRA_ARGS=()
if [ -n "${ADRENOTOOLS_SRC:-}" ]; then
    echo "== libadrenotools"
    cmake -S "$ADRENOTOOLS_SRC" -B "$B/adrenotools" "${TOOLCHAIN[@]}" > "$B/adrenotools.log"
    cmake --build "$B/adrenotools" -j"$JOBS" >> "$B/adrenotools.log"
    EXTRA_ARGS+=(-DADRENOTOOLS_SOURCE_DIR="$ADRENOTOOLS_SRC" -DADRENOTOOLS_BUILD_DIR="$B/adrenotools")
fi
if [ -n "${OGG_SRC:-}" ] && [ -n "${VORBIS_SRC:-}" ]; then
    EXTRA_ARGS+=(-DOGG_SOURCE_DIR="$OGG_SRC" -DVORBIS_SOURCE_DIR="$VORBIS_SRC")
fi

echo "== Quake RT"
cmake -S "$ROOT" -B "$B/game" "${TOOLCHAIN[@]}" \
    -DRTGL1_SDK_PATH="$RTGL1_SDK_PATH" -DRTGL1_LIBRARY="$B/rtgl1/libRayTracedGL1.so" \
    -DSDL2_ANDROID_SOURCE_DIR="$SDL2_SRC" -DSDL2_ANDROID_LIBRARY="$B/sdl2/libSDL2.so" \
    "${EXTRA_ARGS[@]}" > "$B/game.log"
cmake --build "$B/game" -j"$JOBS" >> "$B/game.log"

echo "== Java"
J="$B/java"; rm -rf "$J"; mkdir -p "$J/classes" "$J/dex"
javac -nowarn -Xlint:none -Xlint:-options --release 8 -XDstringConcat=inline -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$J/classes" \
    "$HERE"/app/java/org/libsdl/app/*.java \
    "$HERE"/app/java/org/quakert/android/*.java
"$BT/d8" --release --min-api $API --lib "$ANDROID_JAR" --output "$J/dex" $(find "$J/classes" -name '*.class')

echo "== Assets"
S="$B/stage"; rm -rf "$S"; mkdir -p "$S/assets/ovrd/shaders" "$S/assets/libre" "$S/assets/licenses" "$S/lib/$ABI"
if [ ! -d "$B/rtdata" ] || [ "$RT_DATA" -nt "$B/rtdata" ]; then
    rm -rf "$B/rtdata"
    python3 -I "$HERE/tools/convert_bc_ktx2.py" "$RT_DATA" "$B/rtdata"
fi
( cd "$B/rtdata" && cp -r mat *.txt *.ktx2 "$S/assets/ovrd/" )
cp "$B/shaders/"*.spv "$S/assets/ovrd/shaders/"
cp -r "$LIBREQUAKE" "$S/assets/libre/id1"
rm -rf "$S/assets/libre/id1/config.cfg"
cp "$HERE/licenses/"* "$S/assets/licenses/" 2>/dev/null || true
cp "$SDL2_SRC/LICENSE.txt" "$S/assets/licenses/SDL2-LICENSE.txt"
cp "$ROOT/LICENSE.txt" "$S/assets/licenses/QUAKE-GPL.txt" 2>/dev/null || cp "$ROOT/LICENSE" "$S/assets/licenses/QUAKE-GPL.txt" 2>/dev/null || true
cp "$RTGL1_SDK_PATH/LICENSE" "$S/assets/licenses/RTGL1-LICENSE.txt"

echo "== Native libraries"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
LIBS=("$B/rtgl1/libRayTracedGL1.so" "$B/sdl2/libSDL2.so" "$B/game/libmain.so")
if [ -n "${ADRENOTOOLS_SRC:-}" ]; then
    # adrenotools' hooks must sit in the app's native library folder
    LIBS+=("$B/adrenotools/src/hook/"lib*_hook.so "$B/adrenotools/src/hook/libhook_impl.so")
fi
for so in "${LIBS[@]}"; do
    "$STRIP" --strip-unneeded -o "$S/lib/$ABI/$(basename "$so")" "$so"
done

echo "== APK"
"$BT/aapt2" compile --dir "$HERE/app/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unaligned.apk" -I "$ANDROID_JAR" \
    --manifest "$HERE/app/AndroidManifest.xml" \
    --min-sdk-version $API --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -0 pak -0 ogg -0 ktx2 -A "$S/assets" "$B/res.zip"
( cd "$J/dex" && zip -q "$B/unaligned.apk" classes.dex )
( cd "$S" && zip -q -r "$B/unaligned.apk" lib )

# 16 KB page alignment for native libs (Android 15+ devices)
"$BT/zipalign" -f -P 16 4 "$B/unaligned.apk" "$B/aligned.apk"

KEYSTORE="${KEYSTORE:-$OUT/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Quake RT Android Debug" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/quake-rt-android-arm64.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT/quake-rt-android-arm64.apk"
echo "== Done: $OUT/quake-rt-android-arm64.apk"
