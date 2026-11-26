/* Copyright 2026 Luckolite. Licensed under the Apache License, Version 2.0. */
#include "onnxruntime_c_api.h"
#include <stdatomic.h>

#define CHECK(expr) do { OrtStatusPtr status = (expr); if (status != NULL) return status; } while (0)
enum { max_sites = 16384 };

static OrtStatusPtr shape(const OrtApi *api, const OrtValue *tensor, size_t rank, int64_t *dims) {
    OrtTensorTypeAndShapeInfo *info;
    CHECK(api->GetTensorTypeAndShape(tensor, &info));
    size_t actual_rank = 0;
    OrtStatusPtr status = api->GetDimensionsCount(info, &actual_rank);
    if (!status && actual_rank != rank)
        status = api->CreateStatus(ORT_INVALID_ARGUMENT, "Unexpected pointwise tensor rank");
    if (!status) status = api->GetDimensions(info, dims, rank);
    api->ReleaseTensorTypeAndShapeInfo(info);
    return status;
}

static OrtStatusPtr ORT_API_CALL pack(void *binding, OrtKernelContext *context) {
    const OrtApi *api = binding;
    const OrtValue *input;
    CHECK(api->KernelContext_GetInput(context, 0, &input));
    int64_t dims[4];
    CHECK(shape(api, input, 4, dims));
    if (dims[0] != 1 || dims[1] < 1 || dims[1] > 4096 ||
        dims[2] < 1 || dims[2] > max_sites || dims[3] < 1 || dims[3] > max_sites ||
        dims[2] * dims[3] > max_sites)
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Unsupported pointwise input shape");
    const int sites = (int)(dims[2] * dims[3]);
    const int channels = (int)dims[1];

    void *raw;
    CHECK(api->GetTensorMutableData((OrtValue *)input, &raw));
    const uint32_t *src = raw;
    uint32_t difference[max_sites];
    for (int i = 1; i < sites; ++i) difference[i] = src[i] ^ src[i - 1];
    for (int c = 1; c < channels; ++c) {
        const uint32_t *row = src + c * sites;
        for (int i = 1; i < sites; ++i)
            difference[i] |= row[i] ^ row[i - 1];
    }
    int count = 1;
    for (int i = 1; i < sites; ++i) if (difference[i]) ++count;

    int64_t packed_dims[] = {channels, count};
    int64_t run_dims[] = {count + 3};
    OrtValue *packed, *metadata;
    CHECK(api->KernelContext_GetOutput(context, 0, packed_dims, 2, &packed));
    CHECK(api->KernelContext_GetOutput(context, 1, run_dims, 1, &metadata));
    void *packed_raw, *meta_raw;
    CHECK(api->GetTensorMutableData(packed, &packed_raw));
    CHECK(api->GetTensorMutableData(metadata, &meta_raw));
    uint32_t *dest = packed_raw;
    int32_t *meta = meta_raw;
    meta[0] = (int32_t)dims[2];
    meta[1] = (int32_t)dims[3];
    int32_t *starts = meta + 2;
    int next = 0;
    starts[next++] = 0;
    for (int i = 1; i < sites; ++i) if (difference[i]) starts[next++] = i;
    starts[count] = sites;
    for (int c = 0; c < channels; ++c) {
        const uint32_t *row = src + c * sites;
        uint32_t *out = dest + c * count;
        for (int j = 0; j < count; ++j) out[j] = row[starts[j]];
    }
    return NULL;
}

