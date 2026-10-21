package io.github.leogan.kiln.bench;

import io.github.leogan.kiln.Bytes;
import io.github.leogan.kiln.Kiln;
import io.github.leogan.kiln.NumericPool;
import io.github.leogan.kiln.Schema;
import io.github.leogan.kiln.Tensor;
import io.github.leogan.kiln.TensorDirectory;
import java.util.List;

/** Encode and decode a fixed struct batch and one 1024-element f32 tensor. */
public final class Microbench {
    private static volatile long sink;

    private Microbench() {}

    public static void main(String[] args) {
        Schema point = Schema.struct(
                "Point",
                Schema.field("x", Schema.I32),
                Schema.field("y", Schema.I32));
        int n = 200_000;
        byte[] structs = new byte[n * 8];
        long[] fields = new long[2];
        for (int warm = 0; warm < 3; warm++) {
            encodeStructs(point, structs, fields, n);
            decodeStructs(point, structs, fields, n);
        }
        long t0 = System.nanoTime();
        encodeStructs(point, structs, fields, n);
        long structEncode = System.nanoTime() - t0;
        t0 = System.nanoTime();
        long sum = decodeStructs(point, structs, fields, n);
        long structDecode = System.nanoTime() - t0;

        byte[] payload = new byte[1024 * 4];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        Tensor tensor = Tensor.of("f32", new long[] {1024}, payload);
        List<Tensor> one = List.of(tensor);
        int bytes = Kiln.encodedTensorSize(one);
        byte[] block = new byte[bytes];
        int reps = 20_000;
        for (int warm = 0; warm < 3; warm++) {
            Kiln.encodeTensorsTo(one, block, 0);
            touch(Kiln.decodeTensors(block));
        }
        t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            Kiln.encodeTensorsTo(one, block, 0);
        }
        long tensorEncode = System.nanoTime() - t0;
        t0 = System.nanoTime();
        int touched = 0;
        for (int i = 0; i < reps; i++) {
            touched += touch(Kiln.decodeTensors(block));
        }
        long tensorDecode = System.nanoTime() - t0;
        sink = sum + touched + block[0];

        System.out.println("struct_n " + n);
        System.out.println("struct_encode_ns " + (structEncode / n));
        System.out.println("struct_decode_ns " + (structDecode / n));
        System.out.println("tensor_reps " + reps);
        System.out.println("tensor_encode_ns " + (tensorEncode / reps));
        System.out.println("tensor_decode_ns " + (tensorDecode / reps));
        System.out.println("tensor_bytes " + bytes);
        System.out.println("sink " + sink);

