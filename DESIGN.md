# gld-kiln-java design

gld-kiln-java is a from-scratch Kiln wire v2 library for Java 21.
The runtime never calls a Kiln library written in another language. The Python
module under `testdata/reference/` may be used as a test oracle. It is not a
build dependency and it is not used at runtime.

This repository implements the wire. It is not the specification.

## Goals

Callers encode and decode positional values and version-2 tensor directories.
The struct declaration is the schema: fields are written in order, with no
names, tags, or padding on the wire. A tensor directory keeps numeric payload
bytes as an array. Decode returns a view of those bytes in the input buffer
instead of copying them.

A count is checked against the cap and against `count * minSize(element)`
before any element storage is allocated. A short or hostile list fails with
`EOF` or `LIMIT` and does not allocate the list.

`gld-kilngen-java` reads the JSON Schema subset and emits Java classes with
`encodedSize`, `encode`, and `decode`. Those classes call the same little-endian
primitives as the dynamic codec. The subset has no tensor type, so the
generator does not emit a tensor field.

## Frozen values

Scalars are little-endian. `bool` is one byte, 0 or 1. Strings and byte runs
are a `u32` length plus bytes. Strings must be UTF-8 unless the caller selects
a trusted reader. `list<u8>` and `list<i8>` are a count plus raw bytes. Other
lists are a count plus concatenated elements. `opt` is a 0/1 tag. Dict keys
are sorted by raw UTF-8 bytes. Structs are fields in declaration order.

The optional frame is the ASCII bytes `KLN0`, four zero bytes, and the
little-endian FNV-1a 64 hash of the schema. The hash mixes each token and then
one trailing `0x00` multiply, matching the reference. A tensor field mixes the
ASCII token `tensors`. A hash mismatch is `SCHEMA`.

## Tensor directory

A directory is a 64-byte header (`KLNT`, version 2, flags, counts), then
128-byte descriptors, a name heap, zero padding to a 64-byte boundary, then
payloads. Payloads sit on the canonical 64-byte grid. Dtypes 1–19, opaque
codes 4096–32767, and quantization schemes 0–2 use the check order in the
normative spec. Error names are `FRAME`, `LIMIT`, `EOF`, `TYPE`, `UTF8`,
`TRAILING`, and `SCHEMA`.

Inside a struct, a `tensors` field is one directory block. The following field
starts at the end of that block. A standalone directory rejects trailing bytes.

A trusted reader may skip UTF-8 checks and quantization or shape checks. It
still rejects a length that runs past the buffer.

## Allocation

`Wire.In.count` reads the count, rejects a count above the cap, and rejects a
count whose minimum encoded size does not fit in the unread bytes. Only then
does the list or dict allocate. Tensor counts are rejected the same way, before
a descriptor array is allocated for a count that does not fit.

Encode writes into one buffer. Numeric tensor payloads are copied with
`System.arraycopy`. Fixed-width structs can be encoded from a `long[]` through
`Kiln.encodeFixed` with no per-field objects.

## Generator

`scripts/generate.sh` builds `gld-kilngen-java` and writes one Java file per
struct into `tests/generated`. `scripts/check-generated.sh` regenerates into a
temporary directory and diffs it. Generated decode uses the same count check
as the dynamic codec.
