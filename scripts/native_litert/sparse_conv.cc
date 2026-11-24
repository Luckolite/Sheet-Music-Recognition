// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
#include <immintrin.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

struct Step {
    int weight;
    float a, b, c, d;
};

// Height and width describe the output. VALID inputs include the full halo.
template<int InputChannels>
static bool same_pixel(const float* first, const float* second) {
    auto diff = _mm256_setzero_si256();
    for (int channel = 0; channel < InputChannels; channel += 8) {
        auto a = _mm256_castps_si256(_mm256_loadu_ps(first + channel));
        auto b = _mm256_castps_si256(_mm256_loadu_ps(second + channel));
        diff = _mm256_or_si256(diff, _mm256_xor_si256(a, b));
    }
    return _mm256_testz_si256(diff, diff) != 0;
}

static bool same_sparse_pixel(const float* a, const float* b, int channels) {
    return channels == 64 ? same_pixel<64>(a, b) : same_pixel<128>(a, b);
}


struct EncoderStripe {
    float* buffer;
    int first, last, offset;
    bool read, write;
};
static thread_local const EncoderStripe* active_stripe = nullptr;

template <bool Valid, bool Reuse = false, bool Stripe = false>
static void conv_rows(
    const float* input, const float* packed, const float* bias, int height, int width,
    int cin, int cout, int dilation, int row_begin, int row_end, float* output) {
    const int kernel = 9 * cin;
    const int input_width = width + (Valid ? 2 * dilation : 0);
    const int input_height = height + (Valid ? 2 * dilation : 0);
    std::vector<Step> steps;
    steps.reserve(kernel);
    const __m256 zero = _mm256_setzero_ps();
    const int first = std::max(row_begin - 1, 0);
    std::vector<uint8_t> equal, copies;
    if constexpr (Reuse) {
        const int last = std::min(row_end + 1, height);
        equal.resize(static_cast<size_t>(last - first) * width);
        copies.resize(width);
        for (int y = first; y < last; ++y) {
            const float* pixel = input + static_cast<size_t>(y) * width * cin;
            uint8_t* row = equal.data() + static_cast<size_t>(y - first) * width;
            for (int x = 0; x < width; ++x, pixel += cin) {
                row[x] = (x > 0 && same_sparse_pixel(pixel, pixel - cin, cin) ? 1 : 0)
                       | (y > 0 && same_sparse_pixel(pixel, pixel - width * cin, cin) ? 2 : 0);
            }
        }
    }
    for (int y = row_begin; y < row_end; ++y) {
        float* output_row = output + static_cast<size_t>(y) * width * cout;
        int stripe_first = 0, stripe_end = 0;
        if constexpr (Stripe) {
            const auto& stripe = *active_stripe;
            if (stripe.read && y >= stripe.first && y < stripe.last) {
                stripe_first = stripe.first;
                stripe_end = stripe.last - stripe.offset;
                const int count = stripe_end - stripe_first;
                const size_t index = static_cast<size_t>(y-stripe.first)*count;
                std::memcpy(output_row + stripe.first*cout, stripe.buffer + index*cout,
                            static_cast<size_t>(count)*cout*sizeof(float));
            }
        }
        if constexpr (Reuse) {
            for (int x = 0; x < width; ++x) {
                int same = 0;
                if (y > 0 && y < height - 1 && x > 0 && x < width - 1) {
                    const auto* above = equal.data() + static_cast<size_t>(y-first-1) * width + x-1;
                    const auto* middle = above + width;
                    const auto* below = middle + width;
                    same = above[0] & above[1] & above[2] & middle[0] & middle[1] & middle[2]
                         & below[0] & below[1] & below[2];
                }
                if constexpr (Stripe) {
                    if (x >= stripe_first && x < stripe_end) {
                        copies[x] = 3;  // Already populated from the previous tile.
                        continue;
                    }
                }
                if (x > 1 && (same & 1)) copies[x] = 1;
                else if (y > row_begin && y > 1 && (same & 2)) copies[x] = 2;
                else copies[x] = 0;
            }
        }
        for (int cursor = 0; cursor < width;) {
            int positions[4]{};
            int remaining;
            if constexpr (Reuse) {
                remaining = 0;
                while (cursor < width && remaining < 4) {
                    if (!copies[cursor]) positions[remaining++] = cursor;
                    ++cursor;
                }
                if (!remaining) break;
            } else {
                remaining = std::min(width - cursor, 4);
                for (int i = 0; i < 4; ++i) positions[i] = cursor + i;
                cursor += 4;
            }
            steps.clear();
            for (int ky = 0; ky < 3; ++ky) {
                const int sy = y + (ky - (Valid ? 0 : 1))*dilation;
                if (sy < 0 || sy >= input_height) continue;
                const float* row = input + static_cast<size_t>(sy)*input_width*cin;
                for (int kx = 0; kx < 3; ++kx) {
                    const auto source = [&](int anchor) -> const float* {
                        const int sx = positions[anchor] + (kx - (Valid ? 0 : 1)) * dilation;
                        return anchor < remaining && sx >= 0 && sx < input_width ? row + sx * cin : nullptr;
                    };
                    const float* src0 = source(0), *src1 = source(1), *src2 = source(2), *src3 = source(3);
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
                _mm256_storeu_ps(output_row+positions[0]*cout+co,_mm256_max_ps(a0,zero));
                _mm256_storeu_ps(output_row+positions[0]*cout+co+8,_mm256_max_ps(a1,zero));
                if (remaining<2) continue;
                _mm256_storeu_ps(output_row+positions[1]*cout+co,_mm256_max_ps(b0,zero));
                _mm256_storeu_ps(output_row+positions[1]*cout+co+8,_mm256_max_ps(b1,zero));
                if (remaining<3) continue;
                _mm256_storeu_ps(output_row+positions[2]*cout+co,_mm256_max_ps(c0,zero));
                _mm256_storeu_ps(output_row+positions[2]*cout+co+8,_mm256_max_ps(c1,zero));
                if (remaining<4) continue;
                _mm256_storeu_ps(output_row+positions[3]*cout+co,_mm256_max_ps(d0,zero));
                _mm256_storeu_ps(output_row+positions[3]*cout+co+8,_mm256_max_ps(d1,zero));
            }
        }
        if constexpr (Reuse) {
            for (int x = 0; x < width; ++x) {
                float* dst = output_row + x * cout;
                if (copies[x] == 1) std::memcpy(dst, dst-cout, cout * sizeof(float));
                else if (copies[x] == 2) std::memcpy(dst, dst-width*cout, cout * sizeof(float));
            }
        }
        if constexpr (Stripe) {
            const auto& stripe = *active_stripe;
            if (stripe.write && y >= stripe.first && y < stripe.last) {
                const int count = stripe.last - stripe.offset - stripe.first;
                std::memcpy(stripe.buffer + static_cast<size_t>(y-stripe.first)*count*cout,
                            output_row + (stripe.first+stripe.offset)*cout,
                            static_cast<size_t>(count)*cout*sizeof(float));
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

template<int InputChannels>
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
            const float* input = source + (static_cast<size_t>(yy)*width + xx)*InputChannels;
            const float* kernel = weights + (ky*3 + kx)*InputChannels*32;
            for (int channel = 0; channel < InputChannels; ++channel, kernel += 32) {
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

template<int InputChannels>
static void convolve_pair(const float* source, const float* weights, const float* bias,
        int width, int y, int x0, int x1, float* first, float* second) {
    auto a0 = _mm256_loadu_ps(bias);
    auto a1 = _mm256_loadu_ps(bias + 8);
    auto a2 = _mm256_loadu_ps(bias + 16);
    auto a3 = _mm256_loadu_ps(bias + 24);
    auto b0 = a0, b1 = a1, b2 = a2, b3 = a3;
    const float* kernel = weights;
    for (int ky = 0; ky < 3; ++ky) {
        const float* row = source + static_cast<size_t>(y + ky - 1) * width * InputChannels;
        for (int kx = -1; kx <= 1; ++kx) {
            const float* i0 = row + (x0 + kx)*InputChannels;
            const float* i1 = row + (x1 + kx)*InputChannels;
            for (int channel = 0; channel < InputChannels; ++channel, kernel += 32) {
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

template<int InputChannels, int OutputChannels>
static void convolve_pixel_groups(const float* source, const float* weights, const float* bias,
        int height, int width, int y, int x, float* result) {
    for (int channel = 0; channel < OutputChannels; channel += 32)
        convolve_pixel<InputChannels>(source, weights + 9 * InputChannels * channel, bias + channel,
                       height, width, y, x, result + channel);
}

template<int InputChannels, int OutputChannels>
static void convolve_pair_groups(const float* source, const float* weights, const float* bias,
        int width, int y, int x0, int x1, float* first, float* second) {
    for (int channel = 0; channel < OutputChannels; channel += 32)
        convolve_pair<InputChannels>(source, weights + 9 * InputChannels * channel, bias + channel, width, y, x0, x1,
                      first + channel, second + channel);
}

template<int InputChannels, int OutputChannels, bool Stripe = false>
static void conv_reuse_rows(const float* source, const float* weights, const float* bias,
        int height, int width, int, int, int, int begin, int end, float* output) {
    int first = std::max(begin - 1, 0);
    int last = std::min(end + 1, height);
    std::vector<uint8_t> equal(static_cast<size_t>(last - first) * width);
    for (int y = first; y < last; ++y) {
        const float* pixel = source + static_cast<size_t>(y)*width*InputChannels;
        auto* row = equal.data() + static_cast<size_t>(y-first)*width;
        for (int x = 0; x < width; ++x, pixel += InputChannels) {
            row[x] = (x > 0 && same_pixel<InputChannels>(pixel, pixel-InputChannels) ? 1 : 0)
                | (y > 0 && same_pixel<InputChannels>(pixel, pixel-width*InputChannels) ? 2 : 0);
        }
    }
    // Reuse exact 3x3 patches from earlier interior positions owned by this worker.
    // Compute the remaining positions in pairs to share weight loads.
    std::vector<uint8_t> copies(width);
    for (int y = begin; y < end; ++y) {
        float* output_row = output + static_cast<size_t>(y)*width*OutputChannels;
        int stripe_first = 0, stripe_end = 0;
        if constexpr (Stripe) {
            const auto& stripe = *active_stripe;
            if (stripe.read && y >= stripe.first && y < stripe.last) {
                stripe_first = stripe.first;
                stripe_end = stripe.last - stripe.offset;
                const int count = stripe_end - stripe_first;
                const size_t index = static_cast<size_t>(y-stripe.first)*count;
                std::memcpy(output_row + stripe.first*OutputChannels, stripe.buffer + index*OutputChannels,
                            static_cast<size_t>(count)*OutputChannels*sizeof(float));
            }
        }
        int pending = -1;
        for (int x = 0; x < width; ++x) {
            if constexpr (Stripe) {
                if (x >= stripe_first && x < stripe_end) {
                    copies[x] = 3;  // Already populated from the previous tile.
                    continue;
                }
            }
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
                    convolve_pixel_groups<InputChannels, OutputChannels>(source, weights, bias, height, width, y, x, output_row + x*OutputChannels);
                else if (pending < 0)
                    pending = x;
                else {
                    convolve_pair_groups<InputChannels, OutputChannels>(source, weights, bias, width, y, pending, x,
                                  output_row + pending*OutputChannels, output_row + x*OutputChannels);
                    pending = -1;
                }
            }
        }
        if (pending >= 0)
            convolve_pixel_groups<InputChannels, OutputChannels>(source, weights, bias, height, width, y, pending, output_row + pending*OutputChannels);
        for (int x = 0; x < width; ++x) {
            float* result = output_row + x*OutputChannels;
            if (copies[x] == 1) std::memcpy(result, result-OutputChannels, OutputChannels*sizeof(float));
            else if (copies[x] == 2) std::memcpy(result, result-width*OutputChannels, OutputChannels*sizeof(float));
        }
        if constexpr (Stripe) {
            const auto& stripe = *active_stripe;
            if (stripe.write && y >= stripe.first && y < stripe.last) {
                const int count = stripe.last - stripe.offset - stripe.first;
                std::memcpy(stripe.buffer + static_cast<size_t>(y-stripe.first)*count*OutputChannels,
                            output_row + (stripe.first+stripe.offset)*OutputChannels,
                            static_cast<size_t>(count)*OutputChannels*sizeof(float));
            }
        }
    }
}

// Share weight loads across neighboring VALID positions.
template<int Pixels>
static void convolve_valid32_block(const float* source, const float* weights, const float* bias,
        int width, int channels, float* output) {
    __m256 acc[Pixels][4];
    for (int j = 0; j < Pixels; ++j)
        for (int i = 0; i < 4; ++i)
            acc[j][i] = _mm256_loadu_ps(bias + 8 * i);

    const float* kernel = weights;
    for (int ky = 0; ky < 3; ++ky) {
        const float* input = source + static_cast<size_t>(ky) * width * channels;
        for (int k = 0; k < 3 * channels; ++k, kernel += 32) {
            __m256 value[Pixels];
            for (int j = 0; j < Pixels; ++j) {
                float x = input[k + j * channels];
                value[j] = _mm256_set1_ps(x);
            }
            for (int i = 0; i < 4; ++i) {
                const auto w = _mm256_loadu_ps(kernel + 8 * i);
                for (int j = 0; j < Pixels; ++j)
                    acc[j][i] = _mm256_fmadd_ps(value[j], w, acc[j][i]);
            }
        }
    }

    const auto zero = _mm256_setzero_ps();
    for (int j = 0; j < Pixels; ++j)
        for (int i = 0; i < 4; ++i)
            _mm256_storeu_ps(output + 32 * j + 8 * i, _mm256_max_ps(acc[j][i], zero));
}

static void conv_valid32_rows(const float* source, const float* weights, const float* bias,
        int, int width, int channels, int, int, int begin, int end, float* dest) {
    const int input_width = width + 2;
    for (int y = begin; y < end; ++y) {
        const float* row = source + static_cast<size_t>(y) * input_width * channels;
        float* output = dest + static_cast<size_t>(y) * width * 32;
        int x = 0;
        for (; x + 3 <= width; x += 3)
            convolve_valid32_block<3>(row + x * channels, weights, bias,
                                      input_width, channels, output + x * 32);
        for (; x < width; ++x)
            convolve_valid32_block<1>(row + x * channels, weights, bias,
                                      input_width, channels, output + x * 32);
    }
}

// Load each group of 16 weights once for five adjacent outputs.
template<int Pixels>
static void convolve_valid_final_block(const float* src, const float* packed, const float* bias,
                               int stride, int channels, int outputs, float* dst) {
    const auto zero = _mm256_setzero_ps();
    for (int group = 0; group < outputs; group += 16) {
        const float* weights = packed + 9 * channels * group;
        const auto b0 = _mm256_loadu_ps(bias + group);
        const auto b1 = _mm256_loadu_ps(bias + group + 8);
        auto lower0 = b0, upper0 = b1;
        auto lower1 = b0, upper1 = b1;
        auto lower2 = b0, upper2 = b1;
        auto lower3 = b0, upper3 = b1;
        auto lower4 = b0, upper4 = b1;
        for (int ky = 0; ky < 3; ++ky) {
            const float* row = src + static_cast<size_t>(ky) * stride;
            for (int k = 0; k < 3 * channels; ++k, weights += 16) {
                const auto w0 = _mm256_loadu_ps(weights);
                const auto w1 = _mm256_loadu_ps(weights + 8);
                if constexpr (Pixels > 0) {
                    const auto value = _mm256_set1_ps(row[k + 0 * channels]);
                    lower0 = _mm256_fmadd_ps(value, w0, lower0);
                    upper0 = _mm256_fmadd_ps(value, w1, upper0);
                }
                if constexpr (Pixels > 1) {
                    const auto value = _mm256_set1_ps(row[k + 1 * channels]);
                    lower1 = _mm256_fmadd_ps(value, w0, lower1);
                    upper1 = _mm256_fmadd_ps(value, w1, upper1);
                }
                if constexpr (Pixels > 2) {
                    const auto value = _mm256_set1_ps(row[k + 2 * channels]);
                    lower2 = _mm256_fmadd_ps(value, w0, lower2);
                    upper2 = _mm256_fmadd_ps(value, w1, upper2);
                }
                if constexpr (Pixels > 3) {
                    const auto value = _mm256_set1_ps(row[k + 3 * channels]);
                    lower3 = _mm256_fmadd_ps(value, w0, lower3);
                    upper3 = _mm256_fmadd_ps(value, w1, upper3);
                }
                if constexpr (Pixels > 4) {
                    const auto value = _mm256_set1_ps(row[k + 4 * channels]);
                    lower4 = _mm256_fmadd_ps(value, w0, lower4);
                    upper4 = _mm256_fmadd_ps(value, w1, upper4);
                }
            }
        }
        if constexpr (Pixels > 0) {
            _mm256_storeu_ps(dst + 0 * outputs + group, _mm256_max_ps(lower0, zero));
            _mm256_storeu_ps(dst + 0 * outputs + group + 8, _mm256_max_ps(upper0, zero));
        }
        if constexpr (Pixels > 1) {
            _mm256_storeu_ps(dst + 1 * outputs + group, _mm256_max_ps(lower1, zero));
            _mm256_storeu_ps(dst + 1 * outputs + group + 8, _mm256_max_ps(upper1, zero));
        }
        if constexpr (Pixels > 2) {
            _mm256_storeu_ps(dst + 2 * outputs + group, _mm256_max_ps(lower2, zero));
            _mm256_storeu_ps(dst + 2 * outputs + group + 8, _mm256_max_ps(upper2, zero));
        }
        if constexpr (Pixels > 3) {
            _mm256_storeu_ps(dst + 3 * outputs + group, _mm256_max_ps(lower3, zero));
            _mm256_storeu_ps(dst + 3 * outputs + group + 8, _mm256_max_ps(upper3, zero));
        }
        if constexpr (Pixels > 4) {
            _mm256_storeu_ps(dst + 4 * outputs + group, _mm256_max_ps(lower4, zero));
            _mm256_storeu_ps(dst + 4 * outputs + group + 8, _mm256_max_ps(upper4, zero));
        }
    }
}

static void conv_valid_final_rows(const float* src, const float* packed, const float* bias,
                            int height, int width, int channels, int outputs, int, int begin, int end, float* dst) {
    const int stride = (width + 2) * channels;
    for (int y = begin; y < end; ++y) {
        const float* input = src + static_cast<size_t>(y) * stride;
        float* output = dst + static_cast<size_t>(y) * width * outputs;
        int x = 0;
        for (; x + 5 <= width; x += 5)
            convolve_valid_final_block<5>(input + x * channels, packed, bias,
                                            stride, channels, outputs, output + x * outputs);
        for (; x < width; ++x)
            convolve_valid_final_block<1>(input + x * channels, packed, bias,
                                     stride, channels, outputs, output + x * outputs);
    }
}

using ConvolutionRows = void (*)(const float*, const float*, const float*, int, int,
                                 int, int, int, int, int, float*);


struct ConvolutionJob {
    ConvolutionRows rows;
    const float* input;
    const float* packed;
    const float* bias;
    int height, width, cin, cout, dilation;
    float* output;
    unsigned mode;
    bool second_ok = false;
    const EncoderStripe* stripe = nullptr;
    int parts = 2;
    std::atomic<int> next{0};

    bool runParts(bool second) noexcept {
        if (parts == 2) return second ? run(height/2, height) : run(0, height/2);
        bool ok = true;
        for (;;) {
            const int part = next.fetch_add(1, std::memory_order_relaxed);
            if (part >= parts) return ok;
            bool piece = run(height * part / parts, height * (part + 1) / parts);
            ok = ok && piece;
        }
    }

    bool run(int begin, int end) const noexcept {
        const auto* prior = active_stripe;
        try {
            active_stripe = stripe;
            rows(input, packed, bias, height, width, cin, cout, dilation, begin, end, output);
            active_stripe = prior;
            return true;
        } catch (...) {
            active_stripe = prior;
            return false;
        }
    }
};

class ConvolutionWorker {
    std::mutex mutex;
    std::condition_variable requested, completed;
    ConvolutionJob* pending = nullptr;
    bool stopping = false;
    std::thread worker;

public:
    ConvolutionWorker() : worker([this] { loop(); }) {}
    ~ConvolutionWorker() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            stopping = true;
        }
        requested.notify_one();
        worker.join();
    }

    int dispatch(ConvolutionJob& job) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            pending = &job;
        }
        requested.notify_one();
        const bool first_ok = job.runParts(false);
        std::unique_lock<std::mutex> lock(mutex);
        completed.wait(lock, [&] { return !pending; });
        return first_ok && job.second_ok ? 0 : 1;
    }

private:
    void loop() {
        std::unique_lock<std::mutex> lock(mutex);
        for (;;) {
            requested.wait(lock, [&] { return stopping || pending; });
            if (stopping) return;
            ConvolutionJob* job = pending;
            lock.unlock();
            _mm_setcsr(job->mode);
            const bool ok = job->runParts(true);
            lock.lock();
            job->second_ok = ok;
            pending = nullptr;
            completed.notify_one();
        }
    }
};

static thread_local ConvolutionWorker* active_worker = nullptr;


// The installed interpreter owns the worker. The current invocation borrows it on this thread.
extern "C" __declspec(dllexport)
void* sparse_worker_create() noexcept {
    try {
        return new ConvolutionWorker();
    } catch (...) {
        return nullptr;
    }
}

extern "C" __declspec(dllexport)
void sparse_worker_destroy(void* worker) noexcept {
    delete static_cast<ConvolutionWorker*>(worker);
}

extern "C" __declspec(dllexport)
void* sparse_worker_exchange(void* worker) noexcept {
    auto* previous = active_worker;
    active_worker = static_cast<ConvolutionWorker*>(worker);
    return previous;
}

static int run_workers(ConvolutionRows kernel, const float* input, const float* packed, const float* bias, int height, int width,
                int cin, int cout, int dilation, float* output) noexcept {
    if (active_worker) {
        ConvolutionJob job{kernel, input, packed, bias, height, width, cin, cout, dilation,
                           output, _mm_getcsr()};
        job.stripe = active_stripe;
        // Sparse music is uneven across the two halves of a tile. The existing
        // two workers can claim the next disjoint strip when they finish a strip.
        if (height == width && dilation == 1
                && ((height == 80 && cout == 128 && (cin == 64 || cin == 128))
                        || (height == 160 && cout == 64 && (cin == 32 || cin == 64))))
            job.parts = 6;
        return active_worker->dispatch(job);
    }
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
    if (height <= 0 || width <= 0 || dilation != 1) return 1;
    if (cin == 32 && cout == 32)
        return run_workers(conv_reuse_rows<32, 32>, input, packed, bias, height, width, cin, cout, dilation, output);
    if (cin == 32 && cout == 64)
        return run_workers(conv_reuse_rows<32, 64>, input, packed, bias, height, width, cin, cout, dilation, output);
    if (cin == 64 && cout == 64)
        return run_workers(conv_reuse_rows<64, 64>, input, packed, bias, height, width, cin, cout, dilation, output);
    return 1;
}


static void conv_valid32_segment_rows(const float*, const float*, const float*,
        int, int, int, int, int, int, int, float*);

extern "C" __declspec(dllexport)
int sparse_conv_valid32(const float* input, const float* packed, const float* bias, int height, int width,
                        int cin, int cout, int dilation, float* output) noexcept {
    if (height <= 0 || width <= 0 || cin != 96 || cout != 32 || dilation != 1) return 1;
    if (height == 194 && width == 194)
        return run_workers(conv_valid32_segment_rows, input, packed, bias, height, width, cin, cout, dilation, output);
    return run_workers(conv_valid32_rows, input, packed, bias, height, width, cin, cout, dilation, output);
}

static bool sparse_anchor_applicable(const float* source, int height, int width, int channels) noexcept {
    int total = 0, repeated = 0;
    for (int y = 2; y < height - 1; y += 5) {
        const float* row = source + static_cast<size_t>(y) * width * channels;
        for (int x = 2; x < width - 1; x += 5) {
            const float* pixel = row + x * channels;
            repeated += same_sparse_pixel(pixel, pixel - channels, channels);
            ++total;
        }
    }
    return total > 0 && repeated * 4 >= total;
}

extern "C" __declspec(dllexport)
int sparse_conv_anchor(const float* source, const float* weights, const float* bias, int height, int width,
                       int inputs, int outputs, int dilation, float* dest) noexcept {
    if (height <= 0 || width <= 0 || (inputs != 64 && inputs != 128) || outputs != 128 || dilation != 1) return 1;
    if (!sparse_anchor_applicable(source, height, width, inputs))
        return sparse_conv(source, weights, bias, height, width, inputs, outputs, dilation, dest);
    return run_workers(conv_rows<false, true>, source, weights, bias, height, width,
                       inputs, outputs, dilation, dest);
}

extern "C" __declspec(dllexport)
int sparse_conv_valid_final(const float* input, const float* packed, const float* bias, int height, int width,
                            int cin, int cout, int dilation, float* output) noexcept {
    if (height <= 0 || width <= 0 || cin != 32 || cout != 32 || dilation != 1) return 1;
    return run_workers(conv_valid_final_rows, input, packed, bias, height, width, cin, cout, dilation, output);
}

// The next output row can reuse two of the three prepared input rows.
struct RollingStep { int weight; float values[6]; };
template<bool Valid, bool FullWidth = Valid>
static void rolling_six_rows(const float* input, const float* packed, const float* bias,
        int height, int width, int cin, int cout, int dilation, int first, int stop, float* output) {
    if (first >= stop) return;
    const int kernel = 9 * cin;
    const int input_width = width + (Valid ? 2 : 0);
    const int spacing = Valid ? 1 : dilation;
    const auto zero = _mm256_setzero_ps();
    const auto zero_bits = _mm256_setzero_si256();
    const auto magnitude = _mm256_set1_epi32(0x7fffffff);
    std::vector<RollingStep> ring[3][3];
    for (auto& row : ring)
        for (auto& columns : row)
            columns.reserve(cin);
    for (int x = 0; x < width; x += 6) {
        const int remaining = FullWidth ? 6 : std::min(width - x, 6);
        const auto collect = [&](int slot, int y) {
            if constexpr (Valid) {
                const float* row = input + static_cast<size_t>(y) * input_width * cin;
                for (int kx = 0; kx < 3; ++kx) {
                    auto& steps = ring[slot][kx];
                    steps.clear();
                    const float* source = row + (x + kx) * cin;
                    for (int ci = 0; ci < cin; ci += 8) {
                        auto selected = zero_bits;
                        for (int i = 0; i < remaining; ++i) {
                            const auto bits = _mm256_castps_si256(_mm256_loadu_ps(source + i * cin + ci));
                            selected = _mm256_or_si256(selected, bits);
                        }
                        const auto zeros = _mm256_cmpeq_epi32(_mm256_and_si256(selected, magnitude), zero_bits);
                        unsigned present = static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(zeros))) ^ 255u;
                        while (present) {
                            unsigned long lane;
                            _BitScanForward(&lane, present);
                            present &= present - 1;
                            const int i = ci + static_cast<int>(lane);
                            RollingStep step{16 * i, {}};
                            for (int pixel = 0; pixel < remaining; ++pixel)
                                step.values[pixel] = source[pixel * cin + i];
                            steps.push_back(step);
                        }
                    }
                }
            } else {
                for (int kx = 0; kx < 3; ++kx) {
                    auto& steps = ring[slot][kx];
                    steps.clear();
                    if (y < 0 || y >= height) continue;
                    const float* row = input + static_cast<size_t>(y) * input_width * cin;
                    const int source_start = x + (kx - 1) * dilation;
                    const float* pixels[6]{};
                    for (int pixel = 0; pixel < remaining; ++pixel) {
                        const int sx = source_start + pixel;
                        if (sx >= 0 && sx < width) pixels[pixel] = row + sx * cin;
                    }
                    for (int ci = 0; ci < cin; ci += 8) {
                        auto selected = zero_bits;
                        for (const float* pixel : pixels) {
                            if (pixel) {
                                const auto bits = _mm256_castps_si256(_mm256_loadu_ps(pixel + ci));
                                selected = _mm256_or_si256(selected, bits);
                            }
                        }
                        const auto zeros = _mm256_cmpeq_epi32(_mm256_and_si256(selected, magnitude), zero_bits);
                        unsigned present = static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(zeros))) ^ 255u;
                        while (present) {
                            unsigned long lane;
                            _BitScanForward(&lane, present);
                            present &= present - 1;
                            const int i = ci + static_cast<int>(lane);
                            RollingStep step{16 * i, {}};
                            for (int pixel = 0; pixel < 6; ++pixel)
                                if (pixels[pixel]) step.values[pixel] = pixels[pixel][i];
                            steps.push_back(step);
                        }
                    }
                }
            }
        };
        for (int start = first; start < stop && start - first < spacing; ++start) {
            collect(0, start - (Valid ? 0 : spacing));
            collect(1, start + (Valid ? 1 : 0));
            collect(2, start + spacing * (Valid ? 2 : 1));
            int slot = 0;
            for (int y = start; y < stop; y += spacing) {
                const std::vector<RollingStep>* rows[] = {ring[slot], ring[(slot + 1) % 3], ring[(slot + 2) % 3]};
                float* dest = output + (static_cast<size_t>(y) * width + x) * cout;
                for (int co = 0; co < cout; co += 16) {
                    const float* weights = packed + static_cast<size_t>(co) * kernel;
                    const auto bias0 = _mm256_loadu_ps(bias + co);
                    const auto bias1 = _mm256_loadu_ps(bias + co + 8);
                    auto lower0 = bias0, upper0 = bias1;
                    auto lower1 = bias0, upper1 = bias1;
                    auto lower2 = bias0, upper2 = bias1;
                    auto lower3 = bias0, upper3 = bias1;
                    auto lower4 = bias0, upper4 = bias1;
                    auto lower5 = bias0, upper5 = bias1;
                    for (int ky = 0; ky < 3; ++ky) {
                        for (int kx = 0; kx < 3; ++kx) {
                            const float* kernelWeights = weights + (ky * 3 + kx) * cin * 16;
                            for (const RollingStep& step : rows[ky][kx]) {
                                const auto w0 = _mm256_loadu_ps(kernelWeights + step.weight);
                                const auto w1 = _mm256_loadu_ps(kernelWeights + step.weight + 8);
                                const auto v0 = _mm256_set1_ps(step.values[0]);
                                lower0 = _mm256_fmadd_ps(v0, w0, lower0);
                                upper0 = _mm256_fmadd_ps(v0, w1, upper0);
                                const auto v1 = _mm256_set1_ps(step.values[1]);
                                lower1 = _mm256_fmadd_ps(v1, w0, lower1);
                                upper1 = _mm256_fmadd_ps(v1, w1, upper1);
                                const auto v2 = _mm256_set1_ps(step.values[2]);
                                lower2 = _mm256_fmadd_ps(v2, w0, lower2);
                                upper2 = _mm256_fmadd_ps(v2, w1, upper2);
                                const auto v3 = _mm256_set1_ps(step.values[3]);
                                lower3 = _mm256_fmadd_ps(v3, w0, lower3);
                                upper3 = _mm256_fmadd_ps(v3, w1, upper3);
                                const auto v4 = _mm256_set1_ps(step.values[4]);
                                lower4 = _mm256_fmadd_ps(v4, w0, lower4);
                                upper4 = _mm256_fmadd_ps(v4, w1, upper4);
                                const auto v5 = _mm256_set1_ps(step.values[5]);
                                lower5 = _mm256_fmadd_ps(v5, w0, lower5);
                                upper5 = _mm256_fmadd_ps(v5, w1, upper5);
                            }
                        }
                    }
                    if constexpr (!FullWidth) { if (remaining <= 0) continue; }
                    _mm256_storeu_ps(dest + 0 * cout + co, _mm256_max_ps(lower0, zero));
                    _mm256_storeu_ps(dest + 0 * cout + co + 8, _mm256_max_ps(upper0, zero));
                    if constexpr (!FullWidth) { if (remaining <= 1) continue; }
                    _mm256_storeu_ps(dest + 1 * cout + co, _mm256_max_ps(lower1, zero));
                    _mm256_storeu_ps(dest + 1 * cout + co + 8, _mm256_max_ps(upper1, zero));
                    if constexpr (!FullWidth) { if (remaining <= 2) continue; }
                    _mm256_storeu_ps(dest + 2 * cout + co, _mm256_max_ps(lower2, zero));
                    _mm256_storeu_ps(dest + 2 * cout + co + 8, _mm256_max_ps(upper2, zero));
                    if constexpr (!FullWidth) { if (remaining <= 3) continue; }
                    _mm256_storeu_ps(dest + 3 * cout + co, _mm256_max_ps(lower3, zero));
                    _mm256_storeu_ps(dest + 3 * cout + co + 8, _mm256_max_ps(upper3, zero));
                    if constexpr (!FullWidth) { if (remaining <= 4) continue; }
                    _mm256_storeu_ps(dest + 4 * cout + co, _mm256_max_ps(lower4, zero));
                    _mm256_storeu_ps(dest + 4 * cout + co + 8, _mm256_max_ps(upper4, zero));
                    if constexpr (!FullWidth) { if (remaining <= 5) continue; }
                    _mm256_storeu_ps(dest + 5 * cout + co, _mm256_max_ps(lower5, zero));
                    _mm256_storeu_ps(dest + 5 * cout + co + 8, _mm256_max_ps(upper5, zero));
                }
                if (y + spacing < stop) collect(slot, y + (Valid ? 3 : 2 * spacing));
                slot = (slot + 1) % 3;
            }
        }
    }
}

static void conv_valid_tap_rows(const float*, const float*, const float*,
        int, int, int, int, int, int, int, float*);

extern "C" __declspec(dllexport)
int sparse_conv_valid_group(const float* input, const float* packed, const float* bias,
                       int h, int w, int ic, int oc, int dilation, float* output) noexcept {
    if (h <= 0 || w <= 0 || w % 6 || ic <= 0 || ic % 8 || oc <= 0 || oc % 16 || dilation != 1) return 1;
    if ((h == 54 && w == 54 && ic == 384 && oc == 128)
            || (h == 102 && w == 102 && ic == 192 && oc == 64)) {
        return run_workers(conv_valid_tap_rows, input, packed, bias, h, w, ic, oc, dilation, output);
    }
    return run_workers(rolling_six_rows<true>, input, packed, bias, h, w, ic, oc, dilation, output);
}

extern "C" __declspec(dllexport)
int sparse_conv_dilated_group(const float* input, const float* packed, const float* bias,
                              int h, int w, int ic, int oc, int dilation, float* output) noexcept {
    if (h <= 0 || w <= 0 || ic <= 0 || ic % 8 || oc <= 0 || oc % 16
            || (dilation != 2 && dilation != 4)) return 1;
    return run_workers(rolling_six_rows<false>, input, packed, bias, h, w, ic, oc, dilation, output);
}

static void conv_valid_tail_tap_rows(const float*, const float*, const float*,
        int, int, int, int, int, int, int, float*);

extern "C" __declspec(dllexport)
int sparse_conv_valid_group_tail(const float* input, const float* packed, const float* bias,
                       int h, int w, int ic, int oc, int dilation, float* output) noexcept {
    if (h <= 0 || w <= 0 || ic <= 0 || ic % 8 || oc <= 0 || oc % 16 || dilation != 1) return 1;
    if ((h == 52 && w == 52 && ic == 128 && oc == 128)
            || (h == 100 && w == 100 && ic == 64 && oc == 64)) {
        return run_workers(conv_valid_tail_tap_rows, input, packed, bias, h, w, ic, oc, dilation, output);
    }
    return run_workers(rolling_six_rows<true, false>, input, packed, bias, h, w, ic, oc, dilation, output);
}

struct SegmentChannels {
    uint8_t indices[96];
    int count;
};

static void collect_segment_channels(SegmentChannels& selected, const float* first, int positions) {
    constexpr int channels = 96;
    const auto zero = _mm256_setzero_si256();
    const auto magnitude = _mm256_set1_epi32(0x7fffffff);
    selected.count = 0;
    for (int ci=0; ci<channels; ci+=8) {
        auto merged = zero;
        const float* pixel = first + ci;
        for (int x=0; x<positions; ++x, pixel+=channels)
            merged = _mm256_or_si256(merged, _mm256_castps_si256(_mm256_loadu_ps(pixel)));
        auto empty = _mm256_cmpeq_epi32(_mm256_and_si256(merged, magnitude), zero);
        unsigned bits = static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(empty))) ^ 255u;
        while (bits) {
            unsigned long bit;
            _BitScanForward(&bit, bits);
            bits &= bits - 1;
            selected.indices[selected.count++] = static_cast<uint8_t>(ci + bit);
        }
    }
}

