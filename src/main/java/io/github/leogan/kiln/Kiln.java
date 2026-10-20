package io.github.leogan.kiln;

import java.util.List;

/** From-scratch Kiln wire v2. Schemas are positional. Tensor payloads are views of the input. */
public final class Kiln {
    public static final int DEFAULT_CAP = Wire.DEFAULT_CAP;

    private Kiln() {}

    /** Cap is the maximum count and the maximum length of one string or byte run. */
    public record Options(int cap, boolean trusted) {
        public Options {
            if (cap < 0) {
                throw new IllegalArgumentException("cap");
            }
        }

        public static Options defaults() {
            return new Options(DEFAULT_CAP, false);
        }

        public Options withCap(int cap) {
            return new Options(cap, trusted);
        }

        public Options withTrusted(boolean trusted) {
            return new Options(cap, trusted);
        }
    }

    /** A 16-byte frame: {@code KLN0}, four zero bytes, then the schema hash. */
    public record Framed(long hash, Object value) {}

    public static long typeHash(Schema schema) {
        return schema.typeHash();
    }

    public static byte[] encode(Schema schema, Object value) {
        return encode(schema, value, Options.defaults());
    }

    public static byte[] encode(Schema schema, Object value, Options options) {
        return Codec.encode(schema, value, options.cap);
    }

    public static int encodeTo(Schema schema, Object value, byte[] dest, int destOff) {
        return Codec.encodeTo(schema, value, dest, destOff, DEFAULT_CAP);
    }

    public static Object decode(Schema schema, byte[] data) {
        return decode(schema, data, Options.defaults());
    }

    public static Object decode(Schema schema, byte[] data, Options options) {
        return Codec.decode(schema, data, 0, data.length, options.cap, options.trusted);
    }

    public static byte[] encodeFramed(Schema schema, Object value) {
        return Codec.encodeFramed(schema, value, DEFAULT_CAP);
    }

    public static Framed decodeFramed(Schema schema, byte[] data) {
        return decodeFramed(schema, data, Options.defaults());
    }

    public static Framed decodeFramed(Schema schema, byte[] data, Options options) {
        return Codec.decodeFramed(schema, data, options.cap, options.trusted);
    }

    public static byte[] encodeTensors(List<Tensor> tensors) {
        return Codec.encodeTensors(tensors);
    }

    public static int encodeTensorsTo(List<Tensor> tensors, byte[] dest, int destOff) {
        return Codec.encodeTensorsTo(tensors, dest, destOff);
    }

    public static int encodedTensorSize(List<Tensor> tensors) {
        return Codec.encodedTensorSize(tensors);
    }

    public static TensorDirectory decodeTensors(byte[] data) {
        return decodeTensors(data, Options.defaults());
    }

    public static TensorDirectory decodeTensors(byte[] data, Options options) {
        return Codec.decodeTensors(data, options.cap, options.trusted);
    }

    /** Fixed-width struct, one raw field per slot. No per-field allocation. */
    public static int encodeFixed(Schema schema, long[] fields, byte[] dest, int destOff) {
        return Codec.encodeFixed(schema, fields, dest, destOff);
    }

    public static int decodeFixed(Schema schema, byte[] src, int srcOff, long[] fields) {
        return Codec.decodeFixed(schema, src, srcOff, fields);
    }
}
