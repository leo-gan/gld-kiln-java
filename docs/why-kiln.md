# Why Kiln

Java services already move tensors and fixed records between processes.
A schema that is the struct itself keeps that path short: the field order is
the wire order, and there is nothing to look up by name while encoding.

## The struct is the schema

A generated class is the schema. `encodedSize`, `encode`, and `decode` walk
fields in declaration order. Optional properties are a single presence byte.
Dict keys are ordered by their UTF-8 bytes, so two encodes of the same map
match. The dynamic API uses the same rules when the value is a `Map` or a
`long[]` of fixed-width fields.

`gld-kilngen-java --schema point.json --out src/main/java` emits those classes
from a JSON Schema document. The subset does not describe a tensor directory,
so the generator does not invent a `tensors` field. A tensor block is an
explicit directory value on the dynamic API.

## Bytes stay bytes

A tensor directory describes dtype, shape, and quantization, then points at a
payload. Decode does not widen `f32` or `f16` into a `float[]`. The payload is
a slice of the input buffer. Numeric bytes are copied with one array copy on
encode, not once per element. Negative zero and a quiet NaN payload stay the
bits that arrived.

## Hostile counts

A list count is a `u32` that a peer can set to anything. Before the decoder
allocates element storage it requires the count to be within the cap and
`count * minSize(element)` to fit in the unread bytes. The same rule covers
dicts and the tensor count in a directory header. The failure is `LIMIT` or
`EOF`, and the element array does not exist yet.

The default cap is 16,777,216. A caller can raise it. Raising it does not skip
the fit check.

## Wire

The normative wire, including scalar layout, the frame hash, and the tensor
directory, is specified only at:

https://leo-gan.github.io/gld-kiln/spec/

This library is a Java implementation of that document. It is not the
specification, and it does not restate the wire tables.
