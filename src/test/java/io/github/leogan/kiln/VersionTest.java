package io.github.leogan.kiln;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

public class VersionTest {
    @Test
    void versionFileMatchesConstant() throws Exception {
        String version = Files.readString(KilnV2CasesTest.repoRoot().resolve("VERSION")).trim();
        assertEquals(version, Version.VERSION);
    }
}
