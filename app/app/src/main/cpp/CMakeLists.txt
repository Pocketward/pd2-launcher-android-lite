cmake_minimum_required(VERSION 3.22.1)
project(pd2lite C)

# pd2lite.c: process spawning, the framebuffer memfd, the frame copy; mali_probe.c: the device
# check's Mali GPU id probe (Arm's kbase).
add_library(pd2lite SHARED pd2lite.c mali_probe.c)
target_compile_options(pd2lite PRIVATE -O2 -Wall -Wextra)
# LOAD segments aligned to 16 KB, so the library loads on a kernel with 16 KB pages too (the NDK
# r27 linker aligns to 4 KB by default).
target_link_options(pd2lite PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(pd2lite android log)