static void convolve_segment_triple(const float* source, const float* packed, const float* bias,
                                    int stride, const SegmentChannels* const* rows, float* output) {
    constexpr int channels = 96;
    auto a0 = _mm256_loadu_ps(bias + 0), a1 = _mm256_loadu_ps(bias + 8), a2 = _mm256_loadu_ps(bias + 16), a3 = _mm256_loadu_ps(bias + 24);
    auto a4 = _mm256_loadu_ps(bias + 0), a5 = _mm256_loadu_ps(bias + 8), a6 = _mm256_loadu_ps(bias + 16), a7 = _mm256_loadu_ps(bias + 24);
    auto a8 = _mm256_loadu_ps(bias + 0), a9 = _mm256_loadu_ps(bias + 8), a10 = _mm256_loadu_ps(bias + 16), a11 = _mm256_loadu_ps(bias + 24);

    for (int ky=0; ky<3; ++ky) {
        const float* row = source + static_cast<size_t>(ky)*stride;
        const SegmentChannels& active = *rows[ky];
        for (int kx=0; kx<3; ++kx) {
            const float* first = row + kx*channels;
            const float* weights = packed + (ky*3+kx)*channels*32;
            for (int i=0; i<active.count; ++i) {
                const int ci = active.indices[i];
                const auto v0 = _mm256_set1_ps(first[ci]);
                const auto v1 = _mm256_set1_ps(first[channels+ci]);
                const auto v2 = _mm256_set1_ps(first[2*channels+ci]);
                const float* w = weights + 32 * ci;
                const auto w0 = _mm256_loadu_ps(w + 0);
                a0 = _mm256_fmadd_ps(v0, w0, a0);
                a4 = _mm256_fmadd_ps(v1, w0, a4);
                a8 = _mm256_fmadd_ps(v2, w0, a8);
                const auto w1 = _mm256_loadu_ps(w + 8);
                a1 = _mm256_fmadd_ps(v0, w1, a1);
                a5 = _mm256_fmadd_ps(v1, w1, a5);
                a9 = _mm256_fmadd_ps(v2, w1, a9);
                const auto w2 = _mm256_loadu_ps(w + 16);
                a2 = _mm256_fmadd_ps(v0, w2, a2);
                a6 = _mm256_fmadd_ps(v1, w2, a6);
                a10 = _mm256_fmadd_ps(v2, w2, a10);
                const auto w3 = _mm256_loadu_ps(w + 24);
                a3 = _mm256_fmadd_ps(v0, w3, a3);
                a7 = _mm256_fmadd_ps(v1, w3, a7);
                a11 = _mm256_fmadd_ps(v2, w3, a11);
            }
        }
    }
    const auto zero = _mm256_setzero_ps();
    _mm256_storeu_ps(output + 0, _mm256_max_ps(a0, zero));
    _mm256_storeu_ps(output + 8, _mm256_max_ps(a1, zero));
    _mm256_storeu_ps(output + 16, _mm256_max_ps(a2, zero));
    _mm256_storeu_ps(output + 24, _mm256_max_ps(a3, zero));
    _mm256_storeu_ps(output + 32, _mm256_max_ps(a4, zero));
    _mm256_storeu_ps(output + 40, _mm256_max_ps(a5, zero));
    _mm256_storeu_ps(output + 48, _mm256_max_ps(a6, zero));
    _mm256_storeu_ps(output + 56, _mm256_max_ps(a7, zero));
    _mm256_storeu_ps(output + 64, _mm256_max_ps(a8, zero));
    _mm256_storeu_ps(output + 72, _mm256_max_ps(a9, zero));
    _mm256_storeu_ps(output + 80, _mm256_max_ps(a10, zero));
    _mm256_storeu_ps(output + 88, _mm256_max_ps(a11, zero));
}

