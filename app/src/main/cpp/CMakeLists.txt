cmake_minimum_required(VERSION 3.22.1)
project("czvpn_native")

# ==============================================
# 我们构建两个目标：
#   1. czvpn_jni  : JNI 桥接库 (Kotlin -> C++ 的 native 函数)
#   2. 若存在 libzt 预编译产物，则链接到 czvpn_jni 启用真实 ZeroTier 能力
#     否则 czvpn_jni 内部为 stub 占位实现 (同样可以编译通过)
# ==============================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 先尝试找 ZeroTier libzt 的预编译 .so 和头文件
# 约定：用户把 libzt 放到 app/src/main/jniLibs/<abi>/libzt.so
#       头文件放到 app/src/main/jniLibs/include/ZeroTierSockets.h / ZeroTierConstants.h
set(JNILIBS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs")
set(JNILIBS_INCLUDE "${JNILIBS_ROOT}/include")

set(HAS_LIBZT FALSE)
if(EXISTS "${JNILIBS_INCLUDE}/ZeroTierSockets.h")
    if(EXISTS "${JNILIBS_ROOT}/${ANDROID_ABI}/libzt.so")
        set(HAS_LIBZT TRUE)
        message(STATUS "[CZVPN] Found libzt.so for ABI=${ANDROID_ABI}")
    endif()
endif()

if(NOT HAS_LIBZT)
    message(STATUS "[CZVPN] libzt not found, compiling in STUB mode.")
    message(STATUS "        Put libzt.so + headers into jniLibs/<abi>/ and jniLibs/include/")
endif()

add_library(
    czvpn_jni
    SHARED
    native-lib.cpp
)

target_include_directories(czvpn_jni PRIVATE ${JNILIBS_INCLUDE})

find_library(log-lib log)

target_link_libraries(
    czvpn_jni
    ${log-lib}
)

if(HAS_LIBZT)
    # 引入 libzt.so 作为导入库
    add_library(zt SHARED IMPORTED)
    set_target_properties(zt PROPERTIES
        IMPORTED_LOCATION "${JNILIBS_ROOT}/${ANDROID_ABI}/libzt.so"
        IMPORTED_NO_SONAME TRUE
    )
    target_compile_definitions(czvpn_jni PRIVATE CZVPN_HAS_LIBZT=1)
    target_link_libraries(czvpn_jni zt)
else()
    target_compile_definitions(czvpn_jni PRIVATE CZVPN_HAS_LIBZT=0)
endif()

# 把外部依赖的 libzt.so 也打包进 APK
if(HAS_LIBZT)
    add_custom_command(TARGET czvpn_jni POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${JNILIBS_ROOT}/${ANDROID_ABI}/libzt.so"
            "$<TARGET_FILE_DIR:czvpn_jni>/libzt.so"
    )
endif()
