# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Exact two-thread convolutions for supported Windows LiteRT model layouts."""
from base64 import b85decode
from ctypes import CDLL, CFUNCTYPE, POINTER, Structure, byref, c_bool, c_char_p, c_int, c_int32, c_size_t, c_uint32, c_void_p
from functools import cache
from hashlib import sha256
from pathlib import Path
import json
from zlib import decompress, error as ZlibError

import ai_edge_litert
import numpy as np

_PATH = Path(__file__).parent
_MODEL = _PATH / "data" / "music_sheets_v4_float16.tflite"
_SHA256 = "92ab7c34c27cb704d95f0cde247b3611d1563fdf1c114b9f9817abb18a7b6a93"
_CUSTOM = 32
# Output tensor IDs in the checksum-pinned model.
_REUSE_TENSOR = 75
_WIDE_TENSORS = frozenset((87, 91, 92))
P, I = c_void_p, c_int
_native = CDLL(str(_PATH / "_score_sparse_avx2.dll"))
_supported_cpu = _native.score_sparse_cpu_supported
_supported_cpu.argtypes = []
_supported_cpu.restype = I
if not _supported_cpu():
    raise ImportError("Sparse inference needs AVX2 and FMA")
_conv = _native.sparse_conv
_conv.argtypes = [P, P, P, I, I, I, I, I, P]
_conv.restype = I
_wide_conv = _native.sparse_conv_wide
_wide_conv.argtypes = _conv.argtypes
_wide_conv.restype = I
_conv_valid = getattr(_native, "sparse_conv_valid", None)
_wide_conv_valid = getattr(_native, "sparse_conv_wide_valid", None)
_reuse_conv = getattr(_native, "sparse_conv_reuse", None)
for _kernel in (_conv_valid, _wide_conv_valid, _reuse_conv):
    if _kernel is not None:
        _kernel.argtypes, _kernel.restype = _conv.argtypes, I



@cache
def _assets():
    source = _MODEL.read_bytes()
    if sha256(source).hexdigest() != _SHA256:
        raise ValueError("Bundled model checksum mismatch")
    try:
        weights, layouts = json.loads(decompress((_PATH / "_score_sparse_layouts.json.z").read_bytes()))
    except ZlibError as error:
        raise ValueError("Invalid sparse model layouts") from error
    return source, weights, layouts


def _constant(source, offset, dtype, shape):
    return np.frombuffer(source, dtype=dtype, count=np.prod(shape), offset=offset).reshape(shape)