static void conv_valid32_segment_rows(const float* source, const float* weights, const float* bias,
        int, int width, int channels, int, int, int begin, int end, float* dest) {
    if (begin >= end) return;
    constexpr int outputs = 32, segment = 24;
    const int inputWidth = width + 2;
    const int stride = inputWidth * channels;
    // Dense activations use the existing contiguous kernel without building segment lists.
    const float* sample = source + static_cast<size_t>(begin) * stride;
    bool possibleSparse = false;
    for (int ci = 0; ci < channels; ci += 8) {
        const auto values = _mm256_loadu_ps(sample + ci);
        auto empty = _mm256_cmp_ps(values, _mm256_setzero_ps(), _CMP_EQ_OQ);
        if (_mm256_movemask_ps(empty)) {
            possibleSparse = true;
            break;
        }
    }
    if (!possibleSparse) {
        conv_valid32_rows(source, weights, bias, 0, width, channels, outputs, 1, begin, end, dest);
        return;
    }
    for (int left = 0; left < width; left += segment) {
        const int right = std::min(width, left + segment);
        SegmentChannels cached[3];
        const auto collect = [&](int slot, int y) {
            const float* row = source + (static_cast<size_t>(y) * inputWidth + left) * channels;
            collect_segment_channels(cached[slot], row, right - left + 2);
        };
        for (int ky=0; ky<3; ++ky) collect(ky, begin+ky);
        int slot = 0;
        for (int y=begin; y<end; ++y) {
            const SegmentChannels* active[3] = {&cached[slot], &cached[(slot+1)%3], &cached[(slot+2)%3]};
            const float* row = source + static_cast<size_t>(y) * stride;
            float* output = dest + static_cast<size_t>(y) * width * outputs;
            int x = left;
            for (; x + 3 <= right; x += 3) {
                if (active[0]->count == channels && active[1]->count == channels && active[2]->count == channels)
                    convolve_valid32_block<3>(row+x*channels,weights,bias,inputWidth,channels,output+x*outputs);
                else
                    convolve_segment_triple(row+x*channels,weights,bias,stride,active,output+x*outputs);
            }
            for (; x < right; ++x)
                convolve_valid32_block<1>(row+x*channels,weights,bias,inputWidth,channels,output+x*outputs);
            if (y+1 < end) collect(slot,y+3);
            slot = (slot+1)%3;
        }
    }
}

