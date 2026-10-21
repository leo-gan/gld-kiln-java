package io.github.leogan.kiln;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Reused storage for numeric lists. Pass it with {@link Kiln.Options#withPool}.
 * Arrays and struct maps from one decode stay intact until the next decode
 * on this pool. The next decode overwrites them in place. Two lists of the
 * same width in one value get distinct arrays.
 */
public final class NumericPool {
    private final ArrayList<float[]> floats = new ArrayList<>();
    private final ArrayList<double[]> doubles = new ArrayList<>();
    private final ArrayList<long[]> longs = new ArrayList<>();
    private final ArrayList<int[]> ints = new ArrayList<>();
    private final ArrayList<LinkedHashMap<String, Object>> structs = new ArrayList<>();
    private final ArrayList<Schema> structSchemas = new ArrayList<>();
    private int floatUsed;
    private int doubleUsed;
    private int longUsed;
    private int intUsed;
    private int structUsed;
    private boolean structMatches;

    void reset() {
        floatUsed = 0;
        doubleUsed = 0;
        longUsed = 0;
        intUsed = 0;
        structUsed = 0;
    }

    LinkedHashMap<String, Object> takeStruct(Schema schema, int fields) {
        while (structs.size() <= structUsed) {
            structs.add(new LinkedHashMap<>(Math.max(4, fields * 2)));
            structSchemas.add(null);
        }
        structMatches = structSchemas.get(structUsed) == schema;
        if (!structMatches) {
            structSchemas.set(structUsed, schema);
        }
        return structs.get(structUsed++);
    }

    boolean structSame() {
        return structMatches;
    }

    float[] takeFloats(int n) {
        while (floats.size() <= floatUsed) {
            floats.add(null);
        }
        float[] cur = floats.get(floatUsed);
        if (cur == null || cur.length != n) {
            Object raw = Pod.allocate(float.class, n);
            cur = raw == null ? new float[n] : (float[]) raw;
            floats.set(floatUsed, cur);
        }
        floatUsed++;
        return cur;
    }

    double[] takeDoubles(int n) {
        while (doubles.size() <= doubleUsed) {
            doubles.add(null);
        }
        double[] cur = doubles.get(doubleUsed);
        if (cur == null || cur.length != n) {
            Object raw = Pod.allocate(double.class, n);
            cur = raw == null ? new double[n] : (double[]) raw;
            doubles.set(doubleUsed, cur);
        }
        doubleUsed++;
        return cur;
    }

    long[] takeLongs(int n) {
        while (longs.size() <= longUsed) {
            longs.add(null);
        }
        long[] cur = longs.get(longUsed);
        if (cur == null || cur.length != n) {
            Object raw = Pod.allocate(long.class, n);
            cur = raw == null ? new long[n] : (long[]) raw;
            longs.set(longUsed, cur);
        }
        longUsed++;
        return cur;
    }

    int[] takeInts(int n) {
        while (ints.size() <= intUsed) {
            ints.add(null);
        }
        int[] cur = ints.get(intUsed);
        if (cur == null || cur.length != n) {
            Object raw = Pod.allocate(int.class, n);
            cur = raw == null ? new int[n] : (int[]) raw;
            ints.set(intUsed, cur);
        }
        intUsed++;
        return cur;
    }
}
