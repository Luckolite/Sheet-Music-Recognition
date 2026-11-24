// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
#include <immintrin.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

struct Step {
    int weight;
    float a, b, c, d;
};

// Height and width describe the output. VALID inputs include the full halo.
template <bool Valid>
static void conv_rows(
    const float* input, const float* packed, const float* bias, int height, int width,
    int cin, int cout, int dilation, int row_begin, int row_end, float* output) {
    const int kernel = 9 * cin;
    const int input_width = width + (Valid ? 2 * dilation : 0);
    const int input_height = height + (Valid ? 2 * dilation : 0);
    std::vector<Step> steps;
    steps.reserve(kernel);
    const __m256 zero = _mm256_setzero_ps();
    for (int y = row_begin; y < row_end; ++y) {
        for (int x = 0; x < width; x += 4) {
            int remaining = std::min(width-x, 4);
            steps.clear();
            for (int ky = 0; ky < 3; ++ky) {
                const int sy = y + (ky - (Valid ? 0 : 1))*dilation;
                if (sy < 0 || sy >= input_height) continue;
                const float* row = input + static_cast<size_t>(sy)*input_width*cin;
                for (int kx = 0; kx < 3; ++kx) {
                    const int sx = x + (kx - (Valid ? 0 : 1))*dilation;
                    const float* src0 = sx >= 0 && sx < input_width ? row + sx*cin : nullptr;
                    const float* src1 = remaining>1 && sx+1>=0 && sx+1<input_width ? row+(sx+1)*cin : nullptr;
                    const float* src2 = remaining>2 && sx+2>=0 && sx+2<input_width ? row+(sx+2)*cin : nullptr;
                    const float* src3 = remaining>3 && sx+3>=0 && sx+3<input_width ? row+(sx+3)*cin : nullptr;
                    const int spatial_offset=(ky*3+kx)*cin;
                    const auto bits=[](const float* src,int c) {
                        return src ? _mm256_castps_si256(_mm256_loadu_ps(src+c)) : _mm256_setzero_si256();
                    };
                    const __m256i magnitude=_mm256_set1_epi32(0x7fffffff);
                    int ci=0;
                    for (; ci+8<=cin; ci+=8) {
                        const __m256i first=_mm256_or_si256(bits(src0,ci),bits(src1,ci));
                        const __m256i second=_mm256_or_si256(bits(src2,ci),bits(src3,ci));
                        const __m256i selected=_mm256_and_si256(_mm256_or_si256(first,second),magnitude);
                        const auto zeros=_mm256_cmpeq_epi32(selected,_mm256_setzero_si256());
                        unsigned present=static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(zeros))) ^ 255u;
                        while(present) {
                            unsigned long lane;
                            _BitScanForward(&lane,present);
                            present&=present-1;
                            const int in=ci+static_cast<int>(lane);
                            steps.push_back({(spatial_offset+in)*16,src0 ? src0[in] : 0.0f,
                                             src1 ? src1[in] : 0.0f,src2 ? src2[in] : 0.0f,
                                             src3 ? src3[in] : 0.0f});
                        }
                    }
                    // The calibrated layers have channel counts divisible by eight.
                    for (; ci<cin; ++ci) {
                        const float a=src0 ? src0[ci] : 0.0f, b=src1 ? src1[ci] : 0.0f;
                        const float c=src2 ? src2[ci] : 0.0f, d=src3 ? src3[ci] : 0.0f;
                        if (a!=0.0f || b!=0.0f || c!=0.0f || d!=0.0f) {
                            steps.push_back({(spatial_offset+ci)*16,a,b,c,d});
                        }
                    }
                }
            }
            float* dest=output+(static_cast<size_t>(y)*width+x)*cout;
            for (int co=0; co<cout; co+=16) {
                const float* weights=packed+static_cast<size_t>(co)*kernel;
                const __m256 bias0=_mm256_loadu_ps(bias+co), bias1=_mm256_loadu_ps(bias+co+8);
                __m256 a0=bias0, a1=bias1, b0=bias0, b1=bias1;
                __m256 c0=bias0, c1=bias1, d0=bias0, d1=bias1;
                for (const Step& step:steps) {
                    const __m256 w0=_mm256_loadu_ps(weights+step.weight);
                    const __m256 w1=_mm256_loadu_ps(weights+step.weight+8);
                    const __m256 a=_mm256_set1_ps(step.a), b=_mm256_set1_ps(step.b);
                    const __m256 c=_mm256_set1_ps(step.c), d=_mm256_set1_ps(step.d);
                    a0=_mm256_fmadd_ps(w0,a,a0); a1=_mm256_fmadd_ps(w1,a,a1);
                    b0=_mm256_fmadd_ps(w0,b,b0); b1=_mm256_fmadd_ps(w1,b,b1);
                    c0=_mm256_fmadd_ps(w0,c,c0); c1=_mm256_fmadd_ps(w1,c,c1);
                    d0=_mm256_fmadd_ps(w0,d,d0); d1=_mm256_fmadd_ps(w1,d,d1);
                }
                _mm256_storeu_ps(dest+co,_mm256_max_ps(a0,zero));
                _mm256_storeu_ps(dest+co+8,_mm256_max_ps(a1,zero));
                if (remaining<2) continue;
                _mm256_storeu_ps(dest+cout+co,_mm256_max_ps(b0,zero));
                _mm256_storeu_ps(dest+cout+co+8,_mm256_max_ps(b1,zero));
                if (remaining<3) continue;
                _mm256_storeu_ps(dest+2*cout+co,_mm256_max_ps(c0,zero));
                _mm256_storeu_ps(dest+2*cout+co+8,_mm256_max_ps(c1,zero));
                if (remaining<4) continue;
                _mm256_storeu_ps(dest+3*cout+co,_mm256_max_ps(d0,zero));
                _mm256_storeu_ps(dest+3*cout+co+8,_mm256_max_ps(d1,zero));
            }
        }
    }
}

