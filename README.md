# gld-kiln-java

A from-scratch Kiln wire v2 library for Java.
The runtime and the code generator are Java. They do not wrap, link, or vendor
another language's Kiln implementation.

Python `testdata/reference/kiln_ref.py` is a **test oracle** only. It is not
required to encode or decode at runtime.

This repository is a standalone library. It is not the Kiln specification.

Documentation: [Why Kiln](https://leo-gan.github.io/gld-kiln-java/why-kiln/),
[Instructions](https://leo-gan.github.io/gld-kiln-java/instructions/),
[Examples](https://leo-gan.github.io/gld-kiln-java/examples/),
[Techniques](https://leo-gan.github.io/gld-kiln-java/techniques/),
[Test data](https://leo-gan.github.io/gld-kiln-java/test-data/).

## Install

Java 21. Gradle coordinates `io.github.leo-gan:gld-kiln-java`. The import
package is `io.github.leogan.kiln`. The generator command is `gld-kilngen-java`.

```kotlin
dependencies {
    implementation("io.github.leo-gan:gld-kiln-java:0.2.0")
}
```

From a git checkout:

```bash
git clone https://github.com/leo-gan/gld-kiln-java.git
cd gld-kiln-java
bash scripts/run-tests.sh
bash scripts/generate.sh
```

`gld-kilngen-java --schema testdata/schema/point.json --out tests/generated`
writes a `Point` class with `encodedSize`, `encode`, and `decode`.

## What it reads and writes

| Surface | Behavior |
| --- | --- |
| Frozen values | Positional little-endian structs, scalars, strings, bytes, lists, optional values, and dicts. |
| Tensor directory | Version 2 block. Payload bytes are a view of the input buffer. |
| Frame | Optional 16-byte `KLN0` header. The FNV-1a 64 schema hash is checked. A mismatch is `SCHEMA`. |
| JSON Schema subset | Objects, arrays, scalars, `$ref`, required and optional properties, and `additionalProperties` as `dict<str,V>`. |
| Generator | `gld-kilngen-java` emits Java structs. A `tensors` field is not produced from that subset. |

The default cap is 16,777,216 counts and 16 MiB for one string or byte run.
A hostile list or dict count fails before the decoder allocates element storage.

## Layout

`src/main/java` is the library and `gld-kilngen-java`.
`testdata/` holds the v2 cases, the Python oracle, and the schema files.
`tests/generated/` is regenerated output checked by `scripts/check-generated.sh`.

Requires **Java 21**.

Copyright (c) 2026 Leonid Ganeline. MIT.
