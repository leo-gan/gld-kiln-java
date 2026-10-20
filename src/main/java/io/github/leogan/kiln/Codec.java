package io.github.leogan.kiln;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen values and the version-2 tensor directory. Check order matches the reference decoder. */
final class Codec {
    static final int HEADER = 64;
    static final int DESC = 128;
    static final int FLAG_NAME = 0x01;
    static final int FLAG_LE = 0x02;

    private Codec() {}

    static byte[] encode(Schema schema, Object value, int cap) {
        int size = sizeOf(schema, value, cap);
        byte[] out = new byte[size];
        int wrote = write(schema, value, out, 0, cap);
        if (wrote != size) {
            throw new IllegalStateException("encoded " + wrote + " of " + size);
        }
        return out;
    }

    static int encodeTo(Schema schema, Object value, byte[] dest, int destOff, int cap) {
        int size = sizeOf(schema, value, cap);
        if (destOff < 0 || size > dest.length - destOff) {
            throw new IllegalArgumentException("short buffer");
        }
        int wrote = write(schema, value, dest, destOff, cap);
        if (wrote != destOff + size) {
            throw new IllegalStateException("encoded size");
        }
        return wrote;
    }

    static Object decode(Schema schema, byte[] data, int off, int len, int cap, boolean trusted) {
        Wire.In in = new Wire.In(data, off, off + len, cap, trusted);
        Object value = read(schema, in);
        in.finish();
        return value;
    }

    static byte[] encodeFramed(Schema schema, Object value, int cap) {
        byte[] body = encode(schema, value, cap);
        byte[] out = new byte[16 + body.length];
        out[0] = 'K';
        out[1] = 'L';
        out[2] = 'N';
        out[3] = '0';
        Wire.putI64(out, 8, schema.typeHash());
        System.arraycopy(body, 0, out, 16, body.length);
        return out;
    }

    static Kiln.Framed decodeFramed(Schema schema, byte[] data, int cap, boolean trusted) {
        if (data.length < 16) {
            throw KilnException.eof();
        }
        if (data[0] != 'K' || data[1] != 'L' || data[2] != 'N' || data[3] != '0') {
            throw KilnException.frame();
        }
        if ((data[4] | data[5] | data[6] | data[7]) != 0) {
            throw KilnException.frame();
        }
        long hash = Wire.getI64(data, 8);
        if (hash != schema.typeHash()) {
            throw KilnException.schema();
        }
        Object value = decode(schema, data, 16, data.length - 16, cap, trusted);
        return new Kiln.Framed(hash, value);
    }

    static int encodeFixed(Schema schema, long[] fields, byte[] dest, int destOff) {
        if (schema.kind != Schema.Kind.STRUCT || schema.fixedWidth() < 0) {
            throw new IllegalArgumentException("not a fixed struct");
        }
        if (fields.length != schema.fields().size()) {
            throw new IllegalArgumentException("arity");
        }
        if (destOff < 0 || schema.fixedWidth() > dest.length - destOff) {
            throw new IllegalArgumentException("short buffer");
        }
        List<Schema.Field> fieldsSchema = schema.fields();
        if (fields.length == 2 && fieldsSchema.get(0).type == Schema.I32 && fieldsSchema.get(1).type == Schema.I32) {
            Wire.putI32(dest, destOff, (int) fields[0]);
            Wire.putI32(dest, destOff + 4, (int) fields[1]);
            return destOff + 8;
        }
        int p = destOff;
        for (int i = 0; i < fields.length; i++) {
            p = writeBits(fieldsSchema.get(i).type, fields[i], dest, p);
        }
        return p;
    }

    static int decodeFixed(Schema schema, byte[] src, int srcOff, long[] fields) {
        if (schema.kind != Schema.Kind.STRUCT || schema.fixedWidth() < 0) {
            throw new IllegalArgumentException("not a fixed struct");
        }
        if (fields.length != schema.fields().size()) {
            throw new IllegalArgumentException("arity");
        }
        if (srcOff < 0 || schema.fixedWidth() > src.length - srcOff) {
            throw KilnException.eof();
        }
        List<Schema.Field> fieldsSchema = schema.fields();
        if (fields.length == 2 && fieldsSchema.get(0).type == Schema.I32 && fieldsSchema.get(1).type == Schema.I32) {
            fields[0] = Wire.getI32(src, srcOff);
            fields[1] = Wire.getI32(src, srcOff + 4);
            return srcOff + 8;
        }
        int p = srcOff;
        for (int i = 0; i < fields.length; i++) {
            fields[i] = readBits(fieldsSchema.get(i).type, src, p);
            p += fieldsSchema.get(i).type.fixedWidth();
        }
        return p;
    }

    static int encodedTensorSize(List<Tensor> tensors) {
        return plan(tensors).total;
    }

    static byte[] encodeTensors(List<Tensor> tensors) {
        Plan plan = plan(tensors);
        byte[] out = new byte[plan.total];
        writePlan(plan, out, 0);
        return out;
    }

    static int encodeTensorsTo(List<Tensor> tensors, byte[] dest, int destOff) {
        if (tensors.size() == 1) {
            int simple = writeSimple(tensors.get(0), dest, destOff);
            if (simple >= 0) {
                return simple;
            }
        }
        Plan plan = plan(tensors);
        if (destOff < 0 || plan.total > dest.length - destOff) {
            throw new IllegalArgumentException("short buffer");
        }
        writePlan(plan, dest, destOff);
        return destOff + plan.total;
    }

    static TensorDirectory decodeTensors(byte[] data, int cap, boolean trusted) {
        return decodeDirectory(data, 0, data.length, cap, trusted, true);
    }

    private static int sizeOf(Schema schema, Object value, int cap) {
        return switch (schema.kind) {
            case BOOL -> 1;
            case I8, U8 -> 1;
            case I16, U16 -> 2;
            case I32, U32, F32 -> 4;
            case I64, INT, U64, UINT, F64 -> 8;
            case STR -> {
                int n = Wire.utf8Length(asString(value));
                if (n > cap) {
                    throw KilnException.limit();
                }
                yield 4 + n;
            }
            case BYTES -> 4 + rawLength(value, cap);
            case LIST -> sizeList(schema, value, cap);
            case OPT -> value == null ? 1 : 1 + sizeOf(schema.elem, value, cap);
            case DICT -> sizeDict(schema, value, cap);
            case STRUCT -> sizeStruct(schema, value, cap);
            case TENSORS -> encodedTensorSize(asTensors(value));
        };
    }