struct ValidTapChannels {
    uint16_t indices[384];
    int count;
};


static void collect_tap_channels(ValidTapChannels (&active)[3], const float* source, int channels) {
    const auto zero = _mm256_setzero_si256();
    const auto magnitude = _mm256_set1_epi32(0x7fffffff);
    for (auto& entry : active) entry.count = 0;
    for (int ci = 0; ci < channels; ci += 8) {
        const float* first = source + ci;
        const auto load = [&](int i) {
            return _mm256_castps_si256(_mm256_loadu_ps(first + i * channels));
        };
        const auto shared = _mm256_or_si256(_mm256_or_si256(load(2), load(3)),
                                            _mm256_or_si256(load(4), load(5)));
        const auto left = load(1), right = load(6);
        const __m256i outside[] = {_mm256_or_si256(load(0), left),
                                   _mm256_or_si256(left, right),
                                   _mm256_or_si256(right, load(7))};
        for (int tap=0; tap<3; ++tap) {
            const auto combined = _mm256_and_si256(_mm256_or_si256(shared, outside[tap]), magnitude);
            const auto empty = _mm256_cmpeq_epi32(combined, zero);
            unsigned bits = static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(empty))) ^ 255u;
            while (bits) {
                unsigned long lane;
                _BitScanForward(&lane, bits);
                bits &= bits - 1;
                active[tap].indices[active[tap].count++] = static_cast<uint16_t>(ci + lane);
            }
        }
    }
}

