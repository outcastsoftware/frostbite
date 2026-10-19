package dev.frostbite.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Patches one launcher Activity and embeds the matching Frostbite Java/native runtime. */
public final class FrostbiteInstaller {
    private static final String BOOTSTRAP =
            "    invoke-static {p0}, Ldev/frostbite/runtime/FrostbiteBootstrap;->start(Landroid/content/Context;)V\n";
    private FrostbiteInstaller() { }

    public static void install(Path sourceApk, Path runtimeBundle, String launcherClass,
                               Path outputApk, Path keystore, String alias,
                               char[] storePassword, char[] keyPassword,
                               Consumer<String> progress) throws IOException, InterruptedException {
        sourceApk = sourceApk.toAbsolutePath().normalize();
        outputApk = outputApk.toAbsolutePath().normalize();
        if (!Files.isRegularFile(sourceApk) || !Files.isRegularFile(runtimeBundle))
            throw new IOException("Game APK or Frostbite runtime bundle is missing");
        if (sourceApk.equals(outputApk)) throw new IOException("Output must differ from input APK");
        if (!launcherClass.matches("[A-Za-z_$][A-Za-z0-9_.$]*"))
            throw new IOException("Enter the full launch Activity class name");
        AndroidSdkTools sdk = AndroidSdkTools.find();
        Path work = Files.createTempDirectory("frostbite-install-").toAbsolutePath().normalize();
        try {
            Path decoded = work.resolve("decoded");
            Path rebuilt = work.resolve("rebuilt.apk");
            progress.accept("Decoding APK bytecode");
            runApktool(List.of("d", "-f", "-r", "-o", decoded.toString(), sourceApk.toString()), progress);
            patchActivity(decoded, launcherClass);
            progress.accept("Rebuilding game bytecode");
            runApktool(List.of("b", "-o", rebuilt.toString(), decoded.toString()), progress);

            Path api = work.resolve("api.jar");
            Path aar = work.resolve("runtime.aar");
            extractBundle(runtimeBundle, api, aar);
            Path runtimeClasses = work.resolve("runtime-classes.jar");
            byte[] arm64;
            byte[] arm32;
            try (ZipFile runtime = new ZipFile(aar.toFile())) {
                Files.write(runtimeClasses, read(runtime, "classes.jar", 32 * 1024 * 1024));
                arm64 = read(runtime, "jni/arm64-v8a/libfrostbite.so", 64 * 1024 * 1024);
                arm32 = read(runtime, "jni/armeabi-v7a/libfrostbite.so", 64 * 1024 * 1024);
            }
            Path dexOut = work.resolve("dex");
            Files.createDirectory(dexOut);
            progress.accept("Compiling Frostbite runtime DEX");
            ToolProcess.run(List.of(javaBinary(), "-cp", sdk.d8Jar().toString(),
                    "com.android.tools.r8.D8", "--min-api", "26", "--output", dexOut.toString(),
                    api.toString(), runtimeClasses.toString()), null, java.util.Map.of(), progress);
            Path unsigned = work.resolve("unsigned.apk");
            addRuntime(rebuilt, sourceApk, dexOut.resolve("classes.dex"), arm64, arm32, unsigned);
            ApkSigning.alignSignVerify(unsigned, outputApk, keystore, alias,
                    storePassword, keyPassword, sdk, progress);
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(work))
                        throw new IOException("Unsafe temporary cleanup path: " + path);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void runApktool(List<String> arguments, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(javaBinary(), "-cp",
                System.getProperty("java.class.path"), "brut.apktool.Main"));
        command.addAll(arguments);
        ToolProcess.run(command, null, java.util.Map.of(), progress);
    }

