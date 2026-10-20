package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Point, Message, lists, opts, dicts, and frames checked against testdata/reference/kiln_ref.py. */
public class FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Schema POINT = Schema.struct(
            "Point",
            Schema.field("x", Schema.I32),
            Schema.field("y", Schema.I32));
    private static final Schema MESSAGE = Schema.struct(
            "Message",
            Schema.field("f_bool", Schema.BOOL),
            Schema.field("f_int32", Schema.I32),
            Schema.field("f_int64", Schema.I64),
            Schema.field("f_float64", Schema.F64),
            Schema.field("f_string", Schema.STR),
            Schema.field("f_bool_2", Schema.BOOL),
            Schema.field("f_int32_2", Schema.I32),
            Schema.field("f_string_2", Schema.STR));

    @Test
    void matchesKilnRef() throws Exception {
        Path ref = KilnV2CasesTest.repoRoot().resolve("testdata/reference/kiln_ref.py");
        Process process = new ProcessBuilder("python3", "-c", ORACLE, ref.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, output);
        JsonNode oracle = JSON.readTree(output);

        assertEquals(oracle.get("point_hash").asText(), Long.toUnsignedString(POINT.typeHash()));
        assertEquals(oracle.get("message_hash").asText(), Long.toUnsignedString(MESSAGE.typeHash()));

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", 3);
        point.put("y", -4);
        check(POINT, point, oracle.get("point").asText());
        assertEquals(oracle.get("point_frame").asText(), hex(Kiln.encodeFramed(POINT, point)));

        double nan = Double.longBitsToDouble(0x7FF8000000000001L);
        check(MESSAGE, message(nan, "héllo"), oracle.get("message_nan").asText());
        check(MESSAGE, message(-0.0, ""), oracle.get("message_negzero").asText());
        check(Schema.F32, -0.0f, oracle.get("f32_negzero").asText());
        check(Schema.F64, nan, oracle.get("f64_nan").asText());
        check(Schema.list(Schema.I32), List.of(1, 2, 3), oracle.get("list_i32").asText());
        check(Schema.list(Schema.U8), new byte[] {0, (byte) 0xFF}, oracle.get("list_u8").asText());
        check(Schema.list(Schema.I8), new byte[] {1, (byte) 0xFF}, oracle.get("list_i8").asText());
        check(Schema.opt(Schema.STR), null, oracle.get("opt_none").asText());
        check(Schema.opt(Schema.STR), "a", oracle.get("opt_some").asText());
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("b", 1);
        dict.put("a", 2);
        check(Schema.dict(Schema.I32), dict, oracle.get("dict").asText());
    }

    private static Map<String, Object> message(double f64, String text) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("f_bool", true);
        value.put("f_int32", -7);
        value.put("f_int64", -9L);
        value.put("f_float64", f64);
        value.put("f_string", text);
        value.put("f_bool_2", false);
        value.put("f_int32_2", 0);
        value.put("f_string_2", "");
        return value;
    }

    private static void check(Schema schema, Object value, String expectedHex) {
        byte[] encoded = Kiln.encode(schema, value);
        assertEquals(expectedHex, hex(encoded));
        Object decoded = Kiln.decode(schema, encoded);
        assertArrayEquals(encoded, Kiln.encode(schema, decoded));
    }

    private static String hex(byte[] raw) {
        return Wire.hex(raw, 0, raw.length);
    }

    private static final String ORACLE = """
            import importlib.util, json, struct, sys
            spec = importlib.util.spec_from_file_location("kiln_ref", sys.argv[1])
            m = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(m)
            nan = struct.unpack("<d", struct.pack("<Q", 0x7FF8000000000001))[0]
            msg = {
                "f_bool": True,
                "f_int32": -7,
                "f_int64": -9,
                "f_float64": nan,
                "f_string": "héllo",
                "f_bool_2": False,
                "f_int32_2": 0,
                "f_string_2": "",
            }
            neg = dict(msg)
            neg["f_float64"] = -0.0
            neg["f_string"] = ""
            out = {
                "point_hash": str(m.type_hash(m.POINT)),
                "message_hash": str(m.type_hash(m.MESSAGE)),
                "point": m.encode(m.POINT, {"x": 3, "y": -4}).hex(),
                "point_frame": m.encode_framed(m.POINT, {"x": 3, "y": -4}).hex(),
                "message_nan": m.encode(m.MESSAGE, msg).hex(),
                "message_negzero": m.encode(m.MESSAGE, neg).hex(),
                "f32_negzero": m.encode(("f32",), -0.0).hex(),
                "f64_nan": m.encode(("f64",), nan).hex(),
                "list_i32": m.encode(("list", ("i32",)), [1, 2, 3]).hex(),
                "list_u8": m.encode(("list", ("u8",)), bytes([0, 255])).hex(),
                "list_i8": m.encode(("list", ("i8",)), [1, -1]).hex(),
                "opt_none": m.encode(("opt", ("str",)), None).hex(),
                "opt_some": m.encode(("opt", ("str",)), "a").hex(),
                "dict": m.encode(("dict", ("i32",)), {"b": 1, "a": 2}).hex(),
            }
            print(json.dumps(out))
            """;
}
