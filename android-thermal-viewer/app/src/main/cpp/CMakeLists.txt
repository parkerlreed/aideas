cmake_minimum_required(VERSION 3.22.1)
project("usb_iso")

add_library(usb_iso SHARED usb_iso.c)

find_library(log-lib log)
target_link_libraries(usb_iso ${log-lib})
