package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every record in testdata/kiln-v2-cases.json, plus the Frozen oracle in kiln_ref.py. */
public class KilnV2CasesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema FRAME_BODY = Schema.struct(
            "Framed",
            Schema.field("id", Schema.I64),
            Schema.field("batch", Schema.TENSORS));

    @Test
    void fortySixCasesRoundTripOrReject() throws Exception {
        JsonNode root = JSON.readTree(repoRoot().resolve("testdata/kiln-v2-cases.json").toFile());
        JsonNode cases = root.get("cases");
        assertEquals(46, cases.size());
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            byte[] raw = Wire.parseHex(c.get("input").asText());
            boolean frame = c.get("schema").isTextual() && "frame".equals(c.get("schema").asText());
            Schema schema = frame ? FRAME_BODY : schemaOf(c.get("schema"));
            String expect = c.get("expect").asText();
            try {
                Object value;
                byte[] again;
                if (frame) {
                    Kiln.Framed framed = Kiln.decodeFramed(schema, raw);
                    Map<String, Object> shown = new LinkedHashMap<>();
                    shown.put("hash", new BigInteger(Long.toUnsignedString(framed.hash())));
                    Map<?, ?> body = (Map<?, ?>) framed.value();
                    shown.put("id", body.get("id"));
                    shown.put("batch", body.get("batch"));
                    value = shown;
                    again = Kiln.encodeFramed(schema, framed.value());
                } else {
                    value = Kiln.decode(schema, raw);
                    again = Kiln.encode(schema, value);
                }
                if (!"accept".equals(expect)) {
                    fail(id + " decoded but expected " + c.get("error").asText());
                }
                if (c.has("decoded")) {
                    JsonNode actual = toNode(value);
                    assertTrue(jsonEquals(c.get("decoded"), actual), id + "\nexpected " + c.get("decoded") + "\nactual " + actual);
                }
                assertEquals(c.get("input").asText().toLowerCase(), Wire.hex(again, 0, again.length), id);
            } catch (KilnException ex) {
                if ("accept".equals(expect)) {
                    fail(id + " rejected " + ex.code());
                }
                assertEquals(c.get("error").asText(), ex.code(), id);
            }
        }
    }

    @Test
    void hostileStructListFailsBeforeAllocation() {
        byte[] raw = new byte[4];
        Wire.putI32(raw, 0, 1_000_000_000);
        Schema schema = Schema.list(Schema.struct("Row", Schema.field("a", Schema.I64)));
        long start = System.nanoTime();
        KilnException ex = org.junit.jupiter.api.Assertions.assertThrows(
                KilnException.class, () -> Kiln.decode(schema, raw, Kiln.Options.defaults().withCap(1_000_000_000)));
        assertEquals(KilnException.EOF, ex.code());
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "decode allocated or ran too long");
    }

    @Test
    void utf8RejectsIllFormedStrings() {
        assertTrue(Wire.isUtf8("héllo".getBytes(StandardCharsets.UTF_8), 0, "héllo".getBytes(StandardCharsets.UTF_8).length));
        assertTrue(!Wire.isUtf8(new byte[] {(byte) 0xFF}, 0, 1));
        assertTrue(!Wire.isUtf8(new byte[] {(byte) 0xC0, (byte) 0x80}, 0, 2));
        assertTrue(!Wire.isUtf8(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 0, 3));
        byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8);
        assertTrue(Wire.isUtf8(emoji, 0, emoji.length));
    }

    @Test
    void pointLittleEndian() {
        Schema point = Schema.struct("Point", Schema.field("x", Schema.I32), Schema.field("y", Schema.I32));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("x", 1);
        value.put("y", -2);
        assertEquals("01000000feffffff", Wire.hex(Kiln.encode(point, value), 0, 8));
    }

    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("testdata/kiln-v2-cases.json"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("testdata/kiln-v2-cases.json not found");
        }
        return dir;
    }

    static Schema schemaOf(JsonNode node) {
        if (node.isTextual()) {
            return switch (node.asText()) {
                case "tensor-v2" -> Schema.TENSORS;
                case "frame" -> FRAME_BODY;
                default -> throw new IllegalArgumentException(node.asText());
            };
        }
        String type = node.get("type").asText();
        if ("list".equals(type)) {
            return Schema.list(parseExpr(node.get("element").asText()));
        }
        if ("struct".equals(type)) {
            List<Schema.Field> fields = new ArrayList<>();
            for (JsonNode field : node.get("fields")) {
                String text = field.asText();
                int colon = text.indexOf(':');
                fields.add(Schema.field(text.substring(0, colon), parseExpr(text.substring(colon + 1))));
            }
            return Schema.struct("Case", fields);
        }
        throw new IllegalArgumentException(type);
    }

    static Schema parseExpr(String expr) {
        if (expr.startsWith("struct{") && expr.endsWith("}")) {
            String body = expr.substring("struct{".length(), expr.length() - 1);
            List<Schema.Field> fields = new ArrayList<>();
            if (!body.isEmpty()) {
                for (String part : body.split(",")) {
                    int colon = part.indexOf(':');
                    fields.add(Schema.field(part.substring(0, colon), Schema.named(part.substring(colon + 1))));
                }
            }
            return Schema.struct("Anon", fields);
        }
        return Schema.named(expr);
    }

    static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            if (a.isFloatingPointNumber() || b.isFloatingPointNumber()) {
                return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
            }
            return a.bigIntegerValue().equals(b.bigIntegerValue());
        }
        if (a.isNull() || b.isNull()) {
            return a.isNull() && b.isNull();
        }
        if (a.isArray()) {
            if (!b.isArray() || a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject()) {
            if (!b.isObject() || a.size() != b.size()) {
                return false;
            }
            var names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!b.has(name) || !jsonEquals(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    static JsonNode toNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof TensorDirectory dir) {
            return toNode(dir.toDecoded());
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = JSON.createObjectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                node.set(String.valueOf(entry.getKey()), toNode(entry.getValue()));
            }
            return node;
        }
        if (value instanceof List<?> list) {
            ArrayNode node = JSON.createArrayNode();
            for (Object item : list) {
                node.add(toNode(item));
            }
            return node;
        }
        if (value instanceof float[] nums) {
            ArrayNode node = JSON.createArrayNode();
            for (float num : nums) {
                node.add(num);
            }
            return node;
        }
        if (value instanceof double[] nums) {
            ArrayNode node = JSON.createArrayNode();
            for (double num : nums) {
                node.add(num);
            }
            return node;
        }
        if (value instanceof int[] nums) {
            ArrayNode node = JSON.createArrayNode();
            for (int num : nums) {
                node.add(num);
            }
            return node;
        }
        if (value instanceof long[] nums) {
            ArrayNode node = JSON.createArrayNode();
            for (long num : nums) {
                node.add(num);
            }
            return node;
        }
        if (value instanceof boolean[] nums) {
            ArrayNode node = JSON.createArrayNode();
            for (boolean num : nums) {
                node.add(num);
            }
            return node;
        }
        if (value instanceof Boolean b) {
            return BooleanNode.valueOf(b);
        }
        if (value instanceof String s) {
            return TextNode.valueOf(s);
        }
        if (value instanceof Integer i) {
            return IntNode.valueOf(i);
        }
        if (value instanceof Long l) {
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return IntNode.valueOf(l.intValue());
            }
            return LongNode.valueOf(l);
        }
        if (value instanceof Float f) {
            return DoubleNode.valueOf(f.doubleValue());
        }
        if (value instanceof Double d) {
            return DoubleNode.valueOf(d);
        }
        if (value instanceof BigInteger bi) {
            return BigIntegerNode.valueOf(bi);
        }
        throw new IllegalArgumentException(value.getClass().getName());
    }
}
