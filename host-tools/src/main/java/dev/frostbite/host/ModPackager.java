package dev.frostbite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Build-time entrypoint used by the mod project's assembleRelease task. */
public final class ModPackager {
    private ModPackager() {}

    public static void packageApk(Path modApk, Path output) throws IOException {
        if (!Files.isRegularFile(modApk)) throw new IOException("Missing release mod APK: " + modApk);
        if (!output.getFileName().toString().endsWith(".frostbite"))
            throw new IOException("Mod output must end in .frostbite");
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Mod output directory does not exist: " + output);
        ModArchive packageData = ModArchive.fromApk(modApk);
        Path temporary = Files.createTempFile(parent, "frostbite-package-", ".tmp");
        try {
            Files.write(temporary, packageData.bytes());
            ModArchive.fromFrostbite(temporary);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IOException("Expected release APK and .frostbite output path");
        packageApk(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Created " + args[1]);
    }
}
