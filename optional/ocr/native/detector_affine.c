/* Copyright 2026 Luckolite. Licensed under the Apache License, Version 2.0. */
#include "onnxruntime_c_api.h"
#include <stdatomic.h>

#if defined(_WIN32)
#define EXPORT __declspec(dllexport)
#pragma fp_contract(off)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

enum { api_version = 23, parallel_grain = 16384 };

typedef struct {
  const float* source;
  float* output;
  size_t length;
  float factor;
  float offset;
} Affine;

static void affine_block(void* data, size_t block) {
  const Affine* work = (const Affine*)data;
  const size_t start = block * parallel_grain;
  const size_t remaining = work->length - start;
  const size_t end = start + (remaining < parallel_grain ? remaining : parallel_grain);
  for (size_t i = start; i < end; ++i) {
    const float product = work->source[i] * work->factor;
    work->output[i] = product + work->offset;
  }
}

static void activated_affine_block(void* data, size_t block) {
  const Affine* work = (const Affine*)data;
  const size_t start = block * parallel_grain;
  const size_t remaining = work->length - start;
  const size_t end = start + (remaining < parallel_grain ? remaining : parallel_grain);
  for (size_t i = start; i < end; ++i) {
    const float input = work->source[i];
    const float gate = input * (1.0f / 6.0f) + 0.5f;
    const float bounded = gate < 0.0f ? 0.0f : gate > 1.0f ? 1.0f : gate;
    const float activated = input * bounded;
    work->output[i] = activated * work->factor + work->offset;
  }
}

#define CHECK(expr) do { OrtStatusPtr status = (expr); if (status != NULL) return status; } while (0)

static OrtStatusPtr tensor_data(const OrtApi* api, const OrtValue* value, const float** data) {
  void* address = NULL;
  CHECK(api->GetTensorMutableData((OrtValue*)value, &address));
  *data = (const float*)address;
  return NULL;
}

static OrtStatusPtr tensor_shape(const OrtApi* api, const OrtValue* value,
                                int64_t* dimensions, size_t capacity,
                                size_t* rank, size_t* count) {
  OrtTensorTypeAndShapeInfo* info = NULL;
  CHECK(api->GetTensorTypeAndShape(value, &info));
  OrtStatusPtr status = api->GetDimensionsCount(info, rank);
  if (!status && (*rank == 0 || *rank > capacity))
    status = api->CreateStatus(ORT_INVALID_ARGUMENT, "Unexpected affine tensor rank");
  if (!status) status = api->GetDimensions(info, dimensions, *rank);
  if (!status) status = api->GetTensorShapeElementCount(info, count);
  api->ReleaseTensorTypeAndShapeInfo(info);
  return status;
}

static OrtStatusPtr compute_affine(void* kernel, OrtKernelContext* context,
                                    void (*block)(void*, size_t)) {
  const OrtApi* api = (const OrtApi*)kernel;
  const OrtValue *input = NULL, *factor = NULL, *offset = NULL;
  CHECK(api->KernelContext_GetInput(context, 0, &input));
  CHECK(api->KernelContext_GetInput(context, 1, &factor));
  CHECK(api->KernelContext_GetInput(context, 2, &offset));

  int64_t dimensions[8], scalar_dimensions[1];
  size_t rank, length, scalar_rank, scalar_count;
  CHECK(tensor_shape(api, input, dimensions, 8, &rank, &length));
  CHECK(tensor_shape(api, factor, scalar_dimensions, 1, &scalar_rank, &scalar_count));
  if (scalar_count != 1) return api->CreateStatus(ORT_INVALID_ARGUMENT, "Expected affine factor");
  CHECK(tensor_shape(api, offset, scalar_dimensions, 1, &scalar_rank, &scalar_count));
  if (scalar_count != 1) return api->CreateStatus(ORT_INVALID_ARGUMENT, "Expected affine offset");

  OrtValue* output = NULL;
  CHECK(api->KernelContext_GetOutput(context, 0, dimensions, rank, &output));
  if (length == 0) return NULL;

  void* destination = NULL;
  const float *x = NULL, *alpha = NULL, *beta = NULL;
  CHECK(tensor_data(api, input, &x));
  CHECK(tensor_data(api, factor, &alpha));
  CHECK(tensor_data(api, offset, &beta));
  CHECK(api->GetTensorMutableData(output, &destination));
  Affine work = {x, (float*)destination, length, *alpha, *beta};
  const size_t blocks = (length - 1) / parallel_grain + 1;
  if (blocks == 1) {
    block(&work, 0);
    return NULL;
  }
  return api->KernelContext_ParallelFor(context, block, blocks, 0, &work);
}