// Reuse each weight vector across eight adjacent pixels for the selected layers.
struct WideStep { int weight; float values[8]; };
template <bool Valid>
static void conv_wide_rows(const float* input,const float* packed,const float* bias,int height,int width,
                     int cin,int cout,int dilation,int first,int stop,float* output) {
    const int kernel=9*cin;
    const int input_width = width + (Valid ? 2 * dilation : 0);
    const int input_height = height + (Valid ? 2 * dilation : 0);
    const auto zero=_mm256_setzero_ps();
    const auto zero_bits=_mm256_setzero_si256();
    const auto magnitude=_mm256_set1_epi32(0x7fffffff);
    std::vector<WideStep> steps;
    steps.reserve(kernel);
    for (int y=first;y<stop;++y) {
        for (int x=0;x<width;x+=8) {
            const int remaining=std::min(width-x,8);
            steps.clear();
            for(int ky=0;ky<3;++ky) {
                const int sy=y+(ky - (Valid ? 0 : 1))*dilation;
                if (sy<0 || sy>=input_height) continue;
                const float* row=input+static_cast<size_t>(sy)*input_width*cin;
                for(int kx=0;kx<3;++kx) {
                    const int sx=x+(kx - (Valid ? 0 : 1))*dilation;
                    const int base=(ky*3+kx)*cin;
                    const float* s0=remaining>0 && sx+0>=0 && sx+0<input_width ? row+(sx+0)*cin : nullptr;
                    const float* s1=remaining>1 && sx+1>=0 && sx+1<input_width ? row+(sx+1)*cin : nullptr;
                    const float* s2=remaining>2 && sx+2>=0 && sx+2<input_width ? row+(sx+2)*cin : nullptr;
                    const float* s3=remaining>3 && sx+3>=0 && sx+3<input_width ? row+(sx+3)*cin : nullptr;
                    const float* s4=remaining>4 && sx+4>=0 && sx+4<input_width ? row+(sx+4)*cin : nullptr;
                    const float* s5=remaining>5 && sx+5>=0 && sx+5<input_width ? row+(sx+5)*cin : nullptr;
                    const float* s6=remaining>6 && sx+6>=0 && sx+6<input_width ? row+(sx+6)*cin : nullptr;
                    const float* s7=remaining>7 && sx+7>=0 && sx+7<input_width ? row+(sx+7)*cin : nullptr;
                    for(int ci=0;ci<cin;ci+=8) {
                        auto selected=zero_bits;
                        if(s0) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s0+ci)));
                        if(s1) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s1+ci)));
                        if(s2) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s2+ci)));
                        if(s3) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s3+ci)));
                        if(s4) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s4+ci)));
                        if(s5) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s5+ci)));
                        if(s6) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s6+ci)));
                        if(s7) selected=_mm256_or_si256(selected,_mm256_castps_si256(_mm256_loadu_ps(s7+ci)));
                        selected=_mm256_and_si256(selected,magnitude);
                        const auto zeros=_mm256_cmpeq_epi32(selected,zero_bits);
                        unsigned present=static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(zeros)))^255u;
                        while(present) {
                            unsigned long lane;
                            _BitScanForward(&lane,present);
                            present&=present-1;
                            const int i=ci+static_cast<int>(lane);
                            steps.push_back({(base+i)*8,{s0?s0[i]:0.0f,s1?s1[i]:0.0f,s2?s2[i]:0.0f,s3?s3[i]:0.0f,s4?s4[i]:0.0f,s5?s5[i]:0.0f,s6?s6[i]:0.0f,s7?s7[i]:0.0f}});
                        }
                    }
                }
            }
            float* dest=output+(static_cast<size_t>(y)*width+x)*cout;
            for(int co=0;co<cout;co+=8) {
                const float* weights=packed+static_cast<size_t>(co)*kernel;
                const auto start=_mm256_loadu_ps(bias+co);
                __m256 accum0=start;
                __m256 accum1=start;
                __m256 accum2=start;
                __m256 accum3=start;
                __m256 accum4=start;
                __m256 accum5=start;
                __m256 accum6=start;
                __m256 accum7=start;
                for(const WideStep& step:steps) {
                    const auto weights8=_mm256_loadu_ps(weights+step.weight);
                        accum0=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[0]),accum0);
                        accum1=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[1]),accum1);
                        accum2=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[2]),accum2);
                        accum3=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[3]),accum3);
                        accum4=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[4]),accum4);
                        accum5=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[5]),accum5);
                        accum6=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[6]),accum6);
                        accum7=_mm256_fmadd_ps(weights8,_mm256_set1_ps(step.values[7]),accum7);
                }
                if(remaining>0) _mm256_storeu_ps(dest+0*cout+co,_mm256_max_ps(accum0,zero));
                if(remaining>1) _mm256_storeu_ps(dest+1*cout+co,_mm256_max_ps(accum1,zero));
                if(remaining>2) _mm256_storeu_ps(dest+2*cout+co,_mm256_max_ps(accum2,zero));
                if(remaining>3) _mm256_storeu_ps(dest+3*cout+co,_mm256_max_ps(accum3,zero));
                if(remaining>4) _mm256_storeu_ps(dest+4*cout+co,_mm256_max_ps(accum4,zero));
                if(remaining>5) _mm256_storeu_ps(dest+5*cout+co,_mm256_max_ps(accum5,zero));
                if(remaining>6) _mm256_storeu_ps(dest+6*cout+co,_mm256_max_ps(accum6,zero));
                if(remaining>7) _mm256_storeu_ps(dest+7*cout+co,_mm256_max_ps(accum7,zero));
            }
        }
    }
}

