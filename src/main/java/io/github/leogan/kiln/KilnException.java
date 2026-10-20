package io.github.leogan.kiln;

/** Wire error. {@link #code()} is one of FRAME, LIMIT, EOF, TYPE, UTF8, TRAILING, SCHEMA. */
public final class KilnException extends RuntimeException {
    public static final String FRAME = "FRAME";
    public static final String LIMIT = "LIMIT";
    public static final String EOF = "EOF";
    public static final String TYPE = "TYPE";
    public static final String UTF8 = "UTF8";
    public static final String TRAILING = "TRAILING";
    public static final String SCHEMA = "SCHEMA";

    private final String code;

    private KilnException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static KilnException of(String code) {
        return new KilnException(code);
    }

    public static KilnException frame() {
        return new KilnException(FRAME);
    }

    public static KilnException limit() {
        return new KilnException(LIMIT);
    }

    public static KilnException eof() {
        return new KilnException(EOF);
    }

    public static KilnException type() {
        return new KilnException(TYPE);
    }

    public static KilnException utf8() {
        return new KilnException(UTF8);
    }

    public static KilnException trailing() {
        return new KilnException(TRAILING);
    }

    public static KilnException schema() {
        return new KilnException(SCHEMA);
    }
}