static OrtStatusPtr ORT_API_CALL compute(void* kernel, OrtKernelContext* context) {
  return compute_affine(kernel, context, affine_block);
}

static OrtStatusPtr ORT_API_CALL compute_activated(void* kernel, OrtKernelContext* context) {
  return compute_affine(kernel, context, activated_affine_block);
}

static const char* ORT_API_CALL name_activated(const OrtCustomOp* op) {
  (void)op;
  return "ExactActivatedAffine";
}

static OrtStatusPtr ORT_API_CALL create(const OrtCustomOp* op, const OrtApi* api,
                                        const OrtKernelInfo* info, void** kernel) {
  (void)op;
  (void)info;
  *kernel = (void*)api;
  return NULL;
}

static const char* ORT_API_CALL name(const OrtCustomOp* op) {
  (void)op;
  return "ExactAffine";
}

static const char* ORT_API_CALL cpu_provider(const OrtCustomOp* op) {
  (void)op;
  return NULL;
}

static OrtMemType ORT_API_CALL input_memory(const OrtCustomOp* op, size_t index) {
  (void)op;
  (void)index;
  return OrtMemTypeDefault;
}

static size_t ORT_API_CALL input_count(const OrtCustomOp* op) {
  (void)op;
  return 3;
}

static size_t ORT_API_CALL output_count(const OrtCustomOp* op) {
  (void)op;
  return 1;
}

static ONNXTensorElementDataType ORT_API_CALL tensor_type(const OrtCustomOp* op, size_t index) {
  (void)op;
  (void)index;
  return ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
}

static void ORT_API_CALL destroy(void* kernel) {
  (void)kernel;
}

static OrtCustomOpInputOutputCharacteristic ORT_API_CALL required(const OrtCustomOp* op,
                                                                 size_t index) {
  (void)op;
  (void)index;
  return INPUT_OUTPUT_REQUIRED;
}

static int ORT_API_CALL version(const OrtCustomOp* op) {
  (void)op;
  return 1;
}

static const OrtCustomOp affine_op = {
  .version = api_version,
  .GetName = name,
  .GetExecutionProviderType = cpu_provider,
  .GetInputType = tensor_type,
  .GetInputTypeCount = input_count,
  .GetOutputType = tensor_type,
  .GetOutputTypeCount = output_count,
  .KernelDestroy = destroy,
  .GetInputCharacteristic = required,
  .GetOutputCharacteristic = required,
  .GetInputMemoryType = input_memory,
  .CreateKernelV2 = create,
  .KernelComputeV2 = compute,
  .GetStartVersion = version,
  .GetEndVersion = version,
};

static const OrtCustomOp activated_op = {
  .version = api_version,
  .GetName = name_activated,
  .GetExecutionProviderType = cpu_provider,
  .GetInputType = tensor_type,
  .GetInputTypeCount = input_count,
  .GetOutputType = tensor_type,
  .GetOutputTypeCount = output_count,
  .KernelDestroy = destroy,
  .GetInputCharacteristic = required,
  .GetOutputCharacteristic = required,
  .GetInputMemoryType = input_memory,
  .CreateKernelV2 = create,
  .KernelComputeV2 = compute_activated,
  .GetStartVersion = version,
  .GetEndVersion = version,
};

EXPORT OrtStatusPtr ORT_API_CALL RegisterDetectorAffineOps(OrtSessionOptions* options,
                                                    const OrtApiBase* api_base) {
  const OrtApi* api = api_base->GetApi(api_version);
  if (!api) return api_base->GetApi(1)->CreateStatus(
      ORT_FAIL, "Fused detector requires ORT API 23");
  // One process-owned domain, also when sessions open concurrently.
  static _Atomic(OrtCustomOpDomain*) shared_domain;
  OrtCustomOpDomain* domain = atomic_load(&shared_domain);
  if (!domain) {
    OrtCustomOpDomain* candidate = NULL;
    CHECK(api->CreateCustomOpDomain("com.luckolite.reader", &candidate));
    OrtStatusPtr status = api->CustomOpDomain_Add(candidate, &affine_op);
    if (!status) status = api->CustomOpDomain_Add(candidate, &activated_op);
    if (status) {
      api->ReleaseCustomOpDomain(candidate);
      return status;
    }
    if (atomic_compare_exchange_strong(&shared_domain, &domain, candidate)) domain = candidate;
    else api->ReleaseCustomOpDomain(candidate);
  }
  return api->AddCustomOpDomain(options, domain);
}
