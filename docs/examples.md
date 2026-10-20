# Examples

The snippets use `io.github.leogan.kiln`. `examples/example/Example.java` is the
same struct and tensor path, compiled with the library.

## Point

```java
Schema point = Schema.struct("Point",
        Schema.field("x", Schema.I32),
        Schema.field("y", Schema.I32),
        Schema.field("label", Schema.opt(Schema.STR)));
Map<String, Object> value = new LinkedHashMap<>();
value.put("x", 3);
value.put("y", -4);
byte[] raw = Kiln.encode(point, value);
```

A missing `label` is the optional tag `0`. A present label is `1`, then a
`u32` length, then UTF-8.

Generated code for `testdata/schema/point.json` is a `Point` class. Set the
fields, call `encode`, and pass the bytes to `Point.decode`. `encodedSize`
matches `encode().length`.

## Frame

```java
byte[] framed = Kiln.encodeFramed(point, value);
Kiln.Framed decoded = Kiln.decodeFramed(point, framed);
long hash = decoded.hash();
```

`hash` is the FNV-1a 64 value of `point`. Flipping a hash byte and decoding
again throws `SCHEMA`.

## One f32 tensor

```java
byte[] payload = new byte[] {
        0x00, 0x00, (byte) 0x80, 0x3f,
        0x00, 0x00, 0x00, (byte) 0xc0
};
Tensor tensor = Tensor.of("f32", new long[] {2}, payload);
byte[] block = Kiln.encodeTensors(List.of(tensor));
TensorDirectory directory = Kiln.decodeTensors(block);
Bytes data = directory.tensors().get(0).data();
```

`data.length()` is 8. The bytes are the little-endian values `1.0` and `-2.0`
inside `block`, not a converted `float[]`. A second tensor starts on the next
64-byte payload boundary.

## Batch of fixed structs

```java
Schema point = Schema.struct("Point",
        Schema.field("x", Schema.I32),
        Schema.field("y", Schema.I32));
byte[] dest = new byte[8 * 1000];
long[] fields = new long[2];
for (int i = 0; i < 1000; i++) {
    fields[0] = i;
    fields[1] = -i;
    Kiln.encodeFixed(point, fields, dest, i * 8);
}
```

`scripts/run-microbench.sh` times this pattern and one 1024-element `f32`
tensor, encode and decode.
