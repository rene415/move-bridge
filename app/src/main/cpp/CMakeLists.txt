cmake_minimum_required(VERSION 3.22.1)
project(movebridge C CXX)

set(LU ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

# libusb 1.0.27 built from source (same file list as libusb/android/jni/libusb.mk)
add_library(usb1 STATIC
    ${LU}/libusb/core.c
    ${LU}/libusb/descriptor.c
    ${LU}/libusb/hotplug.c
    ${LU}/libusb/io.c
    ${LU}/libusb/sync.c
    ${LU}/libusb/strerror.c
    ${LU}/libusb/os/linux_usbfs.c
    ${LU}/libusb/os/events_posix.c
    ${LU}/libusb/os/threads_posix.c
    ${LU}/libusb/os/linux_netlink.c)
target_include_directories(usb1
    PUBLIC  ${LU}/libusb
    PRIVATE ${LU}/android ${LU}/libusb/os)
target_compile_options(usb1 PRIVATE -pthread -fvisibility=hidden -w)

add_library(movebridge SHARED move_bridge.cpp)
target_link_libraries(movebridge usb1 log)

# Our own code: all warnings on and treated as errors (NASA rule 10). libusb above keeps -w.
target_compile_options(movebridge PRIVATE -Wall -Wextra -Wshadow -Wconversion -Wno-sign-conversion -Werror)
