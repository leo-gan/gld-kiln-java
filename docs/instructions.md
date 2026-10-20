# Instructions

Requires Java 21. The Gradle dependency is `io.github.leo-gan:gld-kiln-java`.
Import `io.github.leogan.kiln.Kiln` and `io.github.leogan.kiln.Schema`.

```kotlin
dependencies {
    implementation("io.github.leo-gan:gld-kiln-java:0.1.0")
}
```

From a checkout, `bash scripts/run-tests.sh` runs the suite. The script
downloads a Temurin 21 JDK into `~/.cache/gld-kiln-java/jdk-21` when `java` is
older than 21.

## Dynamic values

Build a schema and pass a `Map` whose keys are the field names. Struct encode
follows the schema, not the map's iteration order.

```java
Schema point = Schema.struct("Point",
        Schema.field("x", Schema.I32),
        Schema.field("y", Schema.I32));
Map<String, Object> value = new LinkedHashMap<>();
value.put("x", 1);
value.put("y", -2);
byte[] raw = Kiln.encode(point, value);
Map<?, ?> back = (Map<?, ?>) Kiln.decode(point, raw);
```

`Kiln.encodeFramed` writes a 16-byte header and the body.
`Kiln.decodeFramed` checks the schema hash and throws `KilnException` with
code `SCHEMA` when the hash does not match.

`Kiln.encodeFixed` and `Kiln.decodeFixed` move a fixed-width struct through a
`long[]` with no per-field objects. Field bits are little-endian on the wire
and raw in the array. `f32` uses the low 32 bits from `Float.floatToRawIntBits`.

## Tensors

```java
byte[] payload = new byte[1024 * 4];
Tensor tensor = Tensor.of("f32", new long[] {1024}, payload);
byte[] block = Kiln.encodeTensors(List.of(tensor));
TensorDirectory decoded = Kiln.decodeTensors(block);
Bytes view = decoded.tensors().get(0).data();
```

`view` aliases `block`. Do not mutate `block` if you still read the view.
A `tensors` field inside a struct is one directory block, then the next field.

## Caps and trusted reads

`Kiln.Options.defaults()` uses cap `16777216` and checks UTF-8, shapes, and
quantization. `options.withCap(n)` raises the cap. `options.withTrusted(true)`
skips UTF-8 checks and quantization or shape checks. Lengths that run past the
buffer are still `EOF` or `LIMIT`.

```java
Kiln.decode(schema, raw, Kiln.Options.defaults().withCap(1_000_000).withTrusted(true));
```

## Generator

```bash
bash scripts/generate.sh
# or, after ./gradlew installDist:
build/install/gld-kilngen-java/bin/gld-kilngen-java \
    --schema testdata/schema/point.json \
    --out tests/generated \
    --package io.github.leogan.kiln.generated
```

The JSON Schema subset accepts booleans, integer formats `int8` through
`int64` and `uint8` through `uint64`, numbers (`float`, `double`, or plain
`number` for `f64`), strings, `format: binary`, arrays, objects, `$ref` of
`#/$defs/Name`, `required`, and `additionalProperties` as `dict<str,V>`.
Properties that are not required are optional. `bash scripts/check-generated.sh`
fails when `tests/generated` drifts.

## Errors

`KilnException.code()` is one of `FRAME`, `LIMIT`, `EOF`, `TYPE`, `UTF8`,
`TRAILING`, `SCHEMA`. `FRAME` is a bad directory header or frame magic.
`LIMIT` is a cap or a 64-bit overflow. `EOF` is a short buffer, including a
count that does not fit. `TYPE` is a value that does not match the schema or
the canonical directory. `UTF8` is ill-formed text. `TRAILING` is an extra
byte after a top-level value. `SCHEMA` is a frame hash mismatch.