static void convolve_segment_six(const float* source, const float* packed, const float* bias,
        int stride, int channels, int outputs, const ValidTapChannels* const* rows, float* output) {
    const auto zero = _mm256_setzero_ps();
    for (int co = 0; co < outputs; co += 16) {
        const float* weights = packed + static_cast<size_t>(co) * 9 * channels;
        const auto b0 = _mm256_loadu_ps(bias + co);
        const auto b1 = _mm256_loadu_ps(bias + co + 8);
        auto lo0 = b0, hi0 = b1;
        auto lo1 = b0, hi1 = b1;
        auto lo2 = b0, hi2 = b1;
        auto lo3 = b0, hi3 = b1;
        auto lo4 = b0, hi4 = b1;
        auto lo5 = b0, hi5 = b1;

        for (int ky=0; ky<3; ++ky) {
            const float* row = source + static_cast<size_t>(ky) * stride;
            for (int kx=0; kx<3; ++kx) {
                const ValidTapChannels& active = rows[ky][kx];
                const float* first = row + kx*channels;
                const float* tap = weights + (ky*3+kx)*channels*16;
                for (int i=0; i<active.count; ++i) {
                    const int ci = active.indices[i];
                    const auto w0 = _mm256_loadu_ps(tap + 16*ci);
                    const auto w1 = _mm256_loadu_ps(tap + 16*ci + 8);
                    const auto v0 = _mm256_set1_ps(first[0*channels + ci]);
                    lo0 = _mm256_fmadd_ps(v0, w0, lo0);
                    hi0 = _mm256_fmadd_ps(v0, w1, hi0);
                    const auto v1 = _mm256_set1_ps(first[1*channels + ci]);
                    lo1 = _mm256_fmadd_ps(v1, w0, lo1);
                    hi1 = _mm256_fmadd_ps(v1, w1, hi1);
                    const auto v2 = _mm256_set1_ps(first[2*channels + ci]);
                    lo2 = _mm256_fmadd_ps(v2, w0, lo2);
                    hi2 = _mm256_fmadd_ps(v2, w1, hi2);
                    const auto v3 = _mm256_set1_ps(first[3*channels + ci]);
                    lo3 = _mm256_fmadd_ps(v3, w0, lo3);
                    hi3 = _mm256_fmadd_ps(v3, w1, hi3);
                    const auto v4 = _mm256_set1_ps(first[4*channels + ci]);
                    lo4 = _mm256_fmadd_ps(v4, w0, lo4);
                    hi4 = _mm256_fmadd_ps(v4, w1, hi4);
                    const auto v5 = _mm256_set1_ps(first[5*channels + ci]);
                    lo5 = _mm256_fmadd_ps(v5, w0, lo5);
                    hi5 = _mm256_fmadd_ps(v5, w1, hi5);
                }
            }
        }
        _mm256_storeu_ps(output + 0*outputs + co, _mm256_max_ps(lo0,zero));
        _mm256_storeu_ps(output + 0*outputs + co + 8, _mm256_max_ps(hi0,zero));
        _mm256_storeu_ps(output + 1*outputs + co, _mm256_max_ps(lo1,zero));
        _mm256_storeu_ps(output + 1*outputs + co + 8, _mm256_max_ps(hi1,zero));
        _mm256_storeu_ps(output + 2*outputs + co, _mm256_max_ps(lo2,zero));
        _mm256_storeu_ps(output + 2*outputs + co + 8, _mm256_max_ps(hi2,zero));
        _mm256_storeu_ps(output + 3*outputs + co, _mm256_max_ps(lo3,zero));
        _mm256_storeu_ps(output + 3*outputs + co + 8, _mm256_max_ps(hi3,zero));
        _mm256_storeu_ps(output + 4*outputs + co, _mm256_max_ps(lo4,zero));
        _mm256_storeu_ps(output + 4*outputs + co + 8, _mm256_max_ps(hi4,zero));
        _mm256_storeu_ps(output + 5*outputs + co, _mm256_max_ps(lo5,zero));
        _mm256_storeu_ps(output + 5*outputs + co + 8, _mm256_max_ps(hi5,zero));
    }
}