static OrtStatusPtr ORT_API_CALL expand(void *binding, OrtKernelContext *context) {
    const OrtApi *api = binding;
    const OrtValue *input, *metadata;
    CHECK(api->KernelContext_GetInput(context, 0, &input));
    CHECK(api->KernelContext_GetInput(context, 1, &metadata));
    int64_t dims[2], meta_dims[1];
    CHECK(shape(api, input, 2, dims));
    CHECK(shape(api, metadata, 1, meta_dims));
    if (dims[0] < 1 || dims[0] > 4096 || dims[1] < 1 || dims[1] > max_sites ||
        meta_dims[0] != dims[1] + 3)
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid pointwise run metadata");
    void *raw, *meta_raw;
    CHECK(api->GetTensorMutableData((OrtValue *)input, &raw));
    CHECK(api->GetTensorMutableData((OrtValue *)metadata, &meta_raw));
    const uint32_t *src = raw;
    const int32_t *meta = meta_raw;
    const int count = (int)dims[1];
    if (meta[0] < 1 || meta[0] > max_sites || meta[1] < 1 || meta[1] > max_sites ||
        (int64_t)meta[0] * meta[1] > max_sites)
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid pointwise plane dimensions");
    const int sites = meta[0] * meta[1];
    const int32_t *starts = meta + 2;
    if (starts[0] != 0 || starts[count] != sites)
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid pointwise run bounds");
    for (int i = 1; i <= count; ++i)
        if (starts[i] <= starts[i - 1])
            return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid pointwise run order");
    int64_t out_dims[] = {1, dims[0], meta[0], meta[1]};
    OrtValue *output;
    CHECK(api->KernelContext_GetOutput(context, 0, out_dims, 4, &output));
    void *out_raw;
    CHECK(api->GetTensorMutableData(output, &out_raw));
    uint32_t *dest = out_raw;
    for (int c = 0; c < (int)dims[0]; ++c) {
        const uint32_t *row = src + c * count;
        uint32_t *out = dest + c * sites;
        for (int i = 0; i < count; ++i) {
            const uint32_t v = row[i];
            for (int at = starts[i]; at < starts[i + 1]; ++at) out[at] = v;
        }
    }
    return NULL;
}

static OrtStatusPtr ORT_API_CALL create(const OrtCustomOp *op, const OrtApi *api,
        const OrtKernelInfo *info, void **binding) {
    (void)op; (void)info; *binding = (void *)api;
    return NULL;
}
static const char *ORT_API_CALL pack_name(const OrtCustomOp *op) { (void)op; return "PackSpatialRuns"; }
static const char *ORT_API_CALL expand_name(const OrtCustomOp *op) { (void)op; return "ExpandSpatialRuns"; }
static const char *ORT_API_CALL cpu(const OrtCustomOp *op) { (void)op; return NULL; }
static size_t ORT_API_CALL one(const OrtCustomOp *op) { (void)op; return 1; }
static size_t ORT_API_CALL two(const OrtCustomOp *op) { (void)op; return 2; }
static int ORT_API_CALL version(const OrtCustomOp *op) { (void)op; return 1; }
static ONNXTensorElementDataType ORT_API_CALL tensor_type(const OrtCustomOp *op, size_t index) {
    (void)op;
    return index == 0 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT : ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32;
}
static OrtCustomOpInputOutputCharacteristic ORT_API_CALL required(const OrtCustomOp *op, size_t index) {
    (void)op; (void)index; return INPUT_OUTPUT_REQUIRED;
}
static OrtMemType ORT_API_CALL memory(const OrtCustomOp *op, size_t index) {
    (void)op; (void)index; return OrtMemTypeDefault;
}
static void ORT_API_CALL drop(void *binding) { (void)binding; }
#define OP(name, eval, in, out) { .version=23, .GetName=name, .GetExecutionProviderType=cpu,     .GetInputType=tensor_type, .GetInputTypeCount=in, .GetOutputType=tensor_type, .GetOutputTypeCount=out,     .GetInputCharacteristic=required, .GetOutputCharacteristic=required, .GetInputMemoryType=memory,     .KernelDestroy=drop, .CreateKernelV2=create, .KernelComputeV2=eval,     .GetStartVersion=version, .GetEndVersion=version }
static const OrtCustomOp pack_op = OP(pack_name, pack, one, two);
static const OrtCustomOp expand_op = OP(expand_name, expand, two, one);

typedef struct {
    int source, length, repeated;
} ColumnSpan;

static void copy_columns(uint32_t *restrict out, const uint32_t *restrict in, int n) {
    for (int x = 0; x < n; ++x) out[x] = in[x];
}
static void fill_columns(uint32_t *out, uint32_t value, int n) {
    for (int x = 0; x < n; ++x) out[x] = value;
}

