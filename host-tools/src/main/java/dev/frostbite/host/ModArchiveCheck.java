package dev.frostbite.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Runnable self-check for the .frostbite packaging path. Run: java -cp ... ModArchiveCheck */
public final class ModArchiveCheck {
    private ModArchiveCheck() {}

    public static void main(String[] args) throws IOException {
        Path apk = Files.createTempFile("frostbite-check-", ".apk");
        Path pkg = Files.createTempFile("frostbite-check-", ".frostbite");
        try {
            writeSampleApk(apk);
            ModArchive archive = ModArchive.fromApk(apk);
            require(archive.id().equals("demo-mod"), "id parsed from mod.json");
            require(archive.fileName().equals("demo-mod.frostbite"), "branded file name");

            Files.write(pkg, archive.bytes());
            ModArchive reparsed = ModArchive.fromFrostbite(pkg); // re-validates the branded format
            require(reparsed.id().equals("demo-mod"), "round-trips through .frostbite");
            Set<String> entries = entryNames(pkg);
            require(entries.contains("mod.json"), "mod.json at root");
            require(entries.contains("classes.dex"), "primary dex kept");
            require(entries.contains("META-INF/frostbite/hooks.json"), "hooks manifest kept");
            require(entries.contains("lib/arm64-v8a/libdemo.so"), "ARM library kept");
            require(!entries.contains("res/junk.bin"), "non-payload APK files dropped");

            Path noDex = Files.createTempFile("frostbite-check-", ".apk");
            try {
                writeApk(noDex, false);
                boolean rejected = false;
                try { ModArchive.fromApk(noDex); } catch (IOException expected) { rejected = true; }
                require(rejected, "APK without classes.dex is rejected");
            } finally {
                Files.deleteIfExists(noDex);
            }
            System.out.println("ModArchiveCheck passed");
        } finally {
            Files.deleteIfExists(apk);
            Files.deleteIfExists(pkg);
        }
    }

    private static void writeSampleApk(Path apk) throws IOException { writeApk(apk, true); }

    private static void writeApk(Path apk, boolean withDex) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk), StandardCharsets.UTF_8)) {
            add(zip, "META-INF/frostbite/mod.json", ("{\"schemaVersion\":1,\"apiVersion\":1," +
                    "\"id\":\"demo-mod\",\"version\":\"1.0\"," +
                    "\"entrypoint\":\"dev.frostbite.sample.Mod\"," +
                    "\"hooks\":\"META-INF/frostbite/hooks.json\"}").getBytes(StandardCharsets.UTF_8));
            add(zip, "META-INF/frostbite/hooks.json",
                    "{\"schemaVersion\":1,\"hooks\":[]}".getBytes(StandardCharsets.UTF_8));
            if (withDex) add(zip, "classes.dex", new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', 0});
            add(zip, "lib/arm64-v8a/libdemo.so", new byte[] {0x7f, 'E', 'L', 'F', 0, 0, 0, 0});
            add(zip, "res/junk.bin", new byte[] {1, 2, 3});
        }
    }

    private static Set<String> entryNames(Path archive) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        return names;
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError("ModArchiveCheck failed: " + what);
    }
}
