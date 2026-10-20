package example;

import io.github.leogan.kiln.Bytes;
import io.github.leogan.kiln.Kiln;
import io.github.leogan.kiln.Schema;
import io.github.leogan.kiln.Tensor;
import io.github.leogan.kiln.TensorDirectory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encode a positional point and one f32 tensor. */
public final class Example {
    private Example() {}

    public static void main(String[] args) {
        Schema point = Schema.struct(
                "Point",
                Schema.field("x", Schema.I32),
                Schema.field("y", Schema.I32),
                Schema.field("label", Schema.opt(Schema.STR)));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("x", 3);
        value.put("y", -4);
        byte[] raw = Kiln.encode(point, value);
        byte[] framed = Kiln.encodeFramed(point, value);

        byte[] payload = new byte[] {0x00, 0x00, (byte) 0x80, 0x3f, 0x00, 0x00, 0x00, (byte) 0xc0};
        Tensor tensor = Tensor.of("f32", new long[] {2}, payload);
        byte[] block = Kiln.encodeTensors(List.of(tensor));
        TensorDirectory directory = Kiln.decodeTensors(block);
        Bytes data = directory.tensors().get(0).data();
        System.out.println("point " + raw.length + " framed " + framed.length + " payload " + data.length());
    }
}
