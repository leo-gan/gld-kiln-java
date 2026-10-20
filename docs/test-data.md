# Test data

`testdata/` is the input to the suite. `bash scripts/run-tests.sh` reads it.
Nothing in this tree is copied from another project's private corpus.

## Cases

`testdata/kiln-v2-cases.json` holds 46 records. Each record has `id`, `expect`,
`input` (hex), and `schema`. `expect: accept` must decode, match `decoded`
when that object is present, and re-encode to the same bytes. `expect: reject`
must throw `KilnException` whose `code()` is the record's `error`.

The cases cover an empty directory, scalar and vector payloads, names, packed
dtypes, quantization schemes 0–2, opaque layouts, a directory embedded in a
struct, a frame, a list of `f32`, and the reject paths `FRAME`, `LIMIT`,
`EOF`, `TYPE`, `UTF8`, and `TRAILING`. The list rejects include a count of
16,777,216 structs that does not fit in the remaining buffer. That path
returns `EOF` without allocating the list.

## Oracle

`testdata/reference/kiln_ref.py` is the Frozen encoder used as a test oracle.
The Java tests run it for Point, Message, lists, optional values, dicts, and
framed values, including `f32` negative zero and an `f64` quiet NaN payload.
The module is not on the runtime classpath.

## Schemas

`testdata/schema/point.json`, `message.json`, and `sample.json` are the
generator inputs. `message.json` references `#/$defs/Meta`. `sample.json`
covers bytes, a byte list, a string list, a dict, and an optional string.
`scripts/generate.sh` writes `tests/generated`. `scripts/check-generated.sh`
regenerates those files and diffs them.