static bool same_pixel(const float* first, const float* second) {
    auto diff = _mm256_setzero_si256();
    for (int channel = 0; channel < 32; channel += 8) {
        auto a = _mm256_castps_si256(_mm256_loadu_ps(first + channel));
        auto b = _mm256_castps_si256(_mm256_loadu_ps(second + channel));
        diff = _mm256_or_si256(diff, _mm256_xor_si256(a, b));
    }
    return _mm256_testz_si256(diff, diff) != 0;
}

static void convolve_pixel(const float* source, const float* weights, const float* bias,
        int height, int width, int y, int x, float* result) {
    auto a = _mm256_loadu_ps(bias);
    auto b = _mm256_loadu_ps(bias + 8);
    auto c = _mm256_loadu_ps(bias + 16);
    auto d = _mm256_loadu_ps(bias + 24);
    for (int ky = 0; ky < 3; ++ky) {
        int yy = y + ky - 1;
        if (yy < 0 || yy >= height) continue;
        for (int kx = 0; kx < 3; ++kx) {
            int xx = x + kx - 1;
            if (xx < 0 || xx >= width) continue;
            const float* input = source + (static_cast<size_t>(yy)*width + xx)*32;
            const float* kernel = weights + (ky*3 + kx)*32*32;
            for (int channel = 0; channel < 32; ++channel, kernel += 32) {
                auto v = _mm256_set1_ps(input[channel]);
                a = _mm256_fmadd_ps(v, _mm256_loadu_ps(kernel), a);
                b = _mm256_fmadd_ps(v, _mm256_loadu_ps(kernel + 8), b);
                c = _mm256_fmadd_ps(v, _mm256_loadu_ps(kernel + 16), c);
                d = _mm256_fmadd_ps(v, _mm256_loadu_ps(kernel + 24), d);
            }
        }
    }
    auto zero = _mm256_setzero_ps();
    _mm256_storeu_ps(result, _mm256_max_ps(a, zero));
    _mm256_storeu_ps(result + 8, _mm256_max_ps(b, zero));
    _mm256_storeu_ps(result + 16, _mm256_max_ps(c, zero));
    _mm256_storeu_ps(result + 24, _mm256_max_ps(d, zero));
}

