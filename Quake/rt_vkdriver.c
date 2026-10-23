// Custom Vulkan drivers: lets the player run the ray tracer on a different
// GPU driver than the system one, e.g. Mesa Turnip on Adreno GPUs.
//
// Android: the launcher sets QUAKE_GPU_DRIVER_DIR (folder with the driver's
// .so) and QUAKE_GPU_DRIVER_LIB (its file name); libadrenotools loads it
// through the system Vulkan loader without root. Hooks are in
// QUAKE_NATIVE_LIB_DIR (the app's native library folder).
//
// Elsewhere (testing): QUAKE_VULKAN_DRIVER=/path/to/libvulkan_xxx.so loads a
// Vulkan ICD directly.

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rt_vkdriver.h"
#include "quakedef.h"

#ifdef HAVE_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

static char driver_desc[512] = "system";

const char *RT_CustomVulkanDriverDesc(void)
{
  return driver_desc;
}

void *RT_LoadCustomVulkanDriver(void)
{
  void *handle = NULL;
  void *gipa = NULL;

#ifdef HAVE_ADRENOTOOLS
  const char *dir = getenv("QUAKE_GPU_DRIVER_DIR");
  const char *lib = getenv("QUAKE_GPU_DRIVER_LIB");
  const char *hooks = getenv("QUAKE_NATIVE_LIB_DIR");
  const char *tmp = getenv("QUAKE_GPU_DRIVER_TMP");

  if (!dir || !*dir || !lib || !*lib)
  {
    return NULL;
  }
  if (!hooks || !*hooks)
  {
    Sys_Printf("RT_LoadCustomVulkanDriver: no native library folder, using the system driver\n");
    return NULL;
  }

  handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmp, hooks, dir, lib, NULL, NULL);
  snprintf(driver_desc, sizeof(driver_desc), "%s (%s)", lib, dir);
  if (!handle)
  {
    Sys_Printf("RT_LoadCustomVulkanDriver: adrenotools couldn't load %s from %s: %s\n", lib, dir, dlerror());
    snprintf(driver_desc, sizeof(driver_desc), "system (custom %s failed to load)", lib);
    return NULL;
  }
#else
  const char *path = getenv("QUAKE_VULKAN_DRIVER");

  if (!path || !*path)
  {
    return NULL;
  }
  handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  snprintf(driver_desc, sizeof(driver_desc), "%s", path);
  if (!handle)
  {
    Sys_Printf("RT_LoadCustomVulkanDriver: can't load %s: %s\n", path, dlerror());
    snprintf(driver_desc, sizeof(driver_desc), "system (custom %s failed to load)", path);
    return NULL;
  }
#endif

  // a loader exports vkGetInstanceProcAddr, a bare ICD vk_icdGetInstanceProcAddr
  gipa = dlsym(handle, "vkGetInstanceProcAddr");
  if (!gipa)
  {
    gipa = dlsym(handle, "vk_icdGetInstanceProcAddr");
  }
  if (!gipa)
  {
    Sys_Printf("RT_LoadCustomVulkanDriver: no vkGetInstanceProcAddr in the driver\n");
    snprintf(driver_desc, sizeof(driver_desc), "system (custom driver unusable)");
    return NULL;
  }
  Sys_Printf("RT_LoadCustomVulkanDriver: using %s\n", driver_desc);
  return gipa;
}

// After the Vulkan instance exists: which driver files are mapped into the
// process, and whether the custom driver is one of them. adrenotools falls
// back to the system driver silently, so this is how to tell.
void RT_LogVulkanDriverFiles(void)
{
  FILE *f = fopen("/proc/self/maps", "r");
  char line[1024];
  char seen[16][512];
  int count = 0, custom_found = 0;
  const char *custom = getenv("QUAKE_GPU_DRIVER_LIB");

#ifndef HAVE_ADRENOTOOLS
  custom = getenv("QUAKE_VULKAN_DRIVER");
  if (custom && strrchr(custom, '/'))
  {
    custom = strrchr(custom, '/') + 1;
  }
#endif

  if (!f)
  {
    return;
  }
  while (fgets(line, sizeof(line), f))
  {
    char *path = strchr(line, '/');
    char *nl;
    const char *base;
    int i, dup = 0;

    if (!path)
    {
      continue;
    }
    if ((nl = strchr(path, '\n')))
    {
      *nl = 0;
    }
    base = strrchr(path, '/') + 1;
    // driver libraries: vulkan.<name>.so, libvulkan_<name>.so, the loader libvulkan.so
    if (!(strncmp(base, "vulkan.", 7) == 0 || strncmp(base, "libvulkan", 9) == 0 ||
          (custom && strcmp(base, custom) == 0)))
    {
      continue;
    }
    for (i = 0; i < count; i++)
    {
      dup |= strcmp(seen[i], path) == 0;
    }
    if (dup || count >= 16)
    {
      continue;
    }
    snprintf(seen[count++], sizeof(seen[0]), "%s", path);
    Sys_Printf("Vulkan driver file loaded: %s\n", path);
    if (custom && *custom && strcmp(base, custom) == 0 && strstr(path, "gpu_drivers") != NULL)
    {
      custom_found = 1;
    }
#ifndef HAVE_ADRENOTOOLS
    if (custom && *custom && strcmp(base, custom) == 0)
    {
      custom_found = 1;
    }
#endif
  }
  fclose(f);

  if (custom && *custom)
  {
    Sys_Printf("Custom GPU driver %s: %s\n", custom,
            custom_found ? "IN USE" : "NOT loaded, the system driver is in use (the driver loader fell back)");
  }
}
