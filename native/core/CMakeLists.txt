cmake_minimum_required(VERSION 4.1.2)
project(camera_native LANGUAGES CXX)

add_library(camera_native SHARED
    src/camera_native.cpp
)

target_compile_features(camera_native PRIVATE cxx_std_20)
target_compile_options(camera_native PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