    private static int write(Schema schema, Object value, byte[] dest, int p, int cap) {
        return switch (schema.kind) {
            case BOOL -> Wire.putBoolAt(dest, p, asBool(value));
            case I8 -> {
                Wire.putI8(dest, p, asI8(value));
                yield p + 1;
            }
            case U8 -> {
                Wire.putI8(dest, p, asU8(value));
                yield p + 1;
            }
            case I16 -> {
                Wire.putI16(dest, p, asI16(value));
                yield p + 2;
            }
            case U16 -> {
                Wire.putI16(dest, p, asU16(value));
                yield p + 2;
            }
            case I32 -> Wire.putI32At(dest, p, asI32(value));
            case U32 -> Wire.putU32At(dest, p, asU32(value));
            case I64, INT -> Wire.putI64At(dest, p, asI64(value));
            case U64, UINT -> Wire.putI64At(dest, p, asU64(value));
            case F32 -> Wire.putF32At(dest, p, asF32(value));
            case F64 -> Wire.putF64At(dest, p, asF64(value));
            case STR -> Wire.putStringAt(dest, p, asString(value), cap);
            case BYTES -> writeRaw(value, dest, p, cap);
            case LIST -> writeList(schema, value, dest, p, cap);
            case OPT -> {
                if (value == null) {
                    dest[p] = 0;
                    yield p + 1;
                }
                dest[p] = 1;
                yield write(schema.elem, value, dest, p + 1, cap);
            }
            case DICT -> writeDict(schema, value, dest, p, cap);
            case STRUCT -> writeStruct(schema, value, dest, p, cap);
            case TENSORS -> {
                int end = encodeTensorsTo(asTensors(value), dest, p);
                yield end;
            }
        };
    }

    private static Object read(Schema schema, Wire.In in) {
        return switch (schema.kind) {
            case BOOL -> in.bool();
            case I8 -> in.i8();
            case U8 -> in.u8();
            case I16 -> in.i16();
            case U16 -> in.u16();
            case I32 -> in.i32();
            case U32 -> in.u32();
            case I64, INT, U64, UINT -> in.i64();
            case F32 -> in.f32();
            case F64 -> in.f64();
            case STR -> in.str();
            case BYTES -> in.bytes();
            case LIST -> readList(schema, in);
            case OPT -> in.opt() ? read(schema.elem, in) : null;
            case DICT -> readDict(schema, in);
            case STRUCT -> readStruct(schema, in);
            case TENSORS -> {
                TensorDirectory dir = decodeDirectory(in.b, in.p, in.end - in.p, in.cap, in.trusted, false);
                in.p += dir.blockLength;
                yield dir;
            }
        };
    }

    private static int writeBits(Schema schema, long bits, byte[] dest, int p) {
        return switch (schema.kind) {
            case BOOL -> Wire.putBoolAt(dest, p, bits != 0);
            case I8, U8 -> {
                Wire.putI8(dest, p, (int) bits);
                yield p + 1;
            }
            case I16, U16 -> {
                Wire.putI16(dest, p, (int) bits);
                yield p + 2;
            }
            case I32, U32 -> Wire.putI32At(dest, p, (int) bits);
            case F32 -> Wire.putI32At(dest, p, (int) bits);
            case I64, INT, U64, UINT, F64 -> Wire.putI64At(dest, p, bits);
            default -> throw new IllegalArgumentException(schema.kind.name());
        };
    }

    private static long readBits(Schema schema, byte[] src, int p) {
        return switch (schema.kind) {
            case BOOL, I8 -> Wire.getI8(src, p);
            case U8 -> Wire.getU8(src, p);
            case I16 -> Wire.getI16(src, p);
            case U16 -> Wire.getU16(src, p);
            case I32, F32 -> Wire.getI32(src, p);
            case U32 -> Wire.getU32(src, p);
            case I64, INT, U64, UINT, F64 -> Wire.getI64(src, p);
            default -> throw new IllegalArgumentException(schema.kind.name());
        };
    }

    private static int sizeStruct(Schema schema, Object value, int cap) {
        if (value instanceof long[] bits && schema.fixedWidth() >= 0) {
            if (bits.length != schema.fields().size()) {
                throw new IllegalArgumentException("arity");
            }
            return schema.fixedWidth();
        }
        int size = 0;
        if (value instanceof Map<?, ?> map) {
            for (Schema.Field field : schema.fields()) {
                size += sizeOf(field.type, fieldOf(field, map), cap);
            }
            return size;
        }
        if (value instanceof Object[] row) {
            List<Schema.Field> fields = schema.fields();
            if (row.length != fields.size()) {
                throw new IllegalArgumentException("arity");
            }
            for (int i = 0; i < row.length; i++) {
                size += sizeOf(fields.get(i).type, row[i], cap);
            }
            return size;
        }
        throw new IllegalArgumentException("struct value");
    }

    private static int writeStruct(Schema schema, Object value, byte[] dest, int p, int cap) {
        if (value instanceof long[] bits && schema.fixedWidth() >= 0) {
            return encodeFixed(schema, bits, dest, p);
        }
        if (value instanceof Map<?, ?> map) {
            for (Schema.Field field : schema.fields()) {
                p = write(field.type, fieldOf(field, map), dest, p, cap);
            }
            return p;
        }
        if (value instanceof Object[] row) {
            List<Schema.Field> fields = schema.fields();
            if (row.length != fields.size()) {
                throw new IllegalArgumentException("arity");
            }
            for (int i = 0; i < row.length; i++) {
                p = write(fields.get(i).type, row[i], dest, p, cap);
            }
            return p;
        }
        throw new IllegalArgumentException("struct value");
    }

    private static Object fieldOf(Schema.Field field, Map<?, ?> map) {
        if (!map.containsKey(field.name)) {
            if (field.type.kind == Schema.Kind.OPT) {
                return null;
            }
            throw new IllegalArgumentException("missing " + field.name);
        }
        Object value = map.get(field.name);
        if (value == null && field.type.kind != Schema.Kind.OPT) {
            throw new IllegalArgumentException("null " + field.name);
        }
        return value;
    }