static void convolve_pair(const float* source, const float* weights, const float* bias,
        int width, int y, int x0, int x1, float* first, float* second) {
    auto a0 = _mm256_loadu_ps(bias);
    auto a1 = _mm256_loadu_ps(bias + 8);
    auto a2 = _mm256_loadu_ps(bias + 16);
    auto a3 = _mm256_loadu_ps(bias + 24);
    auto b0 = a0, b1 = a1, b2 = a2, b3 = a3;
    const float* kernel = weights;
    for (int ky = 0; ky < 3; ++ky) {
        const float* row = source + static_cast<size_t>(y + ky - 1) * width * 32;
        for (int kx = -1; kx <= 1; ++kx) {
            const float* i0 = row + (x0 + kx)*32;
            const float* i1 = row + (x1 + kx)*32;
            for (int channel = 0; channel < 32; ++channel, kernel += 32) {
                auto v0 = _mm256_set1_ps(i0[channel]);
                auto v1 = _mm256_set1_ps(i1[channel]);
                auto w = _mm256_loadu_ps(kernel);
                a0 = _mm256_fmadd_ps(v0, w, a0);
                b0 = _mm256_fmadd_ps(v1, w, b0);
                w = _mm256_loadu_ps(kernel + 8);
                a1 = _mm256_fmadd_ps(v0, w, a1);
                b1 = _mm256_fmadd_ps(v1, w, b1);
                w = _mm256_loadu_ps(kernel + 16);
                a2 = _mm256_fmadd_ps(v0, w, a2);
                b2 = _mm256_fmadd_ps(v1, w, b2);
                w = _mm256_loadu_ps(kernel + 24);
                a3 = _mm256_fmadd_ps(v0, w, a3);
                b3 = _mm256_fmadd_ps(v1, w, b3);
            }
        }
    }
    auto zero = _mm256_setzero_ps();
    _mm256_storeu_ps(first, _mm256_max_ps(a0, zero));
    _mm256_storeu_ps(first + 8, _mm256_max_ps(a1, zero));
    _mm256_storeu_ps(first + 16, _mm256_max_ps(a2, zero));
    _mm256_storeu_ps(first + 24, _mm256_max_ps(a3, zero));
    _mm256_storeu_ps(second, _mm256_max_ps(b0, zero));
    _mm256_storeu_ps(second + 8, _mm256_max_ps(b1, zero));
    _mm256_storeu_ps(second + 16, _mm256_max_ps(b2, zero));
    _mm256_storeu_ps(second + 24, _mm256_max_ps(b3, zero));
}

