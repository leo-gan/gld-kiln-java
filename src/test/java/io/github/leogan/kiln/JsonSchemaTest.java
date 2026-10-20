package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonSchemaTest {
    @Test
    void pointAndMessageShapes() throws Exception {
        var root = KilnV2CasesTest.repoRoot();
        Schema point = JsonSchema.parse(root.resolve("testdata/schema/point.json"));
        assertEquals("Point", point.name);
        assertEquals(Schema.I32, point.fields().get(0).type);
        assertEquals(Schema.I32, point.fields().get(1).type);
        assertEquals(Schema.Kind.OPT, point.fields().get(2).type.kind);
        assertEquals(Schema.STR, point.fields().get(2).type.elem);
        assertEquals("label", point.fields().get(2).name);

        Schema message = JsonSchema.parse(root.resolve("testdata/schema/message.json"));
        assertEquals("Message", message.name);
        Schema meta = message.fields().get(8).type;
        assertEquals(Schema.Kind.STRUCT, meta.kind);
        assertEquals("Meta", meta.name);
        assertEquals(Schema.F64, message.fields().get(3).type);
        assertEquals(Schema.STR, meta.fields().get(0).type);
        assertEquals(Schema.BOOL, meta.fields().get(1).type);
    }

    @Test
    void subsetRoundTripAndNoTensors() {
        String json = """
                {
                  "title": "Bag",
                  "type": "object",
                  "properties": {
                    "n": {"type": "integer", "format": "uint64"},
                    "items": {"type": "array", "items": {"type": "integer", "format": "int16"}},
                    "flag": {"type": "boolean"}
                  },
                  "required": ["n", "items"]
                }
                """;
        Schema bag = JsonSchema.parse(json);
        for (Schema.Field field : bag.fields()) {
            assertNotTensors(field.type);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("n", Long.parseUnsignedLong("18446744073709551615"));
        value.put("items", List.of((short) -2, (short) 3));
        byte[] raw = Kiln.encode(bag, value);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Kiln.decode(bag, raw);
        assertNull(back.get("flag"));
        assertArrayEquals(raw, Kiln.encode(bag, back));
        assertEquals(new String(raw, StandardCharsets.ISO_8859_1).length(), raw.length);
    }

    private static void assertNotTensors(Schema schema) {
        if (schema.kind == Schema.Kind.TENSORS) {
            throw new AssertionError("tensors");
        }
        if (schema.elem != null) {
            assertNotTensors(schema.elem);
        }
        if (schema.kind == Schema.Kind.STRUCT) {
            for (Schema.Field field : schema.fields()) {
                assertNotTensors(field.type);
            }
        }
    }
}
