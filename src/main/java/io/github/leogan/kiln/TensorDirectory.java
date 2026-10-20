package io.github.leogan.kiln;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One version-2 tensor directory block. Payload views alias the input buffer. */
public final class TensorDirectory {
    public final int blockLength;
    public final boolean names;
    public final List<Tensor> tensors;

    TensorDirectory(int blockLength, boolean names, List<Tensor> tensors) {
        this.blockLength = blockLength;
        this.names = names;
        this.tensors = tensors;
    }

    public List<Tensor> tensors() {
        return tensors;
    }

    public int blockLength() {
        return blockLength;
    }

    public Map<String, Object> toDecoded() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 2);
        List<String> flags = new ArrayList<>(2);
        if (names) {
            flags.add("names");
        }
        flags.add("le");
        out.put("flags", flags);
        List<Map<String, Object>> rows = new ArrayList<>(tensors.size());
        for (Tensor tensor : tensors) {
            rows.add(tensor.toDecoded());
        }
        out.put("tensors", rows);
        out.put("block_length", blockLength);
        return out;
    }
}
