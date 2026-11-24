// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
#include <intrin.h>

extern "C" __declspec(dllexport) int score_sparse_cpu_supported() {
    int flags[4];
    __cpuid(flags, 0);
    if (flags[0] < 7) return 0;
    __cpuid(flags, 1);
    constexpr int fma_avx_osxsave = (1 << 12) | (1 << 27) | (1 << 28);
    if ((flags[2] & fma_avx_osxsave) != fma_avx_osxsave || (_xgetbv(0) & 6) != 6) return 0;
    __cpuidex(flags, 7, 0);
    return (flags[1] & (1 << 5)) != 0;
}
