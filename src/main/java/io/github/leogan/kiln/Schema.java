package io.github.leogan.kiln;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Positional Frozen schema. The struct name is not part of the frame hash; field names are.
 * A tensor field hashes as the ASCII token {@code tensors}.
 */
public final class Schema {
    public enum Kind {
        BOOL,
        I8,
        I16,
        I32,
        I64,
        INT,
        U8,
        U16,
        U32,
        U64,
        UINT,
        F32,
        F64,
        STR,
        BYTES,
        LIST,
        OPT,
        DICT,
        STRUCT,
        TENSORS
    }

    public static final Schema BOOL = scalar(Kind.BOOL, "bool", 1);
    public static final Schema I8 = scalar(Kind.I8, "i8", 1);
    public static final Schema I16 = scalar(Kind.I16, "i16", 2);
    public static final Schema I32 = scalar(Kind.I32, "i32", 4);
    public static final Schema I64 = scalar(Kind.I64, "i64", 8);
    public static final Schema INT = scalar(Kind.INT, "int", 8);
    public static final Schema U8 = scalar(Kind.U8, "u8", 1);
    public static final Schema U16 = scalar(Kind.U16, "u16", 2);
    public static final Schema U32 = scalar(Kind.U32, "u32", 4);
    public static final Schema U64 = scalar(Kind.U64, "u64", 8);
    public static final Schema UINT = scalar(Kind.UINT, "uint", 8);
    public static final Schema F32 = scalar(Kind.F32, "f32", 4);
    public static final Schema F64 = scalar(Kind.F64, "f64", 8);
    public static final Schema STR = scalar(Kind.STR, "str", 4);
    public static final Schema BYTES = scalar(Kind.BYTES, "bytes", 4);
    public static final Schema TENSORS = scalar(Kind.TENSORS, "tensors", 64);

    public final Kind kind;
    public final String token;
    public final String name;
    public final Schema elem;
    private List<Field> fields;
    private int minSize;
    private int fixedWidth;

    private Schema(Kind kind, String token, String name, Schema elem, List<Field> fields, int minSize) {
        this.kind = kind;
        this.token = token;
        this.name = name;
        this.elem = elem;
        this.fields = fields;
        this.minSize = minSize;
        this.fixedWidth = fixedOf(kind, elem, fields, minSize);
    }

    private static int fixedOf(Kind kind, Schema elem, List<Field> fields, int minSize) {
        if (kind == Kind.STRUCT) {
            int n = 0;
            for (Field f : fields) {
                if (f.type.fixedWidth < 0) {
                    return -1;
                }
                n += f.type.fixedWidth;
            }
            return n;
        }
        if (kind == Kind.LIST || kind == Kind.OPT || kind == Kind.DICT || kind == Kind.STR || kind == Kind.BYTES
                || kind == Kind.TENSORS) {
            return -1;
        }
        return minSize;
    }

    private static Schema scalar(Kind kind, String token, int width) {
        return new Schema(kind, token, token, null, List.of(), width);
    }

    public static Schema list(Schema elem) {
        return new Schema(Kind.LIST, "list", "", elem, List.of(), 4);
    }

    public static Schema opt(Schema elem) {
        return new Schema(Kind.OPT, "opt", "", elem, List.of(), 1);
    }

    public static Schema dict(Schema value) {
        return dict("", value);
    }

    public static Schema dict(String name, Schema value) {
        return new Schema(Kind.DICT, "dict", name, value, List.of(), 4);
    }

    public static Schema struct(String name, Field... fields) {
        return struct(name, List.of(fields));
    }

    public static Schema struct(String name, List<Field> fields) {
        ArrayList<Field> copy = new ArrayList<>(fields);
        int min = 0;
        for (Field f : copy) {
            min += f.type.minSize();
        }
        return new Schema(Kind.STRUCT, "struct", name, null, Collections.unmodifiableList(copy), min);
    }

    /** Placeholder so a struct can refer to itself through opt or list. Fields are sealed on fill. */
    public static Schema structShell(String name) {
        return new Schema(Kind.STRUCT, "struct", name, null, List.of(), 0);
    }

    public void seal(List<Field> fields) {
        if (kind != Kind.STRUCT) {
            throw new IllegalStateException("seal");
        }
        ArrayList<Field> copy = new ArrayList<>(fields);
        int min = 0;
        for (Field f : copy) {
            min += f.type.minSize();
        }
        this.fields = Collections.unmodifiableList(copy);
        this.minSize = min;
        this.fixedWidth = fixedOf(kind, elem, this.fields, min);
    }

    public List<Field> fields() {
        return fields;
    }

    public int minSize() {
        return minSize;
    }

    /** Byte width when every field is fixed, otherwise -1. */
    public int fixedWidth() {
        return fixedWidth;
    }

    public long typeHash() {
        return hashMix(Wire.FNV_OFFSET);
    }

    private long hashMix(long h) {
        return switch (kind) {
            case LIST -> {
                h = mix(h, "list<");
                h = elem.hashMix(h);
                yield mix(h, ">");
            }
            case OPT -> {
                h = mix(h, "opt<");
                h = elem.hashMix(h);
                yield mix(h, ">");
            }
            case DICT -> {
                h = mix(h, "dict<str,");
                h = elem.hashMix(h);
                yield mix(h, ">");
            }
            case STRUCT -> {
                h = mix(h, "struct{");
                for (Field f : fields) {
                    h = mix(h, f.name.getBytes(StandardCharsets.UTF_8));
                    h = mix(h, ":");
                    h = f.type.hashMix(h);
                    h = mix(h, ",");
                }
                yield mix(h, "}");
            }
            default -> mix(h, token);
        };
    }

    static long mix(long h, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            h ^= ascii.charAt(i) & 0xFFL;
            h *= Wire.FNV_PRIME;
        }
        h *= Wire.FNV_PRIME;
        return h;
    }

    static long mix(long h, byte[] data) {
        for (byte value : data) {
            h ^= value & 0xFFL;
            h *= Wire.FNV_PRIME;
        }
        h *= Wire.FNV_PRIME;
        return h;
    }

    public static Schema named(String token) {
        return switch (token) {
            case "bool" -> BOOL;
            case "i8" -> I8;
            case "i16" -> I16;
            case "i32" -> I32;
            case "i64" -> I64;
            case "int" -> INT;
            case "u8" -> U8;
            case "u16" -> U16;
            case "u32" -> U32;
            case "u64" -> U64;
            case "uint" -> UINT;
            case "f32" -> F32;
            case "f64" -> F64;
            case "str" -> STR;
            case "bytes" -> BYTES;
            case "tensors" -> TENSORS;
            default -> throw new IllegalArgumentException("unknown type " + token);
        };
    }

    public static final class Field {
        public final String name;
        public final Schema type;

        public Field(String name, Schema type) {
            this.name = name;
            this.type = type;
        }
    }

    public static Field field(String name, Schema type) {
        return new Field(name, type);
    }
}
