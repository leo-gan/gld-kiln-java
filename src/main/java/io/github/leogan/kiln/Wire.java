package io.github.leogan.kiln;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Little-endian stores and a cursor. Generated code and the dynamic codec share this. */
public final class Wire {
    public static final int DEFAULT_CAP = 16_777_216;
    public static final long FNV_OFFSET = 0xCBF29CE484222325L;
    public static final long FNV_PRIME = 0x100000001B3L;
    static final long MASK = -1L;

    private Wire() {}

    public static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n++;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** Unsigned byte order of the UTF-8 encoding, not Java {@code char} order. */
    public static int compareUtf8(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(ab.length, bb.length);
        for (int i = 0; i < n; i++) {
            int d = (ab[i] & 0xFF) - (bb[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return ab.length - bb.length;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isUtf8(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c <= 0x7F) {
                i++;
                continue;
            }
            int need;
            int min;
            int maxSecond;
            if (c >= 0xC2 && c <= 0xDF) {
                need = 2;
                min = 0x80;
                maxSecond = 0xBF;
            } else if (c == 0xE0) {
                need = 3;
                min = 0xA0;
                maxSecond = 0xBF;
            } else if (c >= 0xE1 && c <= 0xEC) {
                need = 3;
                min = 0x80;
                maxSecond = 0xBF;
            } else if (c == 0xED) {
                need = 3;
                min = 0x80;
                maxSecond = 0x9F;
            } else if (c == 0xEE || c == 0xEF) {
                need = 3;
                min = 0x80;
                maxSecond = 0xBF;
            } else if (c == 0xF0) {
                need = 4;
                min = 0x90;
                maxSecond = 0xBF;
            } else if (c >= 0xF1 && c <= 0xF3) {
                need = 4;
                min = 0x80;
                maxSecond = 0xBF;
            } else if (c == 0xF4) {
                need = 4;
                min = 0x80;
                maxSecond = 0x8F;
            } else {
                return false;
            }
            if (i + need > end) {
                return false;
            }
            int s1 = b[i + 1] & 0xFF;
            if (s1 < min || s1 > maxSecond) {
                return false;
            }
            for (int k = 2; k < need; k++) {
                int sk = b[i + k] & 0xFF;
                if (sk < 0x80 || sk > 0xBF) {
                    return false;
                }
            }
            i += need;
        }
        return true;
    }

    public static void putI8(byte[] a, int i, int v) {
        a[i] = (byte) v;
    }

    public static void putI16(byte[] a, int i, int v) {
        a[i] = (byte) v;
        a[i + 1] = (byte) (v >>> 8);
    }

    public static void putI32(byte[] a, int i, int v) {
        a[i] = (byte) v;
        a[i + 1] = (byte) (v >>> 8);
        a[i + 2] = (byte) (v >>> 16);
        a[i + 3] = (byte) (v >>> 24);
    }

    public static void putI64(byte[] a, int i, long v) {
        putI32(a, i, (int) v);
        putI32(a, i + 4, (int) (v >>> 32));
    }

    public static int getI8(byte[] a, int i) {
        return a[i];
    }

    public static int getU8(byte[] a, int i) {
        return a[i] & 0xFF;
    }

    public static int getI16(byte[] a, int i) {
        return (short) ((a[i] & 0xFF) | (a[i + 1] << 8));
    }

    public static int getU16(byte[] a, int i) {
        return (a[i] & 0xFF) | ((a[i + 1] & 0xFF) << 8);
    }

    public static int getI32(byte[] a, int i) {
        return (a[i] & 0xFF)
                | ((a[i + 1] & 0xFF) << 8)
                | ((a[i + 2] & 0xFF) << 16)
                | (a[i + 3] << 24);
    }

    public static long getU32(byte[] a, int i) {
        return getI32(a, i) & 0xFFFFFFFFL;
    }

    public static long getI64(byte[] a, int i) {
        return (getU32(a, i)) | (getI32(a, i + 4) & 0xFFFFFFFFL) << 32;
    }

    public static int putBoolAt(byte[] a, int i, boolean v) {
        a[i] = (byte) (v ? 1 : 0);
        return i + 1;
    }

    public static int putI32At(byte[] a, int i, int v) {
        putI32(a, i, v);
        return i + 4;
    }

    public static int putI64At(byte[] a, int i, long v) {
        putI64(a, i, v);
        return i + 8;
    }

    public static int putF32At(byte[] a, int i, float v) {
        putI32(a, i, Float.floatToRawIntBits(v));
        return i + 4;
    }

    public static int putF64At(byte[] a, int i, double v) {
        putI64(a, i, Double.doubleToRawLongBits(v));
        return i + 8;
    }

    public static int putU32At(byte[] a, int i, long v) {
        putI32(a, i, (int) v);
        return i + 4;
    }

    public static int putStringAt(byte[] a, int i, String s, int cap) {
        byte[] raw = utf8(s);
        if (raw.length > cap) {
            throw KilnException.limit();
        }
        putI32(a, i, raw.length);
        System.arraycopy(raw, 0, a, i + 4, raw.length);
        return i + 4 + raw.length;
    }

    public static int putRawAt(byte[] a, int i, byte[] raw, int off, int len, int cap) {
        if (Integer.compareUnsigned(len, cap) > 0) {
            throw KilnException.limit();
        }
        putI32(a, i, len);
        System.arraycopy(raw, off, a, i + 4, len);
        return i + 4 + len;
    }

    public static long add(long a, long b) {
        long sum = a + b;
        if (Long.compareUnsigned(sum, a) < 0) {
            throw KilnException.limit();
        }
        return sum;
    }

    public static long mul(long a, long b) {
        if (a != 0 && Long.compareUnsigned(b, Long.divideUnsigned(MASK, a)) > 0) {
            throw KilnException.limit();
        }
        return a * b;
    }

    public static long align64(long n) {
        return (n + 63L) & ~63L;
    }

    public static String hex(byte[] data, int off, int len) {
        char[] out = new char[len * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < len; i++) {
            int v = data[off + i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0xF];
        }
        return new String(out);
    }

    public static byte[] parseHex(String hex) {
        String s = hex.trim();
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Cursor over an input slice. Checks run before any element storage is allocated. */
    public static final class In {
        public final byte[] b;
        public int p;
        public final int end;
        public final int cap;
        public final boolean trusted;

        public In(byte[] b, int p, int end, int cap, boolean trusted) {
            this.b = b;
            this.p = p;
            this.end = end;
            this.cap = cap;
            this.trusted = trusted;
        }

        public static In of(byte[] data) {
            return new In(data, 0, data.length, DEFAULT_CAP, false);
        }

        public static In of(byte[] data, int off, int len) {
            return new In(data, off, off + len, DEFAULT_CAP, false);
        }

        public int remaining() {
            return end - p;
        }

        public void need(int n) {
            if (n < 0 || remaining() < n) {
                throw KilnException.eof();
            }
        }

        public int u8() {
            need(1);
            return b[p++] & 0xFF;
        }

        public int i8() {
            need(1);
            return b[p++];
        }

        public int i16() {
            need(2);
            int v = getI16(b, p);
            p += 2;
            return v;
        }

        public int u16() {
            need(2);
            int v = getU16(b, p);
            p += 2;
            return v;
        }

        public int i32() {
            need(4);
            int v = getI32(b, p);
            p += 4;
            return v;
        }

        public long u32() {
            need(4);
            long v = getU32(b, p);
            p += 4;
            return v;
        }

        public long i64() {
            need(8);
            long v = getI64(b, p);
            p += 8;
            return v;
        }

        public float f32() {
            return Float.intBitsToFloat(i32());
        }

        public double f64() {
            return Double.longBitsToDouble(i64());
        }

        public boolean bool() {
            int v = u8();
            if (v > 1) {
                throw KilnException.type();
            }
            return v == 1;
        }

        public boolean opt() {
            int v = u8();
            if (v > 1) {
                throw KilnException.type();
            }
            return v == 1;
        }

        public String str() {
            long n = u32();
            if (n > cap) {
                throw KilnException.limit();
            }
            if (n > remaining()) {
                throw KilnException.eof();
            }
            int len = (int) n;
            int off = p;
            p += len;
            if (!isUtf8(b, off, len)) {
                if (!trusted) {
                    throw KilnException.utf8();
                }
                return new String(b, off, len, StandardCharsets.ISO_8859_1);
            }
            return new String(b, off, len, StandardCharsets.UTF_8);
        }

        public Bytes bytes() {
            long n = u32();
            if (n > cap) {
                throw KilnException.limit();
            }
            if (n > remaining()) {
                throw KilnException.eof();
            }
            int len = (int) n;
            Bytes view = Bytes.view(b, p, len);
            p += len;
            return view;
        }

        /** Count check shared by lists and dicts. Returns the count. Does not allocate. */
        public int count(int eachMin) {
            long count = u32();
            if (count > cap) {
                throw KilnException.limit();
            }
            if (eachMin != 0 && Long.compareUnsigned(count, Long.divideUnsigned(MASK, eachMin)) > 0) {
                throw KilnException.limit();
            }
            long need = count * (long) eachMin;
            if (Long.compareUnsigned(need, remaining()) > 0) {
                throw KilnException.eof();
            }
            return (int) count;
        }

        public void finish() {
            if (p != end) {
                throw KilnException.trailing();
            }
        }
    }

    public static final class Out {
        private byte[] a;
        private int n;

        public Out(int capacity) {
            a = new byte[Math.max(capacity, 16)];
        }

        public static Out exact(int size) {
            Out out = new Out(0);
            out.a = new byte[size];
            return out;
        }

        public int size() {
            return n;
        }

        public void reset() {
            n = 0;
        }

        public void need(int k) {
            if (n > a.length - k) {
                a = Arrays.copyOf(a, Math.max(a.length << 1, n + k));
            }
        }

        public void u8(int v) {
            need(1);
            a[n++] = (byte) v;
        }

        public void i32(int v) {
            need(4);
            putI32(a, n, v);
            n += 4;
        }

        public void i64(long v) {
            need(8);
            putI64(a, n, v);
            n += 8;
        }

        public void raw(byte[] src, int off, int len) {
            need(len);
            System.arraycopy(src, off, a, n, len);
            n += len;
        }

        public byte[] finish() {
            if (n == a.length) {
                return a;
            }
            return Arrays.copyOf(a, n);
        }

        public byte[] buffer() {
            return a;
        }
    }
}
