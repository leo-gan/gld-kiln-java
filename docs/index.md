# gld-kiln-java

gld-kiln-java encodes and decodes Kiln wire v2 in Java.
Structs are positional, tensor payloads stay byte arrays, and a hostile count
is rejected before the decoder allocates storage for it.

The library is from scratch. It does not wrap another Kiln implementation, and
it is not the specification.

- [Why Kiln](why-kiln.md) explains what this Java library is for.
- [Instructions](instructions.md) covers install, encode, decode, and the generator.
- [Examples](examples.md) shows a struct, a frame, and a tensor directory.
- [Techniques](techniques.md) describes buffers, views, and the count check.
- [Test data](test-data.md) lists the cases and the Python oracle.

The generator command is `gld-kilngen-java`.
The import package is `io.github.leogan.kiln`.
