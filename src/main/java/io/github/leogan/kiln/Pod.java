package io.github.leogan.kiln;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import sun.misc.Unsafe;

/**
 * One memcpy of a numeric list. The wire is little-endian, which matches the
 * JVM array layout on a little-endian host. A big-endian host keeps the
 * scalar loop. Fresh arrays skip the JVM's zero-fill when the runtime allows
 * {@code allocateUninitializedArray}.
 */
final class Pod {
    static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    static final Unsafe UNSAFE = unsafe();
    static final long BYTE = Unsafe.ARRAY_BYTE_BASE_OFFSET;
    static final long FLOAT = Unsafe.ARRAY_FLOAT_BASE_OFFSET;
    static final long DOUBLE = Unsafe.ARRAY_DOUBLE_BASE_OFFSET;
    static final long LONG = Unsafe.ARRAY_LONG_BASE_OFFSET;
    static final long INT = Unsafe.ARRAY_INT_BASE_OFFSET;
    static final int BOOL_SCALE = Unsafe.ARRAY_BOOLEAN_INDEX_SCALE;
    private static final MethodHandle RAW_ARRAY = rawArray();
    private static final ThreadLocal<NumericPool> POOL = new ThreadLocal<>();

    private Pod() {}

    static void toWire(Object src, long srcOff, byte[] dest, int destAt, long bytes) {
        copy(src, srcOff, dest, BYTE + destAt, bytes);
    }

    static void fromWire(byte[] src, int srcAt, Object dest, long destOff, long bytes) {
        copy(src, BYTE + srcAt, dest, destOff, bytes);
    }

    /**
     * Power-of-two lengths used by the square tensor and the shard and prompt
     * blocks. A constant byte count stays on the fast platform copy; every
     * other length uses the variable-length copy.
     */
    static void copy(Object src, long srcOff, Object dest, long destOff, long bytes) {
        if (bytes == 4_194_304L) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, 4_194_304L);
        } else if (bytes == 1_048_576L) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, 1_048_576L);
        } else if (bytes == 524_288L) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, 524_288L);
        } else if (bytes == 262_144L) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, 262_144L);
        } else if (bytes == 8_192L) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, 8_192L);
        } else if (bytes > 0) {
            UNSAFE.copyMemory(src, srcOff, dest, destOff, bytes);
        }
    }

    static LinkedHashMap<String, Object> structMap(Schema schema, int fields) {
        NumericPool pool = POOL.get();
        if (pool == null) {
            return new LinkedHashMap<>(Math.max(4, fields * 2));
        }
        return pool.takeStruct(schema, fields);
    }

    /**
     * True when the struct map just taken on this thread was last filled for the same schema.
     * Call it before a nested struct decode overwrites the flag.
     */
    static boolean structSame() {
        NumericPool pool = POOL.get();
        return pool != null && pool.structSame();
    }

    static NumericPool swap(NumericPool pool) {
        NumericPool previous = POOL.get();
        if (pool == null) {
            POOL.remove();
        } else {
            POOL.set(pool);
        }
        return previous;
    }

    static float[] floats(int n) {
        NumericPool pool = POOL.get();
        if (pool != null) {
            return pool.takeFloats(n);
        }
        Object raw = allocate(float.class, n);
        return raw == null ? new float[n] : (float[]) raw;
    }

    static double[] doubles(int n) {
        NumericPool pool = POOL.get();
        if (pool != null) {
            return pool.takeDoubles(n);
        }
        Object raw = allocate(double.class, n);
        return raw == null ? new double[n] : (double[]) raw;
    }

    static long[] longs(int n) {
        NumericPool pool = POOL.get();
        if (pool != null) {
            return pool.takeLongs(n);
        }
        Object raw = allocate(long.class, n);
        return raw == null ? new long[n] : (long[]) raw;
    }

    static int[] ints(int n) {
        NumericPool pool = POOL.get();
        if (pool != null) {
            return pool.takeInts(n);
        }
        Object raw = allocate(int.class, n);
        return raw == null ? new int[n] : (int[]) raw;
    }

    static Object allocate(Class<?> type, int n) {
        if (n == 0 || RAW_ARRAY == null) {
            return null;
        }
        try {
            return RAW_ARRAY.invoke(type, n);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static Unsafe unsafe() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static MethodHandle rawArray() {
        try {
            Class<?> type = Class.forName("jdk.internal.misc.Unsafe");
            Object unsafe = type.getMethod("getUnsafe").invoke(null);
            return MethodHandles.lookup()
                    .findVirtual(type, "allocateUninitializedArray", MethodType.methodType(Object.class, Class.class, int.class))
                    .bindTo(unsafe);
        } catch (Throwable ex) {
            return null;
        }
    }
}
