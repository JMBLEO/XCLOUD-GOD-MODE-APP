#include <cstdint>
#include <string_view>

#ifndef GODMODE_GIT_COMMIT_SHA
#define GODMODE_GIT_COMMIT_SHA "0000000000000000000000000000000000000000"
#endif

#ifndef GODMODE_COMPILATION_TIMESTAMP
#define GODMODE_COMPILATION_TIMESTAMP "1970-01-01T00:00:00Z"
#endif

namespace com::xcloud::godmode::reproducibility {

struct CompilationIdentityBlock {
    const char magic[20]{"GODMODE_BUILD_ID"};
    const char gitCommitSha[41]{GODMODE_GIT_COMMIT_SHA};
    const char buildTimestamp[25]{GODMODE_COMPILATION_TIMESTAMP};
    const uint32_t abiTarget{
#if defined(__arm__)
        1 // ARMv7
#elif defined(__aarch64__)
        2 // ARM64-v8a / v9a
#elif defined(__i386__)
        3 // x86
#elif defined(__x86_64__)
        4 // x86_64
#else
        0
#endif
    };
};

// Ubicado en la sección .rodata del binario ELF para garantizar inmutabilidad
__attribute__((used, section(".rodata.godmode_ident")))
static const CompilationIdentityBlock g_buildIdentifier{};

std::string_view getCompiledGitCommit() noexcept {
    return std::string_view(g_buildIdentifier.gitCommitSha, 40);
}

} // namespace com::xcloud::godmode::reproducibility
