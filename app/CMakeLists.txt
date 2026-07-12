cmake_minimum_required(VERSION 3.18)
project("llamawisp")

# llama.cpp 源码路径（需要在项目根目录放置 llama.cpp 源码）
# 可以从 https://github.com/ggml-org/llama.cpp 下载
set(LLAMACPP_DIR "${CMAKE_SOURCE_DIR}/llama.cpp")

# 如果本地有 llama.cpp 源码，取消注释以下两行
# add_subdirectory(${LLAMACPP_DIR} llama.cpp)
# target_link_libraries(llamawisp PRIVATE llama)

# 在没有 llama.cpp 源码时，仅编译桩代码用于验证项目结构
add_library(llamawisp SHARED
    src/main/cpp/llama_wisp.cpp
)

# 需要启用 C++17
target_compile_features(llamawisp PUBLIC cxx_std_17)

# Android 平台配置
target_compile_definitions(llamawisp PRIVATE
    $<$<CONFIG:DEBUG>:LLAMA_DEBUG>
)

# 输出路径
set_target_properties(llamawisp PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
