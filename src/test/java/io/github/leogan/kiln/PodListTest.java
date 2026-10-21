package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.leogan.kiln.gen.JavaGen;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PodListTest {
    @Test
    void generatorCopiesF32ListsInOneCall() {
        Schema schema = Schema.struct("Tensor", Schema.field("values", Schema.list(Schema.F32)));
        String source = JavaGen.emit(schema, "example").get("Tensor");
        assertTrue(source.contains("float[] values"));
        assertTrue(source.contains("Wire.putF32s"));
        assertTrue(source.contains("Wire.readF32s(in, value.values)"));
    }

    @Test
    void asciiAndNonAsciiStringsRoundTrip() {
        Schema schema = Schema.struct("S", Schema.field("a", Schema.STR), Schema.field("b", Schema.STR));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("a", "hello");
        row.put("b", "héllo");
        byte[] raw = encode(schema, row);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> back = (java.util.Map<String, Object>) Kiln.decode(schema, raw);
        assertEquals("hello", back.get("a"));
        assertEquals("héllo", back.get("b"));
    }

    @Test
    void f32ListRoundTripsSignalingBits() {
        Schema schema = Schema.list(Schema.F32);
        float[] values = new float[] {Float.intBitsToFloat(0x80000000), Float.intBitsToFloat(0x7fc00000), 1.5f};
        byte[] raw = encode(schema, values);
        float[] back = (float[]) Kiln.decode(schema, raw);
        assertEquals(0x80000000, Float.floatToRawIntBits(back[0]));
        assertEquals(0x7fc00000, Float.floatToRawIntBits(back[1]));
        assertEquals(1.5f, back[2]);
    }

    @Test
    void f64AndI64ListsRoundTrip() {
        Schema f64 = Schema.list(Schema.F64);
        double[] doubles = new double[] {-0.0, 2.5};
        assertArrayEquals(doubles, (double[]) Kiln.decode(f64, encode(f64, doubles)));
        Schema i64 = Schema.list(Schema.I64);
        long[] longs = new long[] {Long.MIN_VALUE, 7L};
        assertArrayEquals(longs, (long[]) Kiln.decode(i64, encode(i64, longs)));
        Schema i32 = Schema.list(Schema.I32);
        int[] ints = new int[] {-1, 9};
        assertArrayEquals(ints, (int[]) Kiln.decode(i32, encode(i32, ints)));
    }

    @Test
    void pooledDecodeReusesTheArrayAndKeepsBits() {
        Schema schema = Schema.list(Schema.F32);
        float[] values = new float[] {Float.intBitsToFloat(0x80000000), 3f};
        byte[] raw = encode(schema, values);
        Kiln.Options options = Kiln.Options.defaults().withPool(new NumericPool());
        float[] first = (float[]) Kiln.decode(schema, raw, options);
        float[] second = (float[]) Kiln.decode(schema, raw, options);
        assertEquals(first, second);
        assertEquals(0x80000000, Float.floatToRawIntBits(second[0]));
    }

    @Test
    void pooledDecodeGivesEachSameSizedListItsOwnArray() {
        Schema schema = Schema.struct(
                "Pair",
                Schema.field("a", Schema.list(Schema.F32)),
                Schema.field("b", Schema.list(Schema.F32)));
        float[] left = new float[] {1f, 2f};
        float[] right = new float[] {3f, 4f};
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("a", left);
        row.put("b", right);
        byte[] raw = encode(schema, row);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> back =
                (java.util.Map<String, Object>) Kiln.decode(schema, raw, Kiln.Options.defaults().withPool(new NumericPool()));
        float[] a = (float[]) back.get("a");
        float[] b = (float[]) back.get("b");
        assertEquals(1f, a[0]);
        assertEquals(3f, b[0]);
        assertEquals(false, a == b);
    }

    @Test
    void pooledNestedStructsDoNotShareAMap() {
        Schema schema = Schema.struct(
                "Wrap",
                Schema.field("meta", Schema.struct("Meta", Schema.field("region", Schema.STR))),
                Schema.field("name", Schema.STR));
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("region", "us");
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("meta", meta);
        row.put("name", "n");
        byte[] raw = encode(schema, row);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> back = (java.util.Map<String, Object>)
                Kiln.decode(schema, raw, Kiln.Options.defaults().withPool(new NumericPool()));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> backMeta = (java.util.Map<String, Object>) back.get("meta");
        assertEquals("us", backMeta.get("region"));
        assertEquals("n", back.get("name"));
        assertEquals(false, back == backMeta);
    }

    @Test
    void pooledStructKeepsRawFloatBitsAndDropsStaleFields() {
        Schema one = Schema.struct("One", Schema.field("x", Schema.I32), Schema.field("z", Schema.F32));
        Schema two = Schema.struct("Two", Schema.field("y", Schema.I64));
        Kiln.Options options = Kiln.Options.defaults().withPool(new NumericPool());
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("x", 1);
        row.put("z", Float.intBitsToFloat(0x80000000));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> first =
                (java.util.Map<String, Object>) Kiln.decode(one, encode(one, row), options);
        assertEquals(0x80000000, Float.floatToRawIntBits((Float) first.get("z")));
        row.put("x", 2);
        row.put("z", 0.0f);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> second =
                (java.util.Map<String, Object>) Kiln.decode(one, encode(one, row), options);
        assertSame(first, second);
        assertEquals(2, ((Number) second.get("x")).intValue());
        assertEquals(0, Float.floatToRawIntBits((Float) second.get("z")));
        java.util.Map<String, Object> other = new java.util.LinkedHashMap<>();
        other.put("y", -4L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> third =
                (java.util.Map<String, Object>) Kiln.decode(two, encode(two, other), options);
        assertSame(first, third);
        assertEquals(-4L, ((Number) third.get("y")).longValue());
        assertFalse(third.containsKey("x"));
        assertFalse(third.containsKey("z"));
    }

    @Test
    void repeatedAsciiStringIsTheSameInstance() {
        Schema schema = Schema.struct("S", Schema.field("a", Schema.STR), Schema.field("b", Schema.STR));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("a", "hello");
        row.put("b", "héllo");
        byte[] raw = encode(schema, row);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> first = (java.util.Map<String, Object>) Kiln.decode(schema, raw);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> second = (java.util.Map<String, Object>) Kiln.decode(schema, raw);
        assertEquals("hello", second.get("a"));
        assertSame(first.get("a"), second.get("a"));
        assertEquals("héllo", second.get("b"));
    }

    private static byte[] encode(Schema schema, Object value) {
        byte[] dest = new byte[64];
        int n = Kiln.encodeTo(schema, value, dest, 0);
        return Arrays.copyOf(dest, n);
    }
}