static void conv_valid_tap_rows(const float* source, const float* weights, const float* bias,
        int, int width, int channels, int outputs, int, int begin, int end, float* dest) {
    if (begin >= end) return;
    const int inputWidth = width + 2;
    const int stride = inputWidth * channels;
    for (int left = 0; left < width; left += 6) {
        ValidTapChannels cached[3][3];
        const auto collect = [&](int slot, int y) {
            const float* row = source + (static_cast<size_t>(y) * inputWidth + left) * channels;
            collect_tap_channels(cached[slot], row, channels);
        };
        for (int ky=0; ky<3; ++ky) collect(ky, begin+ky);
        int slot = 0;
        for (int y=begin; y<end; ++y) {
            const ValidTapChannels* active[3] = {cached[slot], cached[(slot+1)%3], cached[(slot+2)%3]};
            const float* row = source + static_cast<size_t>(y) * stride;
            float* output = dest + static_cast<size_t>(y) * width * outputs;
            convolve_segment_six(row+left*channels,weights,bias,stride,channels,outputs,active,output+left*outputs);
            if (y+1 < end) collect(slot,y+3);
            slot = (slot+1)%3;
        }
    }
}

struct IndexedTap {
    ValidTapChannels active;
    const float* pixels[6];
};

static void collect_valid_tail_taps(IndexedTap (&taps)[3], const float* row, const float* fallback,
        int left, int channels, int remaining) {
    const auto zero = _mm256_setzero_si256();
    const auto magnitude = _mm256_set1_epi32(0x7fffffff);
    for (int tap=0; tap<3; ++tap) {
        auto& entry = taps[tap];
        entry.active.count = 0;
        const int first = left + tap;
        for (int x=0; x<6; ++x) {
            const int col = first + x;
            entry.pixels[x] = x < remaining ? row + col*channels : fallback;
        }
        for (int ci=0; ci<channels; ci+=8) {
            auto mask = zero;
            for (const float* pixel : entry.pixels)
                mask = _mm256_or_si256(mask, _mm256_castps_si256(_mm256_loadu_ps(pixel+ci)));
            auto empty = _mm256_cmpeq_epi32(_mm256_and_si256(mask,magnitude),zero);
            unsigned bits = static_cast<unsigned>(_mm256_movemask_ps(_mm256_castsi256_ps(empty))) ^ 255u;
            while (bits) {
                unsigned long lane;
                _BitScanForward(&lane, bits);
                bits &= bits-1;
                entry.active.indices[entry.active.count++] = static_cast<uint16_t>(ci+lane);
            }
        }
    }
}

