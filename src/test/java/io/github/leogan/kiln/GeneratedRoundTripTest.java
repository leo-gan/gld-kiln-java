package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.leogan.kiln.generated.Message;
import io.github.leogan.kiln.generated.Point;
import io.github.leogan.kiln.generated.Sample;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class GeneratedRoundTripTest {
    @Test
    void pointMatchesDynamicSchema() throws Exception {
        Schema schema = JsonSchema.parse(KilnV2CasesTest.repoRoot().resolve("testdata/schema/point.json"));
        Point point = new Point();
        point.x = 4;
        point.y = -5;
        point.label = null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("x", 4);
        value.put("y", -5);
        assertArrayEquals(Kiln.encode(schema, value), point.encode());
        Point back = Point.decode(point.encode());
        assertEquals(4, back.x);
        assertEquals(-5, back.y);
        assertNull(back.label);
        assertEquals(point.encodedSize(), point.encode().length);

        point.label = "n";
        value.put("label", "n");
        byte[] raw = point.encode();
        assertArrayEquals(Kiln.encode(schema, value), raw);
        assertEquals("n", Point.decode(raw).label);
        String source = Files.readString(KilnV2CasesTest.repoRoot().resolve("tests/generated/Point.java"));
        assertTrue(source.contains("encodedSize"));
        assertTrue(source.contains("encode"));
        assertTrue(source.contains("decode"));
        assertTrue(!source.contains("tensors"));
    }

    @Test
    void messageNestedStruct() throws Exception {
        Schema schema = JsonSchema.parse(KilnV2CasesTest.repoRoot().resolve("testdata/schema/message.json"));
        Message message = new Message();
        message.f_bool = true;
        message.f_int32 = -1;
        message.f_int64 = 99L;
        message.f_float64 = -0.0;
        message.f_string = "héllo";
        message.f_bool_2 = false;
        message.f_int32_2 = 2;
        message.f_string_2 = "";
        message.meta = new io.github.leogan.kiln.generated.Meta();
        message.meta.region = "eu";
        message.meta.flag = true;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("region", "eu");
        meta.put("flag", true);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("f_bool", true);
        value.put("f_int32", -1);
        value.put("f_int64", 99L);
        value.put("f_float64", -0.0);
        value.put("f_string", "héllo");
        value.put("f_bool_2", false);
        value.put("f_int32_2", 2);
        value.put("f_string_2", "");
        value.put("meta", meta);
        byte[] raw = message.encode();
        assertArrayEquals(Kiln.encode(schema, value), raw);
        Message back = Message.decode(raw);
        assertEquals("eu", back.meta.region);
        assertTrue(back.meta.flag);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back.f_float64));
        assertEquals(raw.length, message.encodedSize());
    }

    @Test
    void sampleListsDictAndOptional() throws Exception {
        Schema schema = JsonSchema.parse(KilnV2CasesTest.repoRoot().resolve("testdata/schema/sample.json"));
        Sample sample = new Sample();
        sample.name = "s";
        sample.blob = new byte[] {1, 2, 3};
        sample.score = -8;
        sample.wide = -0.0f;
        sample.tags = List.of("b", "a");
        sample.bytes = new byte[] {0, (byte) 255};
        sample.attrs = new LinkedHashMap<>();
        sample.attrs.put("b", 1);
        sample.attrs.put("a", 2);
        sample.note = null;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("b", 1);
        attrs.put("a", 2);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "s");
        value.put("blob", new byte[] {1, 2, 3});
        value.put("score", -8);
        value.put("wide", -0.0f);
        value.put("tags", List.of("b", "a"));
        value.put("bytes", new byte[] {0, (byte) 255});
        value.put("attrs", attrs);
        byte[] raw = sample.encode();
        assertArrayEquals(Kiln.encode(schema, value), raw);
        Sample back = Sample.decode(raw);
        assertEquals(List.of("b", "a"), back.tags);
        assertEquals(2, back.attrs.get("a"));
        assertNull(back.note);
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(back.wide));
        sample.note = "z";
        value.put("note", "z");
        assertArrayEquals(Kiln.encode(schema, value), sample.encode());
    }
}
