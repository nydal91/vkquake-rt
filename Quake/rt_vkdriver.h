#ifndef RT_VKDRIVER_H
#define RT_VKDRIVER_H

// Returns a PFN_vkGetInstanceProcAddr of a custom Vulkan driver chosen by the
// player, or NULL to use the system driver. See rt_vkdriver.c.
void *RT_LoadCustomVulkanDriver(void);
// "system", or the custom driver in use, for logs and error messages
const char *RT_CustomVulkanDriverDesc(void);

// logs the Vulkan driver files loaded, and if the custom one is in use
void RT_LogVulkanDriverFiles(void);

#endif