        int million = 1 << 20;
        Schema f32s = Schema.list(Schema.F32);
        float[] values = new float[million];
        java.util.Arrays.fill(values, 1.25f);
        values[0] = Float.intBitsToFloat(0x80000000);
        byte[] wire = new byte[4 + million * 4];
        for (int warm = 0; warm < 5; warm++) {
            Kiln.encodeTo(f32s, values, wire, 0);
            touchFloats((float[]) Kiln.decode(f32s, wire));
        }
        long bestEnc = Long.MAX_VALUE;
        long bestDec = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            long a = System.nanoTime();
            Kiln.encodeTo(f32s, values, wire, 0);
            long b = System.nanoTime();
            float[] back = (float[]) Kiln.decode(f32s, wire);
            long c = System.nanoTime();
            sink += Float.floatToRawIntBits(back[0]);
            bestEnc = Math.min(bestEnc, b - a);
            bestDec = Math.min(bestDec, c - b);
        }
        System.out.println("f32_list_n " + million);
        System.out.println("f32_list_encode_ns " + bestEnc);
        System.out.println("f32_list_decode_ns " + bestDec);
        System.out.println("f32_list_bytes " + (4 + million * 4));
        Kiln.Options pooled = Kiln.Options.defaults().withPool(new NumericPool());
        for (int warm = 0; warm < 5; warm++) {
            touchFloats((float[]) Kiln.decode(f32s, wire, pooled));
        }
        long bestPool = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            long a = System.nanoTime();
            float[] back = (float[]) Kiln.decode(f32s, wire, pooled);
            long b = System.nanoTime() - a;
            sink += Float.floatToRawIntBits(back[0]);
            bestPool = Math.min(bestPool, b);
        }
        System.out.println("f32_list_pooled_decode_ns " + bestPool);

        Schema tensorSchema = Schema.struct(
                "Tensor",
                Schema.field("rows", Schema.I32),
                Schema.field("cols", Schema.I32),
                Schema.field("values", Schema.list(Schema.F32)));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("rows", 1024);
        row.put("cols", 1024);
        row.put("values", values);
        byte[] structWire = new byte[12 + million * 4];
        Kiln.Options structPool = Kiln.Options.defaults().withPool(new NumericPool());
        for (int warm = 0; warm < 5; warm++) {
            Kiln.encodeTo(tensorSchema, row, structWire, 0);
            touchFloats((float[]) ((java.util.Map<?, ?>) Kiln.decode(tensorSchema, structWire, structPool)).get("values"));
        }
        long bestStructEnc = Long.MAX_VALUE;
        long bestStructDec = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            long a = System.nanoTime();
            Kiln.encodeTo(tensorSchema, row, structWire, 0);
            long b = System.nanoTime();
            java.util.Map<?, ?> back = (java.util.Map<?, ?>) Kiln.decode(tensorSchema, structWire, structPool);
            long c = System.nanoTime();
            sink += Float.floatToRawIntBits(((float[]) back.get("values"))[0]);
            bestStructEnc = Math.min(bestStructEnc, b - a);
            bestStructDec = Math.min(bestStructDec, c - b);
        }
        System.out.println("tensor_struct_encode_ns " + bestStructEnc);
        System.out.println("tensor_struct_pooled_decode_ns " + bestStructDec);

        Schema message = Schema.struct(
                "Message",
                Schema.field("f_bool", Schema.BOOL),
                Schema.field("f_int32", Schema.I32),
                Schema.field("f_int64", Schema.I64),
                Schema.field("f_float64", Schema.F64),
                Schema.field("f_string", Schema.STR),
                Schema.field("f_bool_2", Schema.BOOL),
                Schema.field("f_int32_2", Schema.I32),
                Schema.field("f_string_2", Schema.STR));
        java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("f_bool", true);
        msg.put("f_int32", 1);
        msg.put("f_int64", 2L);
        msg.put("f_float64", 1.5);
        msg.put("f_string", "alpha");
        msg.put("f_bool_2", false);
        msg.put("f_int32_2", 3);
        msg.put("f_string_2", "beta");
        byte[] msgWire = new byte[64];
        int msgLen = Kiln.encodeTo(message, msg, msgWire, 0);
        byte[] msgBytes = java.util.Arrays.copyOf(msgWire, msgLen);
        for (int warm = 0; warm < 20; warm++) {
            Kiln.encodeTo(message, msg, msgWire, 0);
            sink += Kiln.decode(message, msgBytes).hashCode();
        }
        long bestMsgEnc = Long.MAX_VALUE;
        long bestMsgDec = Long.MAX_VALUE;
        for (int i = 0; i < 50; i++) {
            long a = System.nanoTime();
            Kiln.encodeTo(message, msg, msgWire, 0);
            long b = System.nanoTime();
            sink += Kiln.decode(message, msgBytes).hashCode();
            long c = System.nanoTime();
            bestMsgEnc = Math.min(bestMsgEnc, b - a);
            bestMsgDec = Math.min(bestMsgDec, c - b);
        }
        System.out.println("message_encode_ns " + bestMsgEnc);
        System.out.println("message_decode_ns " + bestMsgDec);
    }

    private static int touchFloats(float[] values) {
        return Float.floatToRawIntBits(values[0]) ^ Float.floatToRawIntBits(values[values.length - 1]);
    }

    private static void encodeStructs(Schema point, byte[] dest, long[] fields, int n) {
        for (int i = 0; i < n; i++) {
            fields[0] = i;
            fields[1] = -i;
            Kiln.encodeFixed(point, fields, dest, i * 8);
        }
    }

    private static long decodeStructs(Schema point, byte[] src, long[] fields, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Kiln.decodeFixed(point, src, i * 8, fields);
            sum += fields[0] + fields[1];
        }
        return sum;
    }

    private static int touch(TensorDirectory directory) {
        Bytes data = directory.tensors().get(0).data();
        return (data.get(0) & 0xFF) + (data.get(data.length() - 1) & 0xFF);
    }
}