    private static void patchActivity(Path decoded, String launcherClass) throws IOException {
        String relative = launcherClass.replace('.', '/') + ".smali";
        List<Path> candidates;
        try (var paths = Files.list(decoded)) {
            candidates = paths.filter(path -> path.getFileName().toString().startsWith("smali"))
                    .map(path -> path.resolve(relative)).filter(Files::isRegularFile).toList();
        }
        if (candidates.size() != 1)
            throw new IOException("Could not find one launch Activity smali: " + launcherClass);
        Path file = candidates.getFirst();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.contains("Ldev/frostbite/runtime/FrostbiteBootstrap;->start"))
            throw new IOException("Activity already contains a Frostbite bootstrap call");
        String methodMarker = ".method protected onCreate(Landroid/os/Bundle;)V";
        int start = source.indexOf(methodMarker);
        if (start < 0) {
            String superclass = source.lines().filter(line -> line.startsWith(".super "))
                    .map(line -> line.substring(7).strip()).findFirst()
                    .orElseThrow(() -> new IOException("Activity has no superclass declaration"));
            String method = "\n" + methodMarker + "\n    .locals 0\n" +
                    "    invoke-super {p0, p1}, " + superclass + "->onCreate(Landroid/os/Bundle;)V\n" +
                    BOOTSTRAP + "    return-void\n.end method\n";
            Files.writeString(file, source + method, StandardCharsets.UTF_8);
            return;
        }
        int end = source.indexOf(".end method", start);
        if (end < 0) throw new IOException("Unterminated launch Activity onCreate");
        String before = source.substring(0, start);
        String body = source.substring(start, end);
        String after = source.substring(end);
        if (!body.contains("return-void"))
            throw new IOException("Launch Activity onCreate has no normal return");
        body = body.replace("    return-void", BOOTSTRAP + "    return-void");
        Files.writeString(file, before + body + after, StandardCharsets.UTF_8);
    }

    private static void extractBundle(Path bundle, Path api, Path aar) throws IOException {
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            Files.write(api, read(zip, "api.jar", 32 * 1024 * 1024));
            Files.write(aar, read(zip, "runtime.aar", 160 * 1024 * 1024));
        }
    }

    private static void addRuntime(Path rebuilt, Path original, Path dex,
                                   byte[] arm64, byte[] arm32, Path target) throws IOException {
        if (!Files.isRegularFile(dex)) throw new IOException("D8 produced no Frostbite DEX");
        try (ZipFile input = new ZipFile(rebuilt.toFile());
             ZipFile originalZip = new ZipFile(original.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            int nextDex = 1;
            var originalEntries = originalZip.entries();
            while (originalEntries.hasMoreElements()) {
                String name = originalEntries.nextElement().getName();
                if (name.matches("classes(?:[0-9]+)?\\.dex")) {
                    int number = name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length()-4));
                    nextDex = Math.max(nextDex, number + 1);
                }
            }
            Set<String> seen = new HashSet<>();
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seen.add(name)) throw new IOException("Duplicate APK entry: " + name);
                if (name.startsWith("/") || name.contains("../") || name.contains("..\\"))
                    throw new IOException("Unsafe APK entry: " + name);
                if (isOldSignature(name)) continue;
                ZipEntry copied = new ZipEntry(name);
                if (name.equals("resources.arsc")) {
                    copied.setMethod(ZipEntry.STORED);
                    copied.setSize(entry.getSize());
                    copied.setCompressedSize(entry.getSize());
                    copied.setCrc(entry.getCrc());
                }
                output.putNextEntry(copied);
                if (!entry.isDirectory()) {
                    try (InputStream stream = input.getInputStream(entry)) { stream.transferTo(output); }
                }
                output.closeEntry();
            }
            add(output, "classes" + nextDex + ".dex", Files.readAllBytes(dex));
            add(output, "lib/arm64-v8a/libfrostbite.so", arm64);
            add(output, "lib/armeabi-v7a/libfrostbite.so", arm32);
            add(output, "assets/frostbite/mods/index.json",
                    "{\"schemaVersion\":1,\"mods\":[]}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") ||
                upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static byte[] read(ZipFile zip, String path, int max) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.getSize() > max) throw new IOException("Missing or oversized " + path);
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] result = input.readNBytes(max + 1);
            if (result.length > max) throw new IOException("Oversized " + path);
            return result;
        }
    }

    private static void add(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }
}
