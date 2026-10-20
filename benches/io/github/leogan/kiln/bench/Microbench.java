package io.github.leogan.kiln.bench;

import io.github.leogan.kiln.Bytes;
import io.github.leogan.kiln.Kiln;
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
