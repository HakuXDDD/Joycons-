cmake_minimum_required(VERSION 3.22.1)

project("joymerge" C)

add_library(joymerge SHARED
        joymerge.c)

target_compile_options(joymerge PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden)

find_library(log-lib log)

target_link_libraries(joymerge ${log-lib})
