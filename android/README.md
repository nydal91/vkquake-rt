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

Desktop test of the touch controls: configure with `-DQUAKE_TOUCH_TEST=ON`
and set `QUAKE_TOUCH_SCRIPT="frame,d|m|u,finger,x,y;..."`.