static void convolve_valid_tail_taps(const float* packed, const float* bias,
        int channels, int outputs, int remaining, const IndexedTap* const* rows, float* output) {
    const auto zero = _mm256_setzero_ps();
    for (int co = 0; co < outputs; co += 16) {
        const float* weights = packed + static_cast<size_t>(co) * 9 * channels;
        const auto b0 = _mm256_loadu_ps(bias + co);
        const auto b1 = _mm256_loadu_ps(bias + co + 8);
        auto lo0 = b0, hi0 = b1;
        auto lo1 = b0, hi1 = b1;
        auto lo2 = b0, hi2 = b1;
        auto lo3 = b0, hi3 = b1;
        auto lo4 = b0, hi4 = b1;
        auto lo5 = b0, hi5 = b1;

        for (int ky=0; ky<3; ++ky) {
            for (int kx=0; kx<3; ++kx) {
                const auto& source = rows[ky][kx];
                const auto& active = source.active;
                const float* tap = weights + (ky*3+kx)*channels*16;
                for (int i=0; i<active.count; ++i) {
                    const int ci = active.indices[i];
                    const auto w0 = _mm256_loadu_ps(tap + 16*ci);
                    const auto w1 = _mm256_loadu_ps(tap + 16*ci + 8);
                    const auto v0 = _mm256_set1_ps(source.pixels[0][ci]);
                    lo0 = _mm256_fmadd_ps(v0, w0, lo0);
                    hi0 = _mm256_fmadd_ps(v0, w1, hi0);
                    const auto v1 = _mm256_set1_ps(source.pixels[1][ci]);
                    lo1 = _mm256_fmadd_ps(v1, w0, lo1);
                    hi1 = _mm256_fmadd_ps(v1, w1, hi1);
                    const auto v2 = _mm256_set1_ps(source.pixels[2][ci]);
                    lo2 = _mm256_fmadd_ps(v2, w0, lo2);
                    hi2 = _mm256_fmadd_ps(v2, w1, hi2);
                    const auto v3 = _mm256_set1_ps(source.pixels[3][ci]);
                    lo3 = _mm256_fmadd_ps(v3, w0, lo3);
                    hi3 = _mm256_fmadd_ps(v3, w1, hi3);
                    const auto v4 = _mm256_set1_ps(source.pixels[4][ci]);
                    lo4 = _mm256_fmadd_ps(v4, w0, lo4);
                    hi4 = _mm256_fmadd_ps(v4, w1, hi4);
                    const auto v5 = _mm256_set1_ps(source.pixels[5][ci]);
                    lo5 = _mm256_fmadd_ps(v5, w0, lo5);
                    hi5 = _mm256_fmadd_ps(v5, w1, hi5);
                }
            }
        }
        if (remaining > 0) {
            _mm256_storeu_ps(output + 0*outputs + co, _mm256_max_ps(lo0,zero));
            _mm256_storeu_ps(output + 0*outputs + co + 8, _mm256_max_ps(hi0,zero));
        }
        if (remaining > 1) {
            _mm256_storeu_ps(output + 1*outputs + co, _mm256_max_ps(lo1,zero));
            _mm256_storeu_ps(output + 1*outputs + co + 8, _mm256_max_ps(hi1,zero));
        }
        if (remaining > 2) {
            _mm256_storeu_ps(output + 2*outputs + co, _mm256_max_ps(lo2,zero));
            _mm256_storeu_ps(output + 2*outputs + co + 8, _mm256_max_ps(hi2,zero));
        }
        if (remaining > 3) {
            _mm256_storeu_ps(output + 3*outputs + co, _mm256_max_ps(lo3,zero));
            _mm256_storeu_ps(output + 3*outputs + co + 8, _mm256_max_ps(hi3,zero));
        }
        if (remaining > 4) {
            _mm256_storeu_ps(output + 4*outputs + co, _mm256_max_ps(lo4,zero));
            _mm256_storeu_ps(output + 4*outputs + co + 8, _mm256_max_ps(hi4,zero));
        }
        if (remaining > 5) {
            _mm256_storeu_ps(output + 5*outputs + co, _mm256_max_ps(lo5,zero));
            _mm256_storeu_ps(output + 5*outputs + co + 8, _mm256_max_ps(hi5,zero));
        }
    }
}



