# Linux / Android build of Quake: Ray Traced (the Windows build uses Windows/VisualStudio)
cmake_minimum_required(VERSION 3.16)
project(vkquake_rt C)

set(RTGL1_SDK_PATH "" CACHE PATH "RayTracedGL1 checkout (Include/ folder)")
set(RTGL1_LIBRARY "" CACHE FILEPATH "libRayTracedGL1.so")
set(OGG_SOURCE_DIR "" CACHE PATH "libogg source (optional, with VORBIS_SOURCE_DIR: Ogg Vorbis music)")
set(VORBIS_SOURCE_DIR "" CACHE PATH "libvorbis source (optional)")
set(ADRENOTOOLS_SOURCE_DIR "" CACHE PATH "Android: libadrenotools source (optional, custom GPU drivers)")
set(ADRENOTOOLS_BUILD_DIR "" CACHE PATH "Android: libadrenotools build folder")
option(QUAKE_TOUCH_TEST "Desktop: Android touch controls, driven by QUAKE_TOUCH_SCRIPT" OFF)

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
    Quake/snd_vorbis.c
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
    Quake/net_bsd.c Quake/net_udp.c Quake/pl_linux.c Quake/sys_sdl_unix.c Quake/cd_null.c
    Quake/rt_vkdriver.c Quake/android_sys.c Quake/android_touch.c)

if(ANDROID)
    add_library(main SHARED ${QUAKE_SOURCES})
    set(TARGET main)
else()
    add_executable(vkquake-rt ${QUAKE_SOURCES})
    set(TARGET vkquake-rt)
endif()

if(ANDROID)
    set(SDL2_ANDROID_SOURCE_DIR "" CACHE PATH "SDL2 source")
    set(SDL2_ANDROID_LIBRARY "" CACHE FILEPATH "libSDL2.so built with the NDK")
    set(SDL2_INCLUDE_DIRS ${SDL2_ANDROID_SOURCE_DIR}/include)
    set(SDL2_LIBRARIES ${SDL2_ANDROID_LIBRARY})
else()
    find_package(SDL2 REQUIRED)
endif()
target_include_directories(${TARGET} PRIVATE "${RTGL1_SDK_PATH}/Include" ${SDL2_INCLUDE_DIRS} Quake)
target_compile_definitions(${TARGET} PRIVATE USE_CODEC_WAVE _GNU_SOURCE)
if(ANDROID)
    target_compile_definitions(${TARGET} PRIVATE RG_USE_SURFACE_ANDROID)
else()
    target_compile_definitions(${TARGET} PRIVATE RG_USE_SURFACE_XLIB)
endif()
if(OGG_SOURCE_DIR AND VORBIS_SOURCE_DIR)
    set(VORBIS_LIB analysis bitrate block codebook envelope floor0 floor1 info lookup lpc lsp
        mapping0 mdct psy registry res0 sharedbook smallft synthesis vorbisenc window vorbisfile)
    set(VORBIS_SOURCES ${OGG_SOURCE_DIR}/src/bitwise.c ${OGG_SOURCE_DIR}/src/framing.c)
    foreach(f ${VORBIS_LIB})
        list(APPEND VORBIS_SOURCES ${VORBIS_SOURCE_DIR}/lib/${f}.c)
    endforeach()
    file(WRITE ${CMAKE_BINARY_DIR}/ogg_config/ogg/config_types.h
"#ifndef __CONFIG_TYPES_H__\n#define __CONFIG_TYPES_H__\n#include <stdint.h>\n
typedef int16_t ogg_int16_t;\ntypedef uint16_t ogg_uint16_t;\ntypedef int32_t ogg_int32_t;\n
typedef uint32_t ogg_uint32_t;\ntypedef int64_t ogg_int64_t;\ntypedef uint64_t ogg_uint64_t;\n#endif\n")
    add_library(quake_vorbis STATIC ${VORBIS_SOURCES})
    target_include_directories(quake_vorbis PUBLIC ${CMAKE_BINARY_DIR}/ogg_config ${OGG_SOURCE_DIR}/include ${VORBIS_SOURCE_DIR}/include
                               PRIVATE ${VORBIS_SOURCE_DIR}/lib)
    target_compile_options(quake_vorbis PRIVATE -w -O2)
    set_target_properties(quake_vorbis PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_definitions(${TARGET} PRIVATE USE_CODEC_VORBIS)
    target_link_libraries(${TARGET} PRIVATE quake_vorbis)
endif()

if(ANDROID)
    target_link_libraries(${TARGET} PRIVATE log android)
    if(ADRENOTOOLS_SOURCE_DIR)
        target_compile_definitions(${TARGET} PRIVATE HAVE_ADRENOTOOLS)
        target_include_directories(${TARGET} PRIVATE ${ADRENOTOOLS_SOURCE_DIR}/include)
        target_link_libraries(${TARGET} PRIVATE ${ADRENOTOOLS_BUILD_DIR}/libadrenotools.a
            ${ADRENOTOOLS_BUILD_DIR}/lib/linkernsbypass/liblinkernsbypass.a)
    endif()
endif()
if(QUAKE_TOUCH_TEST)
    target_compile_definitions(${TARGET} PRIVATE QUAKE_TOUCH_TEST)
endif()

target_compile_options(${TARGET} PRIVATE -std=gnu11 -Wno-trigraphs -Wno-unused-function)
target_link_libraries(${TARGET} PRIVATE "${RTGL1_LIBRARY}" ${SDL2_LIBRARIES} m dl)
if(NOT ANDROID)
    target_link_libraries(${TARGET} PRIVATE pthread)
endif()