static OrtStatusPtr pack_columns_with_radius(void *binding, OrtKernelContext *context, int radius, int minimum) {
    const OrtApi *api = binding;
    const OrtValue *input;
    CHECK(api->KernelContext_GetInput(context, 0, &input));
    int64_t dims[4];
    CHECK(shape(api, input, 4, dims));
    if (dims[0] != 1 || dims[1] < 1 || dims[1] > 4096 || dims[2] < 1 ||
        dims[2] > 4096 || dims[3] < 1 || dims[3] > 4096 ||
        dims[1] * dims[2] * dims[3] > (1LL << 26))
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid depthwise input shape");
    const int w = (int)dims[3];
    const int rows = (int)(dims[1] * dims[2]);
    void *raw;
    CHECK(api->GetTensorMutableData((OrtValue *)input, &raw));
    const uint32_t *src = raw;
    uint32_t difference[4096];
    for (int i = 1; i < w; ++i) difference[i] = src[i] ^ src[i - 1];
    const int head = rows < 4 ? rows : 4;
    for (int row = 1; row < head; ++row) {
        const uint32_t *values = src + row * w;
        for (int i = 1; i < w; ++i) difference[i] |= values[i] ^ values[i - 1];
    }
    for (int start = 1; start < w;) {
        if (difference[start]) {
            ++start;
            continue;
        }
        int end = start + 1;
        while (end < w && !difference[end]) ++end;
        if (end - start < 8) {
            for (int x = start; x < end; ++x) {
                for (int row = head; row < rows; ++row) {
                    const uint32_t *values = src + row * w;
                    if (values[x] != values[x - 1]) {
                        difference[x] = 1;
                        break;
                    }
                }
            }
        } else {
            for (int row = head; row < rows; ++row) {
                const uint32_t *values = src + row * w;
                for (int x = start; x < end; ++x)
                    difference[x] |= values[x] ^ values[x - 1];
            }
        }
        start = end;
    }
    int keep[4096];
    int map[4096];
    int remaining = w > minimum ? w - minimum : 0;
    int size = 0;
    for (int start = 0; start < w;) {
        int end = start + 1;
        while (end < w && !difference[end]) ++end;
        int run = end - start;
        int removed = run - (radius * 2 + 1);
        if (removed > remaining) removed = remaining;
        if (removed <= 0) {
            for (int x = start; x < end; ++x) {
                map[x] = size;
                keep[size++] = x;
            }
        } else {
            // Preserve both boundary footprints and at least one full interior footprint.
            int retain = run - removed;
            remaining -= removed;
            for (int x = 0; x < retain; ++x) keep[size + x] = start + x;
            for (int x = 0; x < radius; ++x) map[start + x] = size + x;
            for (int x = start + radius; x < end - radius; ++x) map[x] = size + radius;
            for (int x = 0; x < radius; ++x) map[end - radius + x] = size + retain - radius + x;
            size += retain;
        }
        start = end;
    }
    dims[3] = size;
    int64_t map_shape[] = {w};
    OrtValue *packed, *mapping;
    CHECK(api->KernelContext_GetOutput(context, 0, dims, 4, &packed));
    CHECK(api->KernelContext_GetOutput(context, 1, map_shape, 1, &mapping));
    void *packed_raw, *map_raw;
    CHECK(api->GetTensorMutableData(packed, &packed_raw));
    CHECK(api->GetTensorMutableData(mapping, &map_raw));
    int32_t *mapping_out = map_raw;
    for (int x = 0; x < w; ++x) mapping_out[x] = map[x];
    ColumnSpan spans[4096];
    int count = 0;
    for (int start = 0; start < size;) {
        int end = start + 1;
        while (end < size && keep[end] - keep[start] == end - start) ++end;
        spans[count++] = (ColumnSpan){keep[start], end - start, 0};
        start = end;
    }
    uint32_t *dst = packed_raw;
    for (int row = 0; row < rows; ++row) {
        const uint32_t *values = src + row * w;
        uint32_t *packed_row = dst + row * size;
        for (int i = 0; i < count; ++i) {
            copy_columns(packed_row, values + spans[i].source, spans[i].length);
            packed_row += spans[i].length;
        }
    }
    return NULL;
}

static OrtStatusPtr ORT_API_CALL pack_chain(void *binding, OrtKernelContext *context) {
    return pack_columns_with_radius(binding, context, 10, 48);
}

