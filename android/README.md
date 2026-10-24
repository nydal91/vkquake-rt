# Quake: Ray Traced for Android

An arm64 Android build of vkQuake-RT, for phones whose Vulkan driver has
`VK_KHR_ray_query` (e.g. Snapdragon 8 Gen 2 and newer / Adreno 740+).

## What's different from the PC version

* **Renderer**: RayTracedGL1's `quake` branch with the ray tracing pipeline
  replaced by ray queries in compute shaders (phones have `VK_KHR_ray_query`
  but usually not `VK_KHR_ray_tracing_pipeline`), Vulkan loaded through volk,
  an Android surface, and no FSR2 (its libraries are Windows-only; the
  upscaler setting falls back to linear upscaling). On a desktop GPU the
  ray-query renderer gives the same image as the original, pixel for pixel.
* **Data**: the release's `ovrd/` materials are converted from BC
  (desktop-only) to RGBA8 textures at build time.
* **Game data**: LibreQuake (free, BSD) is bundled. Add Quake's own
  `pak0.pak` / `pak1.pak` (or a zip of the `id1` folder) with **Add file…** in
  the launcher, or open them with the app from a file manager.
  Mission packs, mods (a zip with a game folder, or with `pak0.pak` /
  `progs.dat` at the top) and map packs (`.bsp` files) can be added the same
  way; map packs without a start map ask which map to play.
* **Touch controls**: left side moves (the point you touch is the stick
  center; push further to run), drag in the middle to look, JUMP and FIRE on
  the right, MENU / weapons / flashlight / scores along the top. In menus,
  drag to move the cursor and tap to select. The overlay hides while a game
  controller is in use.
* **Game controllers**: SDL game controllers work as on PC.
* **GPU driver**: a custom Vulkan driver (e.g. Mesa Turnip, in the usual
  adrenotools zip format) can be imported and chosen in the launcher.
* **Music**: Ogg Vorbis tracks in `id1/music/` play (LibreQuake ships its own).
* **Errors**: if the game fails, the launcher shows the error and the end
  of the log on the next start, with a Copy button. Long-press the app icon
  for "Show log".

## Building

The renderer is RayTracedGL1's `quake` branch (commit 9efa82d) with the
patches in `android/rtgl1-patches/` (ray queries, Android, volk, Adreno fixes):

```sh
git clone -b quake https://github.com/sultim-t/RayTracedGL1 RayTracedGL1-quake-rq
cd RayTracedGL1-quake-rq
git checkout 9efa82daf963e1192daaf2a8596446656f113cd2
git am /path/to/vkquake-rt/android/rtgl1-patches/*.patch
```

`RT_DATA` is the `ovrd` folder of the official Quake: Ray Traced release zip
(https://github.com/sultim-t/vkquake-rt/releases), without its `shaders`
folder: the shaders are compiled from the patched RayTracedGL1.
LibreQuake: https://github.com/lavenderdotpet/LibreQuake/releases (the `lite` id1).

See the variables at the top of `build_apk.sh`. Example:

```sh
ANDROID_HOME=/opt/android-sdk \
RTGL1_SDK_PATH=~/RayTracedGL1-quake-rq \
SDL2_SRC=~/SDL2-2.30.x \
LIBREQUAKE=~/librequake/id1 \
RT_DATA=~/quake-rt-1.0.1/ovrd \
OGG_SRC=~/ogg VORBIS_SRC=~/vorbis \
ADRENOTOOLS_SRC=~/libadrenotools \
android/build_apk.sh
```

## Linux

The same sources build on Linux (x86-64) with CMake; the renderer is the
patched RayTracedGL1 built with `-DRG_WITH_SURFACE_XLIB=ON`:

```sh
cmake -S RayTracedGL1-quake-rq -B build-rtgl1 -DCMAKE_BUILD_TYPE=Release -DRG_WITH_SURFACE_XLIB=ON
cmake --build build-rtgl1 -j
cmake -S vkquake-rt -B build-game -DCMAKE_BUILD_TYPE=Release \
    -DRTGL1_SDK_PATH=$PWD/RayTracedGL1-quake-rq -DRTGL1_LIBRARY=$PWD/build-rtgl1/libRayTracedGL1.so \
    -DOGG_SOURCE_DIR=$PWD/ogg -DVORBIS_SOURCE_DIR=$PWD/vorbis
cmake --build build-game -j
```

Run it from a folder that has `ovrd/` (the release's materials plus the
compiled shaders) and `id1/`: `./vkquake-rt -basedir .`
Needs `VK_KHR_ray_query` (AMD RX 6000+ with Mesa, NVIDIA RTX, Intel Arc).

Desktop test of the touch controls: configure with `-DQUAKE_TOUCH_TEST=ON`
and set `QUAKE_TOUCH_SCRIPT="frame,d|m|u,finger,x,y;..."`.
