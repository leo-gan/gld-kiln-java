package io.github.leogan.kiln.gen;

import io.github.leogan.kiln.JsonSchema;
import io.github.leogan.kiln.Schema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** {@code gld-kilngen-java --schema point.json --out tests/generated}. */
public final class KilnGen {
    private KilnGen() {}

    public static void main(String[] args) throws Exception {
        Path schema = null;
        Path out = null;
        String pkg = "io.github.leogan.kiln.generated";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--schema" -> schema = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--package" -> pkg = args[++i];
                default -> {
                    System.err.println("usage: gld-kilngen-java --schema FILE --out DIR [--package NAME]");
                    System.exit(2);
                }
            }
        }
        if (schema == null || out == null) {
            System.err.println("usage: gld-kilngen-java --schema FILE --out DIR [--package NAME]");
            System.exit(2);
        }
        Schema parsed = JsonSchema.parse(schema);
        Map<String, String> files = JavaGen.emit(parsed, pkg);
        Files.createDirectories(out);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Files.writeString(out.resolve(entry.getKey() + ".java"), entry.getValue());
        }
    }
}