static OrtStatusPtr ORT_API_CALL expand_columns(void *binding, OrtKernelContext *context) {
    const OrtApi *api = binding;
    const OrtValue *input, *mapping;
    CHECK(api->KernelContext_GetInput(context, 0, &input));
    CHECK(api->KernelContext_GetInput(context, 1, &mapping));
    int64_t dims[4], map_shape[1];
    CHECK(shape(api, input, 4, dims));
    CHECK(shape(api, mapping, 1, map_shape));
    if (dims[0] != 1 || dims[1] < 1 || dims[1] > 4096 || dims[2] < 1 ||
        dims[2] > 4096 || dims[3] < 1 || dims[3] > 4096 ||
        map_shape[0] < dims[3] || map_shape[0] > 4096 ||
        dims[1] * dims[2] * map_shape[0] > (1LL << 26))
        return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid depthwise output shape");
    const int compact = (int)dims[3];
    const int width = (int)map_shape[0];
    const int rows = (int)(dims[1] * dims[2]);
    void *raw, *map_raw;
    CHECK(api->GetTensorMutableData((OrtValue *)input, &raw));
    CHECK(api->GetTensorMutableData((OrtValue *)mapping, &map_raw));
    const int32_t *map = map_raw;
    for (int x = 0; x < width; ++x)
        if (map[x] < 0 || map[x] >= compact)
            return api->CreateStatus(ORT_INVALID_ARGUMENT, "Invalid depthwise column map");
    dims[3] = width;
    OrtValue *output;
    CHECK(api->KernelContext_GetOutput(context, 0, dims, 4, &output));
    void *out_raw;
    CHECK(api->GetTensorMutableData(output, &out_raw));
    ColumnSpan spans[4096];
    int count = 0;
    for (int start = 0; start < width;) {
        int end = start + 1;
        int repeat = end < width && map[end] == map[start];
        while (end < width && map[end] - map[start] == (repeat ? 0 : end - start)) ++end;
        spans[count++] = (ColumnSpan){map[start], end - start, repeat};
        start = end;
    }
    const uint32_t *src = raw;
    uint32_t *dst = out_raw;
    for (int row = 0; row < rows; ++row) {
        const uint32_t *compact_row = src + row * compact;
        uint32_t *out = dst + row * width;
        for (int i = 0; i < count; ++i) {
            const ColumnSpan *span = spans + i;
            if (span->repeated) fill_columns(out, compact_row[span->source], span->length);
            else copy_columns(out, compact_row + span->source, span->length);
            out += span->length;
        }
    }
    return NULL;
}

static const char *ORT_API_CALL expand_columns_name(const OrtCustomOp *op) {
    (void)op; return "ExpandDepthwiseColumns";
}
static const char *ORT_API_CALL pack_chain_name(const OrtCustomOp *op) {
    (void)op; return "PackDepthwiseChain";
}
static const OrtCustomOp pack_chain_op = OP(pack_chain_name, pack_chain, one, two);
static const OrtCustomOp expand_columns_op = OP(expand_columns_name, expand_columns, two, one);

/* The Java binding pins this library for the process. ORT borrows the
 * domain, so reuse it across sessions and retain it with the operation code. */
__attribute__((visibility("default")))
OrtStatusPtr ORT_API_CALL RegisterCustomOps(OrtSessionOptions *options, const OrtApiBase *base) {
    const OrtApi *api = base->GetApi(23);
    if (!api) return base->GetApi(1)->CreateStatus(ORT_FAIL, "Custom OCR operations require ORT API 23");
    static _Atomic(OrtCustomOpDomain *) shared_domain;
    OrtCustomOpDomain *domain = atomic_load(&shared_domain);
    if (!domain) {
        OrtCustomOpDomain *created;
        CHECK(api->CreateCustomOpDomain("com.luckolite.spatialprobe", &created));
        OrtStatusPtr status = api->CustomOpDomain_Add(created, &pack_op);
        if (!status) status = api->CustomOpDomain_Add(created, &expand_op);
        if (!status) status = api->CustomOpDomain_Add(created, &expand_columns_op);
        if (!status) status = api->CustomOpDomain_Add(created, &pack_chain_op);
        if (status) {
            api->ReleaseCustomOpDomain(created);
            return status;
        }
        if (atomic_compare_exchange_strong(&shared_domain, &domain, created)) domain = created;
        else api->ReleaseCustomOpDomain(created);
    }
    return api->AddCustomOpDomain(options, domain);
}