static void conv_valid_tail_tap_rows(const float* source, const float* weights, const float* bias,
        int, int width, int channels, int outputs, int, int begin,int end,float* dest) {
    if (begin >= end) return;
    const float zeroPixel[384]{};
    for (int left=0; left<width; left+=6) {
        const int remaining = std::min(width-left,6);
        IndexedTap cached[3][3];
        auto collect = [&](int slot, int row) {
            const float* pixels = source + static_cast<size_t>(row)*(width+2)*channels;
            collect_valid_tail_taps(cached[slot], pixels, zeroPixel, left, channels, remaining);
        };
        collect(0, begin);
        collect(1, begin+1);
        collect(2, begin+2);
        int slot=0;
        for (int y=begin; y<end; ++y) {
            const IndexedTap* active[3] = {cached[slot],cached[(slot+1)%3],cached[(slot+2)%3]};
            float* output = dest + (static_cast<size_t>(y)*width+left)*outputs;
            convolve_valid_tail_taps(weights,bias,channels,outputs,remaining,active,output);
            if (y+1 < end) collect(slot,y+3);
            slot=(slot+1)%3;
        }
    }
}

template<int Low, int High>
static void merge_resize2(const float* low, const float* high, int lowWidth, int highWidth,
        int h, int w, float* result) {
    constexpr int channels = Low + High;
    for (int y = 0; y < h; y += 2) {
        const float* small = low + static_cast<size_t>(y / 2) * lowWidth * Low;
        const float* large0 = high + static_cast<size_t>(y) * highWidth * High;
        const float* large1 = large0 + highWidth * High;
        float* row0 = result + static_cast<size_t>(y) * w * channels;
        float* row1 = row0 + w * channels;
        for (int x = 0; x < w; x += 2) {
            std::memcpy(row0, small, Low * sizeof(float));
            std::memcpy(row0 + channels, small, Low * sizeof(float));
            std::memcpy(row1, small, Low * sizeof(float));
            std::memcpy(row1 + channels, small, Low * sizeof(float));
            std::memcpy(row0 + Low, large0, High * sizeof(float));
            std::memcpy(row0 + channels + Low, large0 + High, High * sizeof(float));
            std::memcpy(row1 + Low, large1, High * sizeof(float));
            std::memcpy(row1 + channels + Low, large1 + High, High * sizeof(float));
            small += Low;
            large0 += 2 * High;
            large1 += 2 * High;
            row0 += 2 * channels;
            row1 += 2 * channels;
        }
    }
}
extern "C" __declspec(dllexport)
int score_merge_resize2(const float* low, const float* high,
        int lowWidth, int highWidth, int h, int w, int highChannels, float* result) noexcept {
    if (!low || !high || !result || h <= 0 || w <= 0 || h % 2 || w % 2
            || lowWidth < w / 2 || highWidth < w) return 1;
    switch (highChannels) {
        case 32: merge_resize2<64,32>(low,high,lowWidth,highWidth,h,w,result); return 0;
        case 64: merge_resize2<128,64>(low,high,lowWidth,highWidth,h,w,result); return 0;
        case 128: merge_resize2<256,128>(low,high,lowWidth,highWidth,h,w,result); return 0;
        default: return 1;
    }
}

extern "C" __declspec(dllexport)
int score_encoder_stripe(const float* input, const float* weights, const float* bias,
        int height, int width, int cin, int cout, int dilation, float* output,
        float* buffer, int first, int last, int offset, int mode, unsigned* cache_mode) noexcept {
    ConvolutionRows kernel = nullptr;
    if (height == 80 && width == 80 && (cin == 64 || cin == 128) && cout == 128 && offset == 48)
        kernel = conv_rows<false, true, true>;
    else if (height == 160 && width == 160 && cin == 32 && cout == 64 && offset == 96)
        kernel = conv_reuse_rows<32,64,true>;
    else if (height == 160 && width == 160 && cin == 64 && cout == 64 && offset == 96)
        kernel = conv_reuse_rows<64,64,true>;
    if (!active_worker || !buffer || !cache_mode || !kernel || dilation != 1
            || first < 1 || last > width - 1 || offset <= 0
            || last - offset <= first || last - offset > first + offset) return 1;
    // Read a prior stripe only under the same rounding and denormal controls.
    const unsigned csr = _mm_getcsr() & 0xffc0;
    const EncoderStripe stripe{buffer, first, last, offset,
                               (mode & 1) && *cache_mode == csr, (mode & 2) != 0};
    const auto* previous = active_stripe;
    active_stripe = &stripe;
    const int result = run_workers(kernel, input, weights, bias,
                                   height, width, cin, cout, dilation, output);
    active_stripe = previous;
    if (!result && stripe.write) *cache_mode = csr;
    return result;
}
