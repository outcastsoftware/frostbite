cmake_minimum_required(VERSION 3.22)
project(frostbite LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(FROSTBITE_UNITY_VER "222" CACHE STRING "BNM Unity header selector; must match the target game")

set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "Link Dobby into libfrostbite" FORCE)
FetchContent_Declare(dobby_fixed
    GIT_REPOSITORY https://github.com/BepInEx/Dobby.git
    GIT_TAG 9d271803881f467aabfc24593c7dcaeb710fe861)
FetchContent_MakeAvailable(dobby_fixed)

FetchContent_Declare(bnm
    GIT_REPOSITORY https://github.com/ByNameModding/BNM-Android.git
    GIT_TAG 502928771983d29e37f28c78f28823dfb775a3aa)
FetchContent_GetProperties(bnm)
if(NOT bnm_POPULATED)
    FetchContent_Populate(bnm)
    set(BNM_SETTINGS "${bnm_SOURCE_DIR}/include/BNM/UserSettings/GlobalSettings.hpp")
    execute_process(
        COMMAND git show 502928771983d29e37f28c78f28823dfb775a3aa:include/BNM/UserSettings/GlobalSettings.hpp
        WORKING_DIRECTORY "${bnm_SOURCE_DIR}"
        OUTPUT_VARIABLE BNM_TEXT
        RESULT_VARIABLE BNM_GIT_RESULT)
    if(NOT BNM_GIT_RESULT EQUAL 0)
        message(FATAL_ERROR "Could not read pinned BNM settings")
    endif()
    string(FIND "${BNM_TEXT}" "// Dobby\n/*" DOBBY_START)
    string(FIND "${BNM_TEXT}" "*/\n\n// Dummy" DOBBY_END)
    string(FIND "${BNM_TEXT}" "#include <dlfcn.h>" BNM_TAIL_START)
    if(DOBBY_START LESS 0 OR DOBBY_END LESS 0 OR BNM_TAIL_START LESS 0)
        message(FATAL_ERROR "Pinned BNM settings layout changed; update integration patch")
    endif()
    string(SUBSTRING "${BNM_TEXT}" 0 ${DOBBY_START} BNM_PREFIX)
    math(EXPR DOBBY_BODY_START "${DOBBY_START} + 11")
    math(EXPR DOBBY_BODY_LENGTH "${DOBBY_END} - ${DOBBY_BODY_START}")
    string(SUBSTRING "${BNM_TEXT}" ${DOBBY_BODY_START} ${DOBBY_BODY_LENGTH} BNM_DOBBY_BODY)
    string(SUBSTRING "${BNM_TEXT}" ${BNM_TAIL_START} -1 BNM_TAIL)
    string(REPLACE "#define UNITY_VER 222" "#define UNITY_VER ${FROSTBITE_UNITY_VER}" BNM_PREFIX "${BNM_PREFIX}")
    string(REPLACE "#define BNM_CLASSES_MANAGEMENT" "// #define BNM_CLASSES_MANAGEMENT" BNM_PREFIX "${BNM_PREFIX}")
    string(REPLACE "#define BNM_COROUTINE" "// #define BNM_COROUTINE" BNM_PREFIX "${BNM_PREFIX}")
    file(WRITE "${BNM_SETTINGS}" "${BNM_PREFIX}// Dobby\n${BNM_DOBBY_BODY}\n${BNM_TAIL}")
endif()
add_subdirectory("${bnm_SOURCE_DIR}" "${bnm_BINARY_DIR}")
target_include_directories(BNM PRIVATE "${dobby_fixed_SOURCE_DIR}/include")

add_library(frostbite SHARED frostbite.cpp)
target_include_directories(frostbite PRIVATE "${dobby_fixed_SOURCE_DIR}/include")
target_link_options(frostbite PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_libraries(frostbite PRIVATE BNM dobby log dl)
