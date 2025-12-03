
cmake_minimum_required(VERSION 3.10.2)
project("media_utils")

add_library(media_utils SHARED
        native-lib.cpp
)

find_library(log-lib log)

target_include_directories(media_utils PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(media_utils
        ${log-lib}
)

