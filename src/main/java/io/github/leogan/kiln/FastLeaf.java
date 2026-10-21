package io.github.leogan.kiln;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct encode and decode for a struct whose fields are scalars or strings.
 * The generic codec stays in place for lists, nested structs, and tensors.
 */
final class FastLeaf {
    private FastLeaf() {}

    static int write(Schema schema, Map<?, ?> map, byte[] dest, int p, int cap) {
        List<Schema.Field> fields = schema.fields();
        for (int i = 0; i < fields.size(); i++) {
            Schema.Field field = fields.get(i);
            Object value = map.get(field.name);
            if (value == null) {
                if (!map.containsKey(field.name)) {
                    throw new IllegalArgumentException("missing " + field.name);
                }
                throw new IllegalArgumentException("null " + field.name);
            }
            p = put(field, value, dest, p, cap);
        }
        return p;
    }

    static Object read(Schema schema, Wire.In in) {
        List<Schema.Field> fields = schema.fields();
        LinkedHashMap<String, Object> map = Pod.structMap(schema, fields.size());
        boolean known = Pod.structSame();
        if (!known) {
            map.clear();
        }
        for (int i = 0; i < fields.size(); i++) {
            readField(map, fields.get(i), in, !known);
        }
        return map;
    }

    private static void readField(LinkedHashMap<String, Object> map, Schema.Field field, Wire.In in, boolean rewrite) {
        if (rewrite) {
            map.put(field.name, readOne(field.type, in));
            return;
        }
        Object prev = map.get(field.name);
        switch (field.type.kind) {
            case BOOL -> {
                boolean v = in.bool();
                if (!(prev instanceof Boolean n) || n.booleanValue() != v) {
                    map.put(field.name, v);
                }
            }
            case I32 -> {
                int v = in.i32();
                if (!(prev instanceof Integer n) || n.intValue() != v) {
                    map.put(field.name, v);
                }
            }
            case I64, INT -> {
                long v = in.i64();
                if (!(prev instanceof Long n) || n.longValue() != v) {
                    map.put(field.name, v);
                }
            }
            case F32 -> {
                float v = in.f32();
                if (!(prev instanceof Float n) || Float.floatToRawIntBits(n) != Float.floatToRawIntBits(v)) {
                    map.put(field.name, v);
                }
            }
            case F64 -> {
                double v = in.f64();
                if (!(prev instanceof Double n) || Double.doubleToRawLongBits(n) != Double.doubleToRawLongBits(v)) {
                    map.put(field.name, v);
                }
            }
            case STR -> {
                String v = in.str();
                if (!v.equals(prev)) {
                    map.put(field.name, v);
                }
            }
            default -> throw new IllegalArgumentException(field.type.kind.name());
        }
    }

    private static int put(Schema.Field field, Object value, byte[] dest, int p, int cap) {
        switch (field.type.kind) {
            case BOOL -> {
                room(dest, p, 1);
                return Wire.putBoolAt(dest, p, Codec.asBool(value));
            }
            case I32 -> {
                room(dest, p, 4);
                return Wire.putI32At(dest, p, Codec.asI32(value));
            }
            case I64, INT -> {
                room(dest, p, 8);
                return Wire.putI64At(dest, p, Codec.asI64(value));
            }
            case F32 -> {
                room(dest, p, 4);
                return Wire.putF32At(dest, p, Codec.asF32(value));
            }
            case F64 -> {
                room(dest, p, 8);
                return Wire.putF64At(dest, p, Codec.asF64(value));
            }
            case STR -> {
                String text = Codec.asString(value);
                int n = Wire.utf8Length(text);
                if (n > cap) {
                    throw KilnException.limit();
                }
                if (p < 0 || (long) p + 4L + n > dest.length) {
                    throw new IllegalArgumentException("short buffer");
                }
                return Wire.putStringAt(dest, p, text, cap);
            }
            default -> throw new IllegalArgumentException(field.type.kind.name());
        }
    }

    private static Object readOne(Schema type, Wire.In in) {
        return switch (type.kind) {
            case BOOL -> in.bool();
            case I32 -> in.i32();
            case I64, INT -> in.i64();
            case F32 -> in.f32();
            case F64 -> in.f64();
            case STR -> in.str();
            default -> throw new IllegalArgumentException(type.kind.name());
        };
    }

    private static void room(byte[] dest, int p, int n) {
        if (p < 0 || p > dest.length - n) {
            throw new IllegalArgumentException("short buffer");
        }
    }
}
