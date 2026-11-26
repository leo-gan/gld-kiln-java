package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CursorTest {
    @Test
    void outAndInMatchSchemaWireFormat() {
        Schema sch =
                Schema.struct(
                        "Row",
                        Schema.field("b", Schema.BOOL),
                        Schema.field("i32", Schema.I32),
                        Schema.field("i64", Schema.I64),
                        Schema.field("f64", Schema.F64),
                        Schema.field("s", Schema.STR),
                        Schema.field("u", Schema.STR),
                        Schema.field("vals", Schema.list(Schema.F64)));

        double[] vals = new double[] {1.5, -0.0, 42.25};
        String ascii = "0123456789abcdef-ascii";
        String utf8 = "héllo-🚀-kiln";

        Wire.Out out = new Wire.Out(128);
        out.bool(true);
        out.i32(-7);
        out.i64(9_000_000_000L);
        out.f64(-12.5);
        out.str(ascii);
        out.str(utf8);
        out.f64s(vals);
        byte[] wire = out.finish();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("b", true);
        row.put("i32", -7);
        row.put("i64", 9_000_000_000L);
        row.put("f64", -12.5);
        row.put("s", ascii);
        row.put("u", utf8);
        row.put("vals", vals);
        assertArrayEquals(Kiln.encode(sch, row), wire);

        Wire.In in = Wire.In.of(wire);
        assertTrue(in.bool());
        assertEquals(-7, in.i32());
        assertEquals(9_000_000_000L, in.i64());
        assertEquals(-12.5, in.f64());
        assertEquals(ascii, in.str());
        assertEquals(utf8, in.str());
        double[] back = Wire.readF64s(in, null);
        in.finish();
        assertEquals(3, back.length);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back[1]));

        byte[] badUtf8 = new byte[] {2, 0, 0, 0, (byte) 0xFF, (byte) 0xFE};
        assertThrows(KilnException.class, () -> Wire.In.of(badUtf8).str());
    }

    @Test
    void utf8SwarChecksAllOffsets() {
        for (int len = 0; len <= 40; len++) {
            byte[] buf = new byte[len];
            for (int i = 0; i < len; i++) {
                buf[i] = 'a';
            }
            assertTrue(Wire.isUtf8(buf, 0, len));
            for (int bad = 0; bad < len; bad++) {
                buf[bad] = (byte) 0xFF;
                assertFalse(Wire.isUtf8(buf, 0, len));
                buf[bad] = 'a';
            }
        }
    }
}