    private static LinkedHashMap<String, Object> readStruct(Schema schema, Wire.In in) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (Schema.Field field : schema.fields()) {
            map.put(field.name, read(field.type, in));
        }
        return map;
    }

    private static int sizeList(Schema schema, Object value, int cap) {
        Schema elem = schema.elem;
        if ((elem.kind == Schema.Kind.U8 || elem.kind == Schema.Kind.I8) && (value instanceof byte[] || value instanceof Bytes)) {
            return 4 + rawLength(value, cap);
        }
        if (value instanceof List<?> list) {
            if (list.size() > cap) {
                throw KilnException.limit();
            }
            int size = 4;
            for (Object item : list) {
                size = addSize(size, sizeOf(elem, item, cap));
            }
            return size;
        }
        int n = arrayLength(value);
        if (n > cap) {
            throw KilnException.limit();
        }
        int width = elem.fixedWidth();
        if (width < 0) {
            throw new IllegalArgumentException("list");
        }
        long body = (long) n * width;
        if (body > Integer.MAX_VALUE - 4L) {
            throw KilnException.limit();
        }
        return 4 + (int) body;
    }

    private static int writeList(Schema schema, Object value, byte[] dest, int p, int cap) {
        Schema elem = schema.elem;
        if ((elem.kind == Schema.Kind.U8 || elem.kind == Schema.Kind.I8) && (value instanceof byte[] || value instanceof Bytes)) {
            return writeRaw(value, dest, p, cap);
        }
        if (value instanceof float[] nums && elem.kind == Schema.Kind.F32) {
            checkCount(nums.length, cap);
            Wire.putI32(dest, p, nums.length);
            for (int i = 0; i < nums.length; i++) {
                Wire.putF32At(dest, p + 4 + i * 4, nums[i]);
            }
            return p + 4 + nums.length * 4;
        }
        if (value instanceof double[] nums && elem.kind == Schema.Kind.F64) {
            checkCount(nums.length, cap);
            Wire.putI32(dest, p, nums.length);
            for (int i = 0; i < nums.length; i++) {
                Wire.putF64At(dest, p + 4 + i * 8, nums[i]);
            }
            return p + 4 + nums.length * 8;
        }
        if (value instanceof int[] nums) {
            checkCount(nums.length, cap);
            Wire.putI32(dest, p, nums.length);
            int q = p + 4;
            for (int num : nums) {
                q = write(elem, num, dest, q, cap);
            }
            return q;
        }
        if (value instanceof long[] nums) {
            checkCount(nums.length, cap);
            Wire.putI32(dest, p, nums.length);
            int q = p + 4;
            for (long num : nums) {
                q = write(elem, num, dest, q, cap);
            }
            return q;
        }
        if (value instanceof boolean[] nums && elem.kind == Schema.Kind.BOOL) {
            checkCount(nums.length, cap);
            Wire.putI32(dest, p, nums.length);
            for (int i = 0; i < nums.length; i++) {
                dest[p + 4 + i] = (byte) (nums[i] ? 1 : 0);
            }
            return p + 4 + nums.length;
        }
        if (value instanceof List<?> list) {
            checkCount(list.size(), cap);
            Wire.putI32(dest, p, list.size());
            int q = p + 4;
            for (Object item : list) {
                q = write(elem, item, dest, q, cap);
            }
            return q;
        }
        throw new IllegalArgumentException("list");
    }

    private static Object readList(Schema schema, Wire.In in) {
        Schema elem = schema.elem;
        // Count must fit before element storage exists.
        int count = in.count(elem.minSize());
        if (elem.kind == Schema.Kind.U8 || elem.kind == Schema.Kind.I8) {
            Bytes view = Bytes.view(in.b, in.p, count);
            in.p += count;
            return view;
        }
        return switch (elem.kind) {
            case F32 -> {
                float[] out = new float[count];
                for (int i = 0; i < count; i++) {
                    out[i] = in.f32();
                }
                yield out;
            }
            case F64 -> {
                double[] out = new double[count];
                for (int i = 0; i < count; i++) {
                    out[i] = in.f64();
                }
                yield out;
            }
            case BOOL -> {
                boolean[] out = new boolean[count];
                for (int i = 0; i < count; i++) {
                    out[i] = in.bool();
                }
                yield out;
            }
            case I64, INT, U64, UINT, U32 -> {
                long[] out = new long[count];
                for (int i = 0; i < count; i++) {
                    out[i] = ((Number) read(elem, in)).longValue();
                }
                yield out;
            }
            case I8, U8, I16, U16, I32 -> {
                int[] out = new int[count];
                for (int i = 0; i < count; i++) {
                    out[i] = ((Number) read(elem, in)).intValue();
                }
                yield out;
            }
            case STR -> {
                ArrayList<String> out = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    out.add(in.str());
                }
                yield out;
            }
            default -> {
                ArrayList<Object> out = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    out.add(read(elem, in));
                }
                yield out;
            }
        };
    }

    private static int sizeDict(Schema schema, Object value, int cap) {
        Map<?, ?> map = asMap(value);
        if (map.size() > cap) {
            throw KilnException.limit();
        }
        int size = 4;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = asString(entry.getKey());
            int keyLen = Wire.utf8Length(key);
            if (keyLen > cap) {
                throw KilnException.limit();
            }
            size = addSize(size, 4 + keyLen);
            size = addSize(size, sizeOf(schema.elem, entry.getValue(), cap));
        }
        return size;
    }

    private static int writeDict(Schema schema, Object value, byte[] dest, int p, int cap) {
        Map<?, ?> map = asMap(value);
        checkCount(map.size(), cap);
        ArrayList<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            keys.add(asString(key));
        }
        keys.sort(Wire::compareUtf8);
        Wire.putI32(dest, p, keys.size());
        int q = p + 4;
        for (String key : keys) {
            q = Wire.putStringAt(dest, q, key, cap);
            q = write(schema.elem, map.get(key), dest, q, cap);
        }
        return q;
    }

    private static LinkedHashMap<String, Object> readDict(Schema schema, Wire.In in) {
        int each = 4 + schema.elem.minSize();
        int count = in.count(each);
        LinkedHashMap<String, Object> map = LinkedHashMap.newLinkedHashMap(count);
        Bytes prev = null;
        for (int i = 0; i < count; i++) {
            long n = in.u32();
            if (n > in.cap) {
                throw KilnException.limit();
            }
            if (n > in.remaining()) {
                throw KilnException.eof();
            }
            int len = (int) n;
            int off = in.p;
            in.p += len;
            if (!in.trusted && !Wire.isUtf8(in.b, off, len)) {
                throw KilnException.utf8();
            }
            if (prev != null && compareBytes(prev.array(), prev.offset(), prev.length(), in.b, off, len) >= 0) {
                throw KilnException.type();
            }
            prev = Bytes.view(in.b, off, len);
            String key = new String(in.b, off, len, StandardCharsets.UTF_8);
            map.put(key, read(schema.elem, in));
        }
        return map;
    }

    private static int compareBytes(byte[] a, int ao, int al, byte[] b, int bo, int bl) {
        int n = Math.min(al, bl);
        for (int i = 0; i < n; i++) {
            int d = (a[ao + i] & 0xFF) - (b[bo + i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return al - bl;
    }

    private static final class Plan {
        final List<Tensor> tensors;
        final byte[] heap;
        final int[] nameOff;
        final int[] nameLen;
        final int[] layoutOff;
        final int[] layoutLen;
        final long[] byteOff;
        final long payloadBytes;
        final int flags;
        final int total;

        Plan(
                List<Tensor> tensors,
                byte[] heap,
                int[] nameOff,
                int[] nameLen,
                int[] layoutOff,
                int[] layoutLen,
                long[] byteOff,
                long payloadBytes,
                int flags,
                int total) {
            this.tensors = tensors;
            this.heap = heap;
            this.nameOff = nameOff;
            this.nameLen = nameLen;
            this.layoutOff = layoutOff;
            this.layoutLen = layoutLen;
            this.byteOff = byteOff;
            this.payloadBytes = payloadBytes;
            this.flags = flags;
            this.total = total;
        }
    }

    /** One whole dtype, no name, no layout, scheme 0. Returns -1 to use the general encoder. */
    private static int writeSimple(Tensor tensor, byte[] dest, int destOff) {
        if (tensor.name != null
                || tensor.layout != null
                || tensor.blockSize != 0
                || tensor.quantScheme != 0
                || tensor.quantAxis != 0xFFFF
                || tensor.groupSize != 0
                || tensor.scaleIndex != 0xFFFFFFFFL
                || tensor.outerIndex != 0xFFFFFFFFL
                || tensor.rank > 8
                || Tensor.wholeWidth(tensor.dtype) == 0) {
            return -1;
        }
        int payload = tensor.data.length();
        if (payload > Integer.MAX_VALUE - 192) {
            throw KilnException.limit();
        }
        int total = 192 + payload;
        if (destOff < 0 || total > dest.length - destOff) {
            throw new IllegalArgumentException("short buffer");
        }
        dest[destOff] = 'K';
        dest[destOff + 1] = 'L';
        dest[destOff + 2] = 'N';
        dest[destOff + 3] = 'T';
        dest[destOff + 4] = 2;
        dest[destOff + 5] = (byte) FLAG_LE;
        Wire.putI16(dest, destOff + 6, 0);
        Wire.putI32(dest, destOff + 8, 1);
        Wire.putI32(dest, destOff + 12, 0);
        Wire.putI64(dest, destOff + 16, payload);
        Wire.putI64(dest, destOff + 24, DESC);
        Wire.putI64(dest, destOff + 32, 0L);
        Wire.putI64(dest, destOff + 40, 0L);
        Wire.putI64(dest, destOff + 48, 0L);
        Wire.putI64(dest, destOff + 56, 0L);
        int base = destOff + HEADER;
        Wire.putI16(dest, base, tensor.dtype);
        dest[base + 2] = (byte) tensor.rank;
        dest[base + 3] = 0;
        Wire.putI16(dest, base + 4, 0xFFFF);
        Wire.putI16(dest, base + 6, 0);
        Wire.putI16(dest, base + 8, 0xFFFF);
        Wire.putI16(dest, base + 10, 0);
        Wire.putI32(dest, base + 12, 0);
        Wire.putI16(dest, base + 16, 0);
        Wire.putI16(dest, base + 18, 0xFFFF);
        Wire.putI32(dest, base + 20, 0);
        Wire.putI32(dest, base + 24, 0xFFFFFFFF);
        Wire.putI32(dest, base + 28, 0xFFFFFFFF);
        Wire.putI64(dest, base + 32, 0L);
        Wire.putI64(dest, base + 40, payload);
        for (int dim = 0; dim < 8; dim++) {
            Wire.putI64(dest, base + 48 + 8 * dim, tensor.shape[dim]);
        }
        Wire.putI64(dest, base + 112, 0L);
        Wire.putI64(dest, base + 120, 0L);
        if (payload > 0) {
            tensor.data.copyTo(dest, destOff + 192);
        }
        return destOff + total;
    }

    /**
     * Fast decode for one unnamed whole-dtype tensor at payload offset 0.
     * Returns null when the block is a different shape so the full checker runs.
     */
    private static TensorDirectory decodeSimple(byte[] buf, int off, int len, boolean trusted, boolean standalone) {
        if (len < 192) {
            return null;
        }
        if (buf[off] != 'K' || buf[off + 1] != 'L' || buf[off + 2] != 'N' || buf[off + 3] != 'T') {
            return null;
        }
        if ((buf[off + 4] & 0xFF) != 2 || (buf[off + 5] & 0xFF) != FLAG_LE) {
            return null;
        }
        if (Wire.getU16(buf, off + 6) != 0) {
            return null;
        }
        if ((Wire.getI64(buf, off + 32) | Wire.getI64(buf, off + 40) | Wire.getI64(buf, off + 48) | Wire.getI64(buf, off + 56))
                != 0) {
            return null;
        }
        if (Wire.getU32(buf, off + 8) != 1 || Wire.getU32(buf, off + 12) != 0 || Wire.getI64(buf, off + 24) != DESC) {
            return null;
        }
        long payloadBytes = Wire.getI64(buf, off + 16);
        if (Long.compareUnsigned(payloadBytes, Integer.MAX_VALUE - 192) > 0) {
            return null;
        }
        int payload = (int) payloadBytes;
        int block = 192 + payload;
        if (block > len || (standalone && block != len)) {
            return null;
        }
        int base = off + HEADER;
        if ((Wire.getI64(buf, base + 112) | Wire.getI64(buf, base + 120)) != 0) {
            return null;
        }
        if ((buf[base + 3] & 0xFF) != 0) {
            return null;
        }
        if (Wire.getU16(buf, base + 4) != 0xFFFF || Wire.getU16(buf, base + 6) != 0) {
            return null;
        }
        if (Wire.getU16(buf, base + 8) != 0xFFFF || Wire.getU16(buf, base + 10) != 0) {
            return null;
        }
        if (Wire.getU32(buf, base + 12) != 0 || Wire.getU16(buf, base + 16) != 0 || Wire.getU16(buf, base + 18) != 0xFFFF) {
            return null;
        }
        if (Wire.getU32(buf, base + 20) != 0
                || Wire.getU32(buf, base + 24) != 0xFFFFFFFFL
                || Wire.getU32(buf, base + 28) != 0xFFFFFFFFL) {
            return null;
        }
        if (Wire.getI64(buf, base + 32) != 0 || Wire.getI64(buf, base + 40) != payloadBytes) {
            return null;
        }
        int dtype = Wire.getU16(buf, base);
        int rank = buf[base + 2] & 0xFF;
        if (rank > 8 || Tensor.wholeWidth(dtype) == 0) {
            return null;
        }
        long[] shape = new long[8];
        for (int dim = 0; dim < 8; dim++) {
            shape[dim] = Wire.getI64(buf, base + 48 + 8 * dim);
        }
        if (!trusted) {
            for (int dim = rank; dim < 8; dim++) {
                if (shape[dim] != 0) {
                    return null;
                }
            }
            try {
                if (expectedBytes(dtype, elementCount(rank, shape)) != payloadBytes) {
                    return null;
                }
            } catch (KilnException ex) {
                return null;
            }
            if (dtype == 7) {
                int start = off + 192;
                for (int i = 0; i < payload; i++) {
                    if ((buf[start + i] & 0xFF) > 1) {
                        return null;
                    }
                }
            }
        }
        Bytes data = Bytes.view(buf, off + 192, payload);
        Tensor tensor = new Tensor(
                dtype,
                rank,
                shape,
                data,
                null,
                null,
                0L,
                0,
                0xFFFF,
                0L,
                0xFFFFFFFFL,
                0xFFFFFFFFL,
                null,
                null);
        return new TensorDirectory(block, false, List.of(tensor));
    }

    private static Plan plan(List<Tensor> tensors) {
        int n = tensors.size();
        int heapLen = 0;
        int named = 0;
        for (Tensor tensor : tensors) {
            if (tensor.name != null) {
                heapLen += tensor.name.length();
                named++;
            }
        }
        for (Tensor tensor : tensors) {
            if (tensor.layout != null) {
                heapLen += tensor.layout.length();
            }
        }
        byte[] heap = new byte[heapLen];
        int[] nameOff = new int[n];
        int[] nameLen = new int[n];
        int[] layoutOff = new int[n];
        int[] layoutLen = new int[n];
        int cursor = 0;
        for (int i = 0; i < n; i++) {
            Bytes name = tensors.get(i).name;
            if (name == null) {
                nameOff[i] = 0xFFFF;
                nameLen[i] = 0;
            } else {
                if (cursor >= 0xFFFF) {
                    throw KilnException.limit();
                }
                nameOff[i] = cursor;
                nameLen[i] = name.length();
                name.copyTo(heap, cursor);
                cursor += name.length();
            }
        }
        for (int i = 0; i < n; i++) {
            Bytes layout = tensors.get(i).layout;
            if (layout == null) {
                layoutOff[i] = 0xFFFF;
                layoutLen[i] = 0;
            } else {
                if (cursor >= 0xFFFF) {
                    throw KilnException.limit();
                }
                layoutOff[i] = cursor;
                layoutLen[i] = layout.length();
                layout.copyTo(heap, cursor);
                cursor += layout.length();
            }
        }
        long[] byteOff = new long[n];
        long end = 0;
        for (int i = 0; i < n; i++) {
            long at = Wire.align64(end);
            byteOff[i] = at;
            end = Wire.add(at, tensors.get(i).data.length());
        }
        long directoryBytes = Wire.add(Wire.mul(n, DESC), heapLen);
        long payloadBase = Wire.align64(Wire.add(HEADER, directoryBytes));
        long total = Wire.add(payloadBase, end);
        if (total > Integer.MAX_VALUE) {
            throw KilnException.limit();
        }
        int flags = FLAG_LE | (named > 0 ? FLAG_NAME : 0);
        return new Plan(tensors, heap, nameOff, nameLen, layoutOff, layoutLen, byteOff, end, flags, (int) total);
    }

    private static void writePlan(Plan plan, byte[] dest, int p) {
        Arrays.fill(dest, p, p + plan.total, (byte) 0);
        dest[p] = 'K';
        dest[p + 1] = 'L';
        dest[p + 2] = 'N';
        dest[p + 3] = 'T';
        dest[p + 4] = 2;
        dest[p + 5] = (byte) plan.flags;
        int n = plan.tensors.size();
        Wire.putI32(dest, p + 8, n);
        Wire.putI32(dest, p + 12, plan.heap.length);
        Wire.putI64(dest, p + 16, plan.payloadBytes);
        long directoryBytes = Wire.add(Wire.mul(n, DESC), plan.heap.length);
        Wire.putI64(dest, p + 24, directoryBytes);
        for (int i = 0; i < n; i++) {
            Tensor tensor = plan.tensors.get(i);
            int base = p + HEADER + i * DESC;
            Wire.putI16(dest, base, tensor.dtype);
            dest[base + 2] = (byte) tensor.rank;
            Wire.putI16(dest, base + 4, plan.nameOff[i]);
            Wire.putI16(dest, base + 6, plan.nameLen[i]);
            Wire.putI16(dest, base + 8, plan.layoutOff[i]);
            Wire.putI16(dest, base + 10, plan.layoutLen[i]);
            Wire.putI32(dest, base + 12, (int) tensor.blockSize);
            Wire.putI16(dest, base + 16, tensor.quantScheme);
            Wire.putI16(dest, base + 18, tensor.quantAxis);
            Wire.putI32(dest, base + 20, (int) tensor.groupSize);
            Wire.putI32(dest, base + 24, (int) tensor.scaleIndex);
            Wire.putI32(dest, base + 28, (int) tensor.outerIndex);
            Wire.putI64(dest, base + 32, plan.byteOff[i]);
            Wire.putI64(dest, base + 40, tensor.data.length());
            for (int dim = 0; dim < 8; dim++) {
                Wire.putI64(dest, base + 48 + 8 * dim, tensor.shape[dim]);
            }
        }
        if (plan.heap.length > 0) {
            System.arraycopy(plan.heap, 0, dest, p + HEADER + n * DESC, plan.heap.length);
        }
        int payloadBase = (int) Wire.align64(HEADER + directoryBytes);
        for (int i = 0; i < n; i++) {
            Bytes data = plan.tensors.get(i).data;
            if (data.length() > 0) {
                data.copyTo(dest, p + payloadBase + (int) plan.byteOff[i]);
            }
        }
    }

    private static TensorDirectory decodeDirectory(
            byte[] buf, int off, int len, int cap, boolean trusted, boolean standalone) {
        TensorDirectory simple = decodeSimple(buf, off, len, trusted, standalone);
        if (simple != null) {
            return simple;
        }
        if (len < 4) {
            throw KilnException.eof();
        }
        if (buf[off] != 'K' || buf[off + 1] != 'L' || buf[off + 2] != 'N' || buf[off + 3] != 'T') {
            throw KilnException.frame();
        }
        if (len < HEADER) {
            throw KilnException.eof();
        }
        int version = buf[off + 4] & 0xFF;
        if (version != 2) {
            throw KilnException.frame();
        }
        int flags = buf[off + 5] & 0xFF;
        if ((flags & ~0x03) != 0) {
            throw KilnException.frame();
        }
        if ((flags & FLAG_LE) == 0) {
            throw KilnException.frame();
        }
        if (Wire.getU16(buf, off + 6) != 0) {
            throw KilnException.frame();
        }
        for (int i = 32; i < HEADER; i++) {
            if (buf[off + i] != 0) {
                throw KilnException.frame();
            }
        }
        long count = Wire.getU32(buf, off + 8);
        long nameBytes = Wire.getU32(buf, off + 12);
        long payloadBytes = Wire.getI64(buf, off + 16);
        long directoryBytes = Wire.getI64(buf, off + 24);
        if (Long.compareUnsigned(count, cap) > 0 || Long.compareUnsigned(nameBytes, cap) > 0) {
            throw KilnException.limit();
        }
        long expectDir = Wire.add(Wire.mul(count, DESC), nameBytes);
        if (directoryBytes != expectDir) {
            throw KilnException.type();
        }
        long directoryEnd = Wire.add(HEADER, directoryBytes);
        if (Long.compareUnsigned(directoryEnd, len) > 0) {
            throw KilnException.eof();
        }
        long payloadBase = Wire.align64(directoryEnd);
        long blockEnd = Wire.add(payloadBase, payloadBytes);
        if (Long.compareUnsigned(blockEnd, len) > 0) {
            throw KilnException.eof();
        }
        int dirEnd = (int) directoryEnd;
        int payBase = (int) payloadBase;
        for (int i = dirEnd; i < payBase; i++) {
            if (buf[off + i] != 0) {
                throw KilnException.type();
            }
        }
        int n = (int) count;
        Raw[] raw = new Raw[n];
        for (int index = 0; index < n; index++) {
            int base = off + HEADER + index * DESC;
            for (int i = 112; i < DESC; i++) {
                if (buf[base + i] != 0) {
                    throw KilnException.frame();
                }
            }
            Raw item = new Raw();
            item.dtype = Wire.getU16(buf, base);
            item.rank = buf[base + 2] & 0xFF;
            item.reserved = buf[base + 3] & 0xFF;
            item.nameOff = Wire.getU16(buf, base + 4);
            item.nameLen = Wire.getU16(buf, base + 6);
            item.layoutOff = Wire.getU16(buf, base + 8);
            item.layoutLen = Wire.getU16(buf, base + 10);
            item.blockSize = Wire.getU32(buf, base + 12);
            item.quantScheme = Wire.getU16(buf, base + 16);
            item.quantAxis = Wire.getU16(buf, base + 18);
            item.groupSize = Wire.getU32(buf, base + 20);
            item.scaleIndex = Wire.getU32(buf, base + 24);
            item.outerIndex = Wire.getU32(buf, base + 28);
            item.byteOffset = Wire.getI64(buf, base + 32);
            item.byteLength = Wire.getI64(buf, base + 40);
            item.shape = new long[8];
            for (int dim = 0; dim < 8; dim++) {
                item.shape[dim] = Wire.getI64(buf, base + 48 + 8 * dim);
            }
            raw[index] = item;
        }
        int heapOff = off + HEADER + n * DESC;
        int nameLimit = (int) nameBytes;
        boolean namesFlag = (flags & FLAG_NAME) != 0;
        boolean hasName = false;
        for (Raw item : raw) {
            if (item.nameLen != 0) {
                hasName = true;
                break;
            }
        }
        if (namesFlag != hasName) {
            throw KilnException.type();
        }
        if (namesFlag) {
            for (Raw item : raw) {
                if (item.nameLen == 0) {
                    throw KilnException.type();
                }
            }
        }
        HashSet<Slice> seen = new HashSet<>();
        for (Raw item : raw) {
            if (item.reserved != 0) {
                throw KilnException.frame();
            }
            if (item.nameLen == 0) {
                if (item.nameOff != 0xFFFF) {
                    throw KilnException.type();
                }
            } else {
                if (item.nameOff == 0xFFFF) {
                    throw KilnException.type();
                }
                long end = Wire.add(item.nameOff, item.nameLen);
                if (Long.compareUnsigned(end, nameBytes) > 0) {
                    throw KilnException.type();
                }
                int start = heapOff + item.nameOff;
                if (!trusted && !Wire.isUtf8(buf, start, item.nameLen)) {
                    throw KilnException.utf8();
                }
                Slice key = new Slice(buf, start, item.nameLen);
                if (!seen.add(key)) {
                    throw KilnException.type();
                }
                item.name = Bytes.view(buf, start, item.nameLen);
                item.nameText = text(buf, start, item.nameLen, trusted);
            }
            if (item.layoutLen == 0) {
                if (item.layoutOff != 0xFFFF) {
                    throw KilnException.type();
                }
            } else {
                long end = Wire.add(item.layoutOff, item.layoutLen);
                if (Long.compareUnsigned(end, nameBytes) > 0) {
                    throw KilnException.type();
                }
                int start = heapOff + item.layoutOff;
                if (!trusted && !Wire.isUtf8(buf, start, item.layoutLen)) {
                    throw KilnException.utf8();
                }
                item.layout = Bytes.view(buf, start, item.layoutLen);
                item.layoutText = text(buf, start, item.layoutLen, trusted);
            }
        }
        if (!heapMatches(buf, heapOff, nameLimit, raw)) {
            throw KilnException.type();
        }
        long[] rangeEnd = new long[n];
        for (int i = 0; i < n; i++) {
            Raw item = raw[i];
            long itemEnd = Wire.add(item.byteOffset, item.byteLength);
            if (Long.compareUnsigned(itemEnd, payloadBytes) > 0) {
                throw KilnException.type();
            }
            rangeEnd[i] = itemEnd;
            if (!trusted) {
                checkTensor(item);
            }
        }
        for (int left = 0; left < n; left++) {
            for (int right = left + 1; right < n; right++) {
                long a0 = raw[left].byteOffset;
                long a1 = rangeEnd[left];
                long b0 = raw[right].byteOffset;
                long b1 = rangeEnd[right];
                if (a0 == a1 || b0 == b1) {
                    continue;
                }
                if (Long.compareUnsigned(a0, b1) < 0 && Long.compareUnsigned(b0, a1) < 0) {
                    throw KilnException.type();
                }
            }
        }
        long placed = 0;
        for (Raw item : raw) {
            if (item.byteOffset != Wire.align64(placed)) {
                throw KilnException.type();
            }
            placed = Wire.add(item.byteOffset, item.byteLength);
        }
        if (placed != payloadBytes) {
            throw KilnException.type();
        }
        int payloadOff = off + payBase;
        if (!trusted) {
            for (Raw item : raw) {
                int start = payloadOff + (int) item.byteOffset;
                int length = (int) item.byteLength;
                if (Tensor.packed(item.dtype)) {
                    long elems = elementCount(item.rank, item.shape);
                    if ((elems & 1L) == 1L && length > 0 && (buf[start + length - 1] & 0xF0) != 0) {
                        throw KilnException.type();
                    }
                }
                if (item.dtype == 7) {
                    for (int i = 0; i < length; i++) {
                        if ((buf[start + i] & 0xFF) > 1) {
                            throw KilnException.type();
                        }
                    }
                }
            }
        }
        long gap = 0;
        for (Raw item : raw) {
            int from = (int) gap;
            int to = (int) item.byteOffset;
            for (int i = from; i < to; i++) {
                if (buf[payloadOff + i] != 0) {
                    throw KilnException.type();
                }
            }
            gap = Wire.add(item.byteOffset, item.byteLength);
        }
        if (!trusted) {
            checkQuant(raw);
        }
        if (standalone && blockEnd != len) {
            throw KilnException.trailing();
        }
        ArrayList<Tensor> tensors = new ArrayList<>(n);
        for (Raw item : raw) {
            int start = payloadOff + (int) item.byteOffset;
            int length = (int) item.byteLength;
            Bytes data = length == 0 ? Bytes.view(buf, start, 0) : Bytes.view(buf, start, length);
            tensors.add(new Tensor(
                    item.dtype,
                    item.rank,
                    item.shape,
                    data,
                    item.name,
                    item.layout,
                    item.blockSize,
                    item.quantScheme,
                    item.quantAxis,
                    item.groupSize,
                    item.scaleIndex,
                    item.outerIndex,
                    item.nameText,
                    item.layoutText));
        }
        return new TensorDirectory((int) blockEnd, namesFlag, List.copyOf(tensors));
    }

    /** Heap bytes must be the name slices in order, then the layout slices in order. */
    private static boolean heapMatches(byte[] buf, int heapOff, int nameBytes, Raw[] raw) {
        int at = 0;
        for (Raw item : raw) {
            if (item.name == null) {
                continue;
            }
            if (!sliceEqualsHeap(buf, heapOff, nameBytes, at, item.name)) {
                return false;
            }
            at += item.nameLen;
        }
        for (Raw item : raw) {
            if (item.layout == null) {
                continue;
            }
            if (!sliceEqualsHeap(buf, heapOff, nameBytes, at, item.layout)) {
                return false;
            }
            at += item.layoutLen;
        }
        return at == nameBytes;
    }

    private static boolean sliceEqualsHeap(byte[] buf, int heapOff, int nameBytes, int at, Bytes slice) {
        if ((long) at + slice.length() > nameBytes) {
            return false;
        }
        return Arrays.equals(
                buf,
                heapOff + at,
                heapOff + at + slice.length(),
                slice.array(),
                slice.offset(),
                slice.offset() + slice.length());
    }

    private static String text(byte[] buf, int off, int len, boolean trusted) {
        if (Wire.isUtf8(buf, off, len)) {
            return new String(buf, off, len, StandardCharsets.UTF_8);
        }
        if (!trusted) {
            throw KilnException.utf8();
        }
        return new String(buf, off, len, StandardCharsets.ISO_8859_1);
    }

    private static void checkTensor(Raw item) {
        if (item.rank > 8) {
            throw KilnException.type();
        }
        for (int dim = item.rank; dim < 8; dim++) {
            if (item.shape[dim] != 0) {
                throw KilnException.type();
            }
        }
        int dtype = item.dtype;
        if (dtype == 0 || (dtype >= 20 && dtype < Tensor.OPAQUE_MIN) || dtype > Tensor.OPAQUE_MAX) {
            throw KilnException.type();
        }
        long count = elementCount(item.rank, item.shape);
        if (dtype >= Tensor.OPAQUE_MIN) {
            if (item.layout == null || item.layout.length() == 0 || item.blockSize == 0) {
                throw KilnException.type();
            }
            if (Long.remainderUnsigned(item.byteLength, item.blockSize) != 0) {
                throw KilnException.type();
            }
            return;
        }
        if (item.layout != null || item.blockSize != 0) {
            throw KilnException.type();
        }
        long expect = expectedBytes(dtype, count);
        if (item.byteLength != expect) {
            throw KilnException.type();
        }
    }

    private static long elementCount(int rank, long[] shape) {
        if (rank == 0) {
            return 1;
        }
        long count = 1;
        for (int dim = 0; dim < rank; dim++) {
            count = Wire.mul(count, shape[dim]);
        }
        return count;
    }

    private static long expectedBytes(int dtype, long count) {
        int width = Tensor.wholeWidth(dtype);
        if (width != 0) {
            return Wire.mul(count, width);
        }
        if (Tensor.packed(dtype)) {
            long half = count >>> 1;
            if ((count & 1L) == 1L) {
                half = Wire.add(half, 1);
            }
            return half;
        }
        throw KilnException.type();
    }

    private static void checkQuant(Raw[] tensors) {
        for (int index = 0; index < tensors.length; index++) {
            Raw item = tensors[index];
            int scheme = item.quantScheme;
            if (scheme == 0) {
                if (item.groupSize != 0
                        || item.quantAxis != 0xFFFF
                        || item.scaleIndex != 0xFFFFFFFFL
                        || item.outerIndex != 0xFFFFFFFFL) {
                    throw KilnException.type();
                }
                continue;
            }
            if (scheme != 1 && scheme != 2) {
                throw KilnException.type();
            }
            if (item.rank == 0 || Integer.compareUnsigned(item.quantAxis, item.rank) >= 0) {
                throw KilnException.type();
            }
            if (item.groupSize == 0) {
                throw KilnException.type();
            }
            long scale = item.scaleIndex;
            if (scale == index || Long.compareUnsigned(scale, tensors.length) >= 0) {
                throw KilnException.type();
            }
            Raw other = tensors[(int) scale];
            if (other.quantScheme != 0) {
                throw KilnException.type();
            }
            long groups = ceilDiv(item.shape[item.quantAxis], item.groupSize);
            long otherDims = 1;
            for (int dim = 0; dim < item.rank; dim++) {
                if (dim != item.quantAxis) {
                    otherDims = Wire.mul(otherDims, item.shape[dim]);
                }
            }
            long expect = Wire.mul(groups, otherDims);
            long scaleCount = elementCount(other.rank, other.shape);
            if (scaleCount != expect) {
                throw KilnException.type();
            }
            if (scheme == 1) {
                if (item.outerIndex != 0xFFFFFFFFL) {
                    throw KilnException.type();
                }
                continue;
            }
            long outer = item.outerIndex;
            if (outer == index || outer == scale || Long.compareUnsigned(outer, tensors.length) >= 0) {
                throw KilnException.type();
            }
            Raw outerItem = tensors[(int) outer];
            if (outerItem.quantScheme != 0
                    || outerItem.dtype != 2
                    || elementCount(outerItem.rank, outerItem.shape) != 1) {
                throw KilnException.type();
            }
        }
    }

    private static long ceilDiv(long n, long d) {
        long sum = n + (d - 1);
        if (Long.compareUnsigned(sum, n) < 0) {
            BigInteger bn = new BigInteger(Long.toUnsignedString(n));
            BigInteger bd = new BigInteger(Long.toUnsignedString(d));
            BigInteger q = bn.add(bd).subtract(BigInteger.ONE).divide(bd);
            if (q.bitLength() > 64) {
                throw KilnException.limit();
            }
            return q.longValue();
        }
        return Long.divideUnsigned(sum, d);
    }

    private static final class Raw {
        int dtype;
        int rank;
        int reserved;
        int nameOff;
        int nameLen;
        int layoutOff;
        int layoutLen;
        long blockSize;
        int quantScheme;
        int quantAxis;
        long groupSize;
        long scaleIndex;
        long outerIndex;
        long byteOffset;
        long byteLength;
        long[] shape;
        Bytes name;
        Bytes layout;
        String nameText;
        String layoutText;
    }

    private static final class Slice {
        final byte[] b;
        final int off;
        final int len;

        Slice(byte[] b, int off, int len) {
            this.b = b;
            this.off = off;
            this.len = len;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Slice s)) {
                return false;
            }
            return len == s.len && Arrays.equals(b, off, off + len, s.b, s.off, s.off + len);
        }

        @Override
        public int hashCode() {
            int h = 1;
            for (int i = off; i < off + len; i++) {
                h = 31 * h + (b[i] & 0xFF);
            }
            return h;
        }
    }

    private static List<Tensor> asTensors(Object value) {
        if (value instanceof TensorDirectory dir) {
            return dir.tensors;
        }
        if (value instanceof Tensor tensor) {
            return List.of(tensor);
        }
        if (value instanceof List<?> list) {
            ArrayList<Tensor> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add((Tensor) item);
            }
            return out;
        }
        throw new IllegalArgumentException("tensors");
    }

    private static int rawLength(Object value, int cap) {
        int n;
        if (value instanceof byte[] raw) {
            n = raw.length;
        } else if (value instanceof Bytes bytes) {
            n = bytes.length();
        } else {
            throw new IllegalArgumentException("bytes");
        }
        if (Integer.compareUnsigned(n, cap) > 0) {
            throw KilnException.limit();
        }
        return n;
    }

    private static int writeRaw(Object value, byte[] dest, int p, int cap) {
        if (value instanceof byte[] raw) {
            return Wire.putRawAt(dest, p, raw, 0, raw.length, cap);
        }
        if (value instanceof Bytes bytes) {
            return Wire.putRawAt(dest, p, bytes.array(), bytes.offset(), bytes.length(), cap);
        }
        throw new IllegalArgumentException("bytes");
    }

    private static int arrayLength(Object value) {
        if (value instanceof byte[] a) {
            return a.length;
        }
        if (value instanceof int[] a) {
            return a.length;
        }
        if (value instanceof long[] a) {
            return a.length;
        }
        if (value instanceof float[] a) {
            return a.length;
        }
        if (value instanceof double[] a) {
            return a.length;
        }
        if (value instanceof boolean[] a) {
            return a.length;
        }
        if (value instanceof Bytes a) {
            return a.length();
        }
        throw new IllegalArgumentException("list");
    }

    private static void checkCount(int count, int cap) {
        if (Integer.compareUnsigned(count, cap) > 0) {
            throw KilnException.limit();
        }
    }

    private static int addSize(int a, int b) {
        long sum = (long) a + b;
        if (sum > Integer.MAX_VALUE) {
            throw KilnException.limit();
        }
        return (int) sum;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        throw new IllegalArgumentException("dict");
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("bool");
    }

    private static String asString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("string");
    }

    private static long asNumber(Object value) {
        if (value instanceof BigInteger bi) {
            if (bi.signum() < 0 || bi.bitLength() > 64) {
                throw new IllegalArgumentException("integer");
            }
            return bi.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("integer");
    }

    private static int asI8(Object value) {
        long n = asNumber(value);
        if (n < -128 || n > 127) {
            throw new IllegalArgumentException("i8");
        }
        return (int) n;
    }

    private static int asU8(Object value) {
        long n = asNumber(value);
        if (n < 0 || n > 255) {
            throw new IllegalArgumentException("u8");
        }
        return (int) n;
    }

    private static int asI16(Object value) {
        long n = asNumber(value);
        if (n < -32768 || n > 32767) {
            throw new IllegalArgumentException("i16");
        }
        return (int) n;
    }

    private static int asU16(Object value) {
        long n = asNumber(value);
        if (n < 0 || n > 65535) {
            throw new IllegalArgumentException("u16");
        }
        return (int) n;
    }

    private static int asI32(Object value) {
        long n = asNumber(value);
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("i32");
        }
        return (int) n;
    }

    private static long asU32(Object value) {
        if (value instanceof BigInteger bi) {
            if (bi.signum() < 0 || bi.bitLength() > 32) {
                throw new IllegalArgumentException("u32");
            }
            return bi.longValue();
        }
        long n = asNumber(value);
        if (Long.compareUnsigned(n, 0xFFFFFFFFL) > 0 || n < 0) {
            // Negative long is not a u32. Positive longs above 2^32-1 are rejected by the unsigned compare
            // only when the high bit is clear. A negative Java long is > 2^32 unsigned.
            throw new IllegalArgumentException("u32");
        }
        return n;
    }

    private static long asI64(Object value) {
        if (value instanceof BigInteger bi) {
            if (bi.bitLength() > 63 && !(bi.bitLength() == 64 && bi.signum() < 0 && bi.equals(BigInteger.valueOf(Long.MIN_VALUE)))) {
                if (bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                        || bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new IllegalArgumentException("i64");
                }
            }
            return bi.longValue();
        }
        return asNumber(value);
    }

    private static long asU64(Object value) {
        if (value instanceof BigInteger bi) {
            if (bi.signum() < 0 || bi.bitLength() > 64) {
                throw new IllegalArgumentException("u64");
            }
            return bi.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("u64");
    }

    private static float asF32(Object value) {
        if (value instanceof Float f) {
            return f;
        }
        if (value instanceof Double d) {
            return d.floatValue();
        }
        if (value instanceof Number n) {
            return n.floatValue();
        }
        throw new IllegalArgumentException("f32");
    }

    private static double asF64(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("f64");
    }
}
