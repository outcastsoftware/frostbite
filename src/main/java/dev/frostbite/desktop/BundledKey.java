package dev.frostbite.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts the bundled development keystore to a temp file for signing. */
final class BundledKey {
    static final String ALIAS = "frostbite";
    static final char[] PASSWORD = "frostbite".toCharArray();
    private static Path extracted;
    private BundledKey() {}

    static synchronized Path path() throws IOException {
        if (extracted != null && Files.isRegularFile(extracted)) return extracted;
        try (InputStream input = BundledKey.class.getResourceAsStream("/frostbite-development.p12")) {
            if (input == null) throw new IOException("Bundled development key is missing");
            extracted = Files.createTempFile("frostbite-development-", ".p12");
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        }
    }
}