@cache
def _kernels():
    source, weights, _ = _assets()
    result = {}
    for tensor, (_, weight_ref, bias_ref, ic, oc, dilation) in weights.items():
        weight = _constant(source, *weight_ref)
        bias = np.ascontiguousarray(_constant(source, *bias_ref), dtype=np.float32)
        lanes = 8 if int(tensor) in _WIDE_TENSORS else 16
        if int(tensor) == _REUSE_TENSOR:
            lanes = 32
        packed = np.ascontiguousarray(
            weight.reshape(oc // lanes, lanes, -1).transpose(0, 2, 1), dtype=np.float32)
        result[int(tensor)] = packed, bias, ic, oc, dilation
    return result

api=CDLL(str(Path(ai_edge_litert.__file__).with_name("pywrap_litert_common.dll")))
V=None
Z=c_size_t
S=c_char_p
Invoke=CFUNCTYPE(I,P,P,P)

def bind(name,result,*args):
    fn=getattr(api,"TfLite"+name)
    fn.argtypes=args
    fn.restype=result
    return fn

model_create=bind("ModelCreate",P,S,Z)
model_delete=bind("ModelDelete",V,P)
options_create=bind("InterpreterOptionsCreate",P)
options_delete=bind("InterpreterOptionsDelete",V,P)
threads=bind("InterpreterOptionsSetNumThreads",V,P,I)
add=bind("InterpreterOptionsAddOperator",V,P,P)
create=bind("InterpreterCreate",P,P,P)
delete=bind("InterpreterDelete",V,P)
allocate=bind("InterpreterAllocateTensors",I,P)
invoke=bind("InterpreterInvoke",I,P)
create_op=bind("OperatorCreate",P,I,S,I,P)
delete_op=bind("OperatorDelete",V,P)
set_invoke=bind("OperatorSetInvokeWithData",I,P,Invoke)
class XnnOptions(Structure):
    _fields_=[
        ("num_threads",c_int32),("runtime_flags",c_uint32),("flags",c_uint32),
        ("weights_cache",P),("handle_variable_ops",c_bool),("weight_cache_file_path",S),
        ("weight_cache_file_descriptor",I),("weight_cache_provider",P),
        ("weight_cache_lock_memory",c_bool),
    ]

delegate_defaults=bind("XNNPackDelegateOptionsDefault",XnnOptions)
delegate_create=bind("XNNPackDelegateCreate",P,POINTER(XnnOptions))
delegate_delete=bind("XNNPackDelegateDelete",V,P)
delegate_add=bind("InterpreterOptionsAddDelegate",V,P,P)
get_input=bind("InterpreterGetInputTensor",P,P,I)
get_output=bind("InterpreterGetOutputTensor",P,P,I)
data=bind("TensorData",P,P)
opaque_input=bind("OpaqueNodeGetInput",P,P,P,I)
opaque_output=bind("OpaqueNodeGetOutput",P,P,P,I)
opaque_data=bind("OpaqueTensorData",P,P)
opaque_bytes=bind("OpaqueTensorByteSize",Z,P)

def check(status):
    if status:
        raise RuntimeError(f"LiteRT C API status {status}")



def _details(rows):
    types = {"int32": np.int32, "float32": np.float32, "int64": np.int64}
    return [{"index": index, "shape": np.array(shape, dtype=np.int32), "dtype": types[dtype]}
            for index, shape, dtype in rows]


def _specialize(original):
    source, _, layouts = _assets()
    skeleton, chunks, filters, inputs, outputs = layouts[sha256(original).hexdigest()]
    streams = memoryview(decompress(b85decode(skeleton))), memoryview(source)
    graph = b"".join(streams[part][first:last] for part, first, last in chunks)
    kernels = _kernels()
    calls = {}
    for name, layer, height, width, *padding in filters:
        packed, bias, ic, oc, dilation = kernels[layer]
        calls[name.encode()] = packed, bias, height, width, ic, oc, dilation, bool(padding and padding[0])
    return graph, calls, _details(inputs), _details(outputs)


class _Session:
    def __init__(self, model, layouts):
        self.handle = self.delegate = None
        self.operators = []
        self.callbacks = []
        self.names = tuple(layouts)
        self.model = model
        file = model_create(model, len(model))
        if not file:
            raise RuntimeError("Unable to read specialized model")
        opts = options_create()
        try:
            if not opts:
                raise RuntimeError("Unable to create LiteRT options")
            threads(opts, 2)
            for name, layout in layouts.items():
                self._install(opts, name, layout)
            settings = delegate_defaults()
            settings.num_threads = 2
            self.delegate = delegate_create(byref(settings))
            if not self.delegate:
                raise RuntimeError("Unable to initialize XNNPACK")
            delegate_add(opts, self.delegate)
            self.handle = create(file, opts)
            if not self.handle:
                raise RuntimeError("Unable to initialize LiteRT")
        except Exception:
            self.close()
            raise
        finally:
            options_delete(opts)
            model_delete(file)

    def _install(self, opts, name, layout):
        packed, bias, height, width, ic, oc, dilation, valid = layout
        halo = 2 * dilation if valid else 0
        in_bytes = (height + halo) * (width + halo) * ic * 4
        out_bytes = height * width * oc * 4
        packed_data, bias_data = packed.ctypes.data, bias.ctypes.data
        lanes = packed.shape[-1]
        if lanes == 32:
            convolve = _reuse_conv
        elif valid:
            convolve = _wide_conv_valid if lanes == 8 else _conv_valid
        else:
            convolve = _wide_conv if lanes == 8 else _conv
        if convolve is None:
            raise RuntimeError("Native DLL does not support the required convolution")

        @Invoke
        def run(_, context, node):
            try:
                left = opaque_input(context, node, 0)
                right = opaque_output(context, node, 0)
                if (not left or not right or opaque_bytes(left) != in_bytes
                        or opaque_bytes(right) != out_bytes):
                    return 1
                src, dst = opaque_data(left), opaque_data(right)
                if not src or not dst:
                    return 1
                return convolve(src, packed_data, bias_data, height, width, ic, oc, dilation, dst)
            except Exception:
                return 1

        op = create_op(_CUSTOM, name, 1, None)
        if not op:
            raise RuntimeError("Unable to register convolution")
        self.operators.append(op)
        self.callbacks.append(run)
        check(set_invoke(op, run))
        add(opts, op)

    def close(self):
        if self.handle:
            delete(self.handle)
            self.handle = None
        if self.delegate:
            delegate_delete(self.delegate)
            self.delegate = None
        for op in self.operators:
            delete_op(op)
        self.operators.clear()
        self.callbacks.clear()

    def __del__(self):
        self.close()

    def tensor(self, fetch, index, detail):
        tensor = fetch(self.handle, index)
        raw = data(tensor) if tensor else None
        if not raw:
            raise RuntimeError("Unallocated LiteRT tensor")
        dtype = np.ctypeslib.as_ctypes_type(detail["dtype"])
        size = int(np.prod(detail["shape"]))
        return np.ctypeslib.as_array((dtype * size).from_address(raw)).reshape(detail["shape"])


class SparseInterpreter:
    # Preserve the existing bundled LiteRT routes in the reader.
    __module__ = "ai_edge_litert.interpreter"

    def __init__(self, stock, original):
        model, layouts, self._inputs, self._outputs = _specialize(original)
        self._stock = stock
        self._original = original
        self._native = _Session(model, layouts)
        self._reference = None
        self._staging = {}
        self._output_ports = {item["index"]: (i, item) for i, item in enumerate(self._outputs)}
        self.fallback_reason = ""

    def _fallback(self, error):
        self.fallback_reason = str(error)
        self._native.close()
        self._native = None
        self._reference = self._stock(model_content=self._original, num_threads=2)
        self._reference.allocate_tensors()

    def allocate_tensors(self):
        if self._native is not None:
            try:
                check(allocate(self._native.handle))
            except (OSError, RuntimeError, ValueError) as error:
                self._fallback(error)
        for item in self._inputs:
            self._staging[item["index"]] = np.zeros(item["shape"], dtype=item["dtype"])

    def get_input_details(self):
        return self._inputs

    def get_output_details(self):
        return self._outputs

    def tensor(self, index):
        def view():
            if index in self._staging:
                return self._staging[index]
            if self._reference is not None:
                return self._reference.tensor(index)()
            n, info = self._output_ports[index]
            return self._native.tensor(get_output, n, info)
        return view

    def set_tensor(self, index, value):
        np.copyto(self._staging[index], value, casting="no")

    def get_tensor(self, index):
        return self.tensor(index)().copy()

    def invoke(self):
        if self._native is not None:
            try:
                for n, item in enumerate(self._inputs):
                    np.copyto(self._native.tensor(get_input, n, item), self._staging[item["index"]])
                check(invoke(self._native.handle))
                return
            except (OSError, RuntimeError, ValueError) as error:
                self._fallback(error)
        for index, values in self._staging.items():
            self._reference.set_tensor(index, values)
        self._reference.invoke()


def factory_for(stock):
    def create_interpreter(*, model_path=None, model_content=None, num_threads=2):
        if num_threads == 2:
            try:
                original = Path(model_path).read_bytes() if model_content is None else model_content
                return SparseInterpreter(stock, original)
            except (ImportError, KeyError, IndexError, OSError, RuntimeError, TypeError, ValueError):
                pass
        return stock(model_path=model_path, model_content=model_content, num_threads=num_threads)

    create_interpreter.__module__ = stock.__module__
    return create_interpreter
