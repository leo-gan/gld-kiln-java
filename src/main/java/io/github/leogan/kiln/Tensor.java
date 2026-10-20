package io.github.leogan.kiln;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One tensor in a version-2 directory. Payload bytes are a view when decoded. */
public final class Tensor {
    static final int OPAQUE_MIN = 4096;
    static final int OPAQUE_MAX = 32767;
    static final String[] DTYPE_NAMES = {
        null,
        "f64",
        "f32",
        "f16",
        "bf16",
        "f8e4m3",
        "f8e5m2",
        "bool",
        "i8",
        "u8",
        "i16",
        "u16",
        "i32",
        "u32",
        "i64",
        "u64",
        "i4",
        "u4",
        "e2m1",
        "e8m0"
    };

    final int dtype;
    final int rank;
    final long[] shape;
    final Bytes data;
    final Bytes name;
    final Bytes layout;
    final long blockSize;
    final int quantScheme;
    final int quantAxis;
    final long groupSize;
    final long scaleIndex;
    final long outerIndex;
    final String nameText;
    final String layoutText;

    Tensor(
            int dtype,
            int rank,
            long[] shape,
            Bytes data,
            Bytes name,
            Bytes layout,
            long blockSize,
            int quantScheme,
            int quantAxis,
            long groupSize,
            long scaleIndex,
            long outerIndex,
            String nameText,
            String layoutText) {
        this.dtype = dtype;
        this.rank = rank;
        this.shape = shape;
        this.data = data;
        this.name = name;
        this.layout = layout;
        this.blockSize = blockSize;
        this.quantScheme = quantScheme;
        this.quantAxis = quantAxis;
        this.groupSize = groupSize;
        this.scaleIndex = scaleIndex;
        this.outerIndex = outerIndex;
        this.nameText = nameText;
        this.layoutText = layoutText;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Tensor of(String dtype, long[] shape, byte[] data) {
        return builder().dtype(dtype).shape(shape).data(data).build();
    }

    public int dtype() {
        return dtype;
    }

    public int rank() {
        return rank;
    }

    public Bytes data() {
        return data;
    }

    public String name() {
        return nameText;
    }

    public String layout() {
        return layoutText;
    }

    public int quantScheme() {
        return quantScheme;
    }

    public long blockSize() {
        return blockSize;
    }

    public long[] shape() {
        long[] out = new long[rank];
        System.arraycopy(shape, 0, out, 0, rank);
        return out;
    }

    static int code(String name) {
        for (int i = 1; i < DTYPE_NAMES.length; i++) {
            if (DTYPE_NAMES[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("dtype " + name);
    }

    static int wholeWidth(int dtype) {
        return switch (dtype) {
            case 1, 14, 15 -> 8;
            case 2, 12, 13 -> 4;
            case 3, 4, 10, 11 -> 2;
            case 5, 6, 7, 8, 9, 19 -> 1;
            default -> 0;
        };
    }

    static boolean packed(int dtype) {
        return dtype >= 16 && dtype <= 18;
    }

    Map<String, Object> toDecoded() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", nameText);
        if (dtype >= 1 && dtype < DTYPE_NAMES.length) {
            row.put("dtype", DTYPE_NAMES[dtype]);
        } else {
            row.put("dtype", dtype);
        }
        List<Long> dims = new ArrayList<>(rank);
        for (int i = 0; i < rank; i++) {
            dims.add(shape[i]);
        }
        row.put("shape", dims);
        row.put("quant_scheme", quantScheme);
        row.put("data_hex", data.length() == 0 ? "" : Wire.hex(data.array(), data.offset(), data.length()));
        return row;
    }

    public static final class Builder {
        private int dtype;
        private long[] shape = new long[0];
        private byte[] data = new byte[0];
        private String name;
        private String layout;
        private int blockSize;
        private int quantScheme;
        private int quantAxis = 0xFFFF;
        private long groupSize;
        private long scaleIndex = 0xFFFFFFFFL;
        private long outerIndex = 0xFFFFFFFFL;

        public Builder dtype(int code) {
            this.dtype = code;
            return this;
        }

        public Builder dtype(String name) {
            this.dtype = code(name);
            return this;
        }

        public Builder shape(long... shape) {
            this.shape = shape == null ? new long[0] : shape.clone();
            return this;
        }

        public Builder data(byte[] data) {
            this.data = data == null ? new byte[0] : data;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder layout(String layout) {
            this.layout = layout;
            return this;
        }

        public Builder blockSize(int blockSize) {
            this.blockSize = blockSize;
            return this;
        }

        public Builder quant(int scheme, int axis, long groupSize, long scaleIndex, long outerIndex) {
            this.quantScheme = scheme;
            this.quantAxis = axis;
            this.groupSize = groupSize;
            this.scaleIndex = scaleIndex;
            this.outerIndex = outerIndex;
            return this;
        }

        public Tensor build() {
            if (shape.length > 8) {
                throw new IllegalArgumentException("rank");
            }
            long[] full = new long[8];
            System.arraycopy(shape, 0, full, 0, shape.length);
            Bytes nameBytes = name == null ? null : Bytes.copy(name.getBytes(StandardCharsets.UTF_8));
            Bytes layoutBytes = layout == null ? null : Bytes.copy(layout.getBytes(StandardCharsets.UTF_8));
            return new Tensor(
                    dtype,
                    shape.length,
                    full,
                    Bytes.copy(data),
                    nameBytes,
                    layoutBytes,
                    blockSize,
                    quantScheme,
                    quantAxis,
                    groupSize,
                    scaleIndex,
                    outerIndex,
                    name,
                    layout);
        }
    }
}