static void conv_reuse_rows(const float* source, const float* weights, const float* bias,
        int height, int width, int, int, int, int begin, int end, float* output) {
    int first = std::max(begin - 1, 0);
    int last = std::min(end + 1, height);
    std::vector<uint8_t> equal(static_cast<size_t>(last - first) * width);
    for (int y = first; y < last; ++y) {
        const float* pixel = source + static_cast<size_t>(y)*width*32;
        auto* row = equal.data() + static_cast<size_t>(y-first)*width;
        for (int x = 0; x < width; ++x, pixel += 32) {
            row[x] = (x > 0 && same_pixel(pixel, pixel-32) ? 1 : 0)
                | (y > 0 && same_pixel(pixel, pixel-width*32) ? 2 : 0);
        }
    }
    // Reuse exact 3x3 patches from earlier interior positions owned by this worker.
    // Compute the remaining positions in pairs to share weight loads.
    std::vector<uint8_t> copies(width);
    for (int y = begin; y < end; ++y) {
        float* output_row = output + static_cast<size_t>(y)*width*32;
        int pending = -1;
        for (int x = 0; x < width; ++x) {
            int same = 0;
            bool interior = y > 0 && y < height-1 && x > 0 && x < width-1;
            if (interior) {
                const uint8_t* a = equal.data() + static_cast<size_t>(y-first-1)*width + x-1;
                const uint8_t* b = a + width;
                const uint8_t* c = b + width;
                same = a[0]&a[1]&a[2]&b[0]&b[1]&b[2]&c[0]&c[1]&c[2];
            }
            if (x > 1 && (same & 1)) copies[x] = 1;
            else if (y > begin && y > 1 && (same & 2)) copies[x] = 2;
            else {
                copies[x] = 0;
                if (!interior)
                    convolve_pixel(source, weights, bias, height, width, y, x, output_row + x*32);
                else if (pending < 0)
                    pending = x;
                else {
                    convolve_pair(source, weights, bias, width, y, pending, x,
                                  output_row + pending*32, output_row + x*32);
                    pending = -1;
                }
            }
        }
        if (pending >= 0)
            convolve_pixel(source, weights, bias, height, width, y, pending, output_row + pending*32);
        for (int x = 0; x < width; ++x) {
            float* result = output_row + x*32;
            if (copies[x] == 1) std::memcpy(result, result-32, 32*sizeof(float));
            else if (copies[x] == 2) std::memcpy(result, result-width*32, 32*sizeof(float));
        }
    }
}

using ConvolutionRows = void (*)(const float*, const float*, const float*, int, int,
                                 int, int, int, int, int, float*);

static int run_workers(ConvolutionRows kernel, const float* input, const float* packed, const float* bias, int height, int width,
                int cin, int cout, int dilation, float* output) noexcept {
    const int mid = height/2;
    const unsigned arithmetic_mode = _mm_getcsr();
    bool second_ok = true;
    try {
        std::thread second([&] {
            try {
                _mm_setcsr(arithmetic_mode);
                kernel(input,packed,bias,height,width,cin,cout,dilation,mid,height,output);
            } catch (...) {
                second_ok = false;
            }
        });
        bool first_ok = true;
        try {
            kernel(input,packed,bias,height,width,cin,cout,dilation,0,mid,output);
        } catch (...) {
            first_ok = false;
        }
        second.join();
        return first_ok && second_ok ? 0 : 1;
    } catch (...) {
        return 1;
    }
}

extern "C" __declspec(dllexport)
int sparse_conv(const float* input, const float* packed, const float* bias, int height, int width,
                int cin, int cout, int dilation, float* output) noexcept {
    return run_workers(conv_rows<false>, input, packed, bias, height, width, cin, cout, dilation, output);
}

extern "C" __declspec(dllexport)
int sparse_conv_wide(const float* input, const float* packed, const float* bias, int height, int width,
                     int cin, int cout, int dilation, float* output) noexcept {
    if (cin % 8 || cout % 8) return 1;
    return run_workers(conv_wide_rows<false>, input, packed, bias, height, width, cin, cout, dilation, output);
}

extern "C" __declspec(dllexport)
int sparse_conv_valid(const float* input, const float* packed, const float* bias, int height, int width,
         int cin, int cout, int dilation, float* output) noexcept {
    if (cin % 8 || cout % 16) return 1;
    return run_workers(conv_rows<true>, input, packed, bias, height, width, cin, cout, dilation, output);
}

extern "C" __declspec(dllexport)
int sparse_conv_wide_valid(const float* input, const float* packed, const float* bias, int height, int width,
         int cin, int cout, int dilation, float* output) noexcept {
    if (cin % 8 || cout % 8) return 1;
    return run_workers(conv_wide_rows<true>, input, packed, bias, height, width, cin, cout, dilation, output);
}


extern "C" __declspec(dllexport)
int sparse_conv_reuse(const float* input, const float* packed, const float* bias, int height, int width,
                      int cin, int cout, int dilation, float* output) noexcept {
    if (height <= 0 || width <= 0 || cin != 32 || cout != 32 || dilation != 1) return 1;
    return run_workers(conv_reuse_rows, input, packed, bias, height, width, cin, cout, dilation, output);
}
