# Linux / Android build of Quake: Ray Traced (the Windows build uses Windows/VisualStudio)
cmake_minimum_required(VERSION 3.16)
project(vkquake_rt C)

set(RTGL1_SDK_PATH "" CACHE PATH "RayTracedGL1 checkout (Include/ folder)")
set(RTGL1_LIBRARY "" CACHE FILEPATH "libRayTracedGL1.so")

set(QUAKE_SOURCES 
    Quake/bgmusic.c
    Quake/cfgfile.c
    Quake/chase.c
    Quake/cl_demo.c
    Quake/cl_input.c
    Quake/cl_main.c
    Quake/cl_parse.c
    Quake/cl_tent.c
    Quake/cmd.c
    Quake/common.c
    Quake/console.c
    Quake/crc.c
    Quake/cvar.c
    Quake/gl_draw.c
    Quake/gl_fog.c
    Quake/gl_heap.c
    Quake/gl_mesh.c
    Quake/gl_model.c
    Quake/gl_refrag.c
    Quake/gl_rlight.c
    Quake/gl_rmain.c
    Quake/gl_rmisc.c
    Quake/gl_screen.c
    Quake/gl_sky.c
    Quake/gl_texmgr.c
    Quake/gl_vidsdl.c
    Quake/gl_warp.c
    Quake/host.c
    Quake/host_cmd.c
    Quake/image.c
    Quake/in_sdl.c
    Quake/keys.c
    Quake/main_sdl.c
    Quake/mathlib.c
    Quake/mdfour.c
    Quake/mem.c
    Quake/menu.c
    Quake/miniz.c
    Quake/net_dgrm.c
    Quake/net_loop.c
    Quake/net_main.c
    Quake/palette.c
    Quake/pr_cmds.c
    Quake/pr_edict.c
    Quake/pr_exec.c
    Quake/pr_ext.c
    Quake/r_alias.c
    Quake/r_brush.c
    Quake/r_part.c
    Quake/r_part_fte.c
    Quake/r_sprite.c
    Quake/r_world.c
    Quake/sbar.c
    Quake/snd_codec.c
    Quake/snd_dma.c
    Quake/snd_mem.c
    Quake/snd_mix.c
    Quake/snd_sdl.c
    Quake/snd_umx.c
    Quake/snd_wave.c
    Quake/strlcat.c
    Quake/strlcpy.c
    Quake/sv_main.c
    Quake/sv_move.c
    Quake/sv_phys.c
    Quake/sv_user.c
    Quake/tasks.c
    Quake/view.c
    Quake/wad.c
    Quake/world.c
    Quake/net_bsd.c Quake/net_udp.c Quake/pl_linux.c Quake/sys_sdl_unix.c Quake/cd_null.c)

if(ANDROID)
    add_library(main SHARED ${QUAKE_SOURCES})
    set(TARGET main)
else()
    add_executable(vkquake-rt ${QUAKE_SOURCES})
    set(TARGET vkquake-rt)
endif()

find_package(SDL2 REQUIRED)
target_include_directories(${TARGET} PRIVATE "${RTGL1_SDK_PATH}/Include" ${SDL2_INCLUDE_DIRS} Quake)
target_compile_definitions(${TARGET} PRIVATE USE_CODEC_WAVE _GNU_SOURCE)
if(ANDROID)
    target_compile_definitions(${TARGET} PRIVATE RG_USE_SURFACE_ANDROID)
else()
    target_compile_definitions(${TARGET} PRIVATE RG_USE_SURFACE_XLIB)
endif()
target_compile_options(${TARGET} PRIVATE -std=gnu11 -Wno-trigraphs -Wno-unused-function)
target_link_libraries(${TARGET} PRIVATE "${RTGL1_LIBRARY}" ${SDL2_LIBRARIES} m pthread dl)
