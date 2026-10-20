package io.github.leogan.kiln;

/** A slice of an existing buffer. Tensor payloads and raw byte runs borrow the input. */
public final class Bytes {
    private final byte[] data;
    private final int offset;
    private final int length;

    private Bytes(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    public static Bytes view(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("slice");
        }
        return new Bytes(data, offset, length);
    }

    public static Bytes copy(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("null");
        }
        byte[] owned = data.clone();
        return new Bytes(owned, 0, owned.length);
    }

    public int length() {
        return length;
    }

    public byte get(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return data[offset + index];
    }

    public byte[] array() {
        return data;
    }

    public int offset() {
        return offset;
    }

    public void copyTo(byte[] dest, int destOff) {
        System.arraycopy(data, offset, dest, destOff, length);
    }

    public byte[] toArray() {
        if (length == 0) {
            return new byte[0];
        }
        byte[] out = new byte[length];
        copyTo(out, 0);
        return out;
    }
}
