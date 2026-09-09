cmake_minimum_required(VERSION 3.22.1)
project(cubacadabra_jni C)

add_library(cubacadabra_client SHARED IMPORTED)
set_target_properties(cubacadabra_client PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../../../build/generated/rust/jniLibs/${ANDROID_ABI}/libcubacadabra_client.so"
    IMPORTED_NO_SONAME TRUE
)

add_library(cubacadabra_jni SHARED jni_bridge.c)
find_library(android-lib android)
find_library(log-lib log)
target_link_libraries(cubacadabra_jni cubacadabra_client ${android-lib} ${log-lib})
