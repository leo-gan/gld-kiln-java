# Techniques

## One buffer

Encode measures the value, allocates one `byte[]`, and writes into it.
Fields are not encoded into temporary arrays and then concatenated. A caller
who already has a buffer uses `Kiln.encodeTo` or `Kiln.encodeTensorsTo`.
`Kiln.encodeFixed` only writes the fixed width of the struct.

Little-endian stores are explicit shifts. `f32` and `f64` use
`floatToRawIntBits` and `doubleToRawLongBits`, so a sign bit on zero and a
NaN payload are preserved.

## Tensor payloads

On encode, each payload is `System.arraycopy` into the canonical offset.
Padding between the name heap and the payload, and the alignment gaps between
payloads, are zeros. On decode, `Bytes` holds `array`, `offset`, and `length`
into the input. Reading `Bytes.get` does not allocate. Call `toArray` only
when a copy is required.

Descriptors are parsed in the order the spec requires. The name heap is
compared with the concatenation of name bytes, then layout bytes. Quantization
is checked after payload placement. A standalone directory then rejects a
trailing byte.

## Count before storage

`Wire.In.count(minSize)` is the gate for lists and dicts:

1. Read the `u32` count.
2. If the count is above the cap, throw `LIMIT`.
3. If `count * minSize` does not fit in the unread bytes, throw `EOF`.
4. Return the count. The caller allocates only after that return.

`minSize` of a struct is the sum of its fields. `minSize` of `opt` is 1.
`minSize` of a string or a nested list is 4. `minSize` of a tensor directory
is the 64-byte header. A directory whose declared byte length does not fit is
`EOF` or `LIMIT` before the descriptor objects are allocated.

Generated list and dict decode calls `in.count` and only then constructs the
`ArrayList` or `LinkedHashMap`.

## Trusted mode

Trusted mode skips UTF-8 validation and the quantization and shape checks.
Header bounds, directory fit, and payload ranges that would read past the
buffer are still enforced. Use it only on bytes this library, or another
trusted writer, just produced.

## Schema hash

The frame hash starts at the FNV-1a 64 offset and mixes each ASCII token.
After the token bytes, the mixer multiplies by the FNV prime once more. That
extra multiply is the trailing `0x00` mix: xor with zero would not change the
hash, and the multiply still does. Struct field names are mixed as UTF-8.
The struct's Java class name is not part of the hash.

## Generator

`JavaGen` walks structs inside-out, so a nested `$ref` is a class that exists
before the parent that names it. Optional scalars use boxed fields. `list<u8>`
and `list<i8>` are `byte[]` so the body stays one copy. Other lists are
`List`. `additionalProperties` is `Map<String, V>`, sorted on encode.
`scripts/check-generated.sh` diffs a fresh emit against `tests/generated`.
