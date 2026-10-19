package dev.frostbite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public record AndroidSdkTools(Path sdk, Path buildTools, Path zipalign, Path apksignerJar, Path d8Jar) {
    public static AndroidSdkTools find() throws IOException {
        String value = System.getenv("ANDROID_HOME");
        if (value == null || value.isBlank()) value = System.getenv("ANDROID_SDK_ROOT");
        if (value == null || value.isBlank())
            throw new IOException("Set ANDROID_HOME to the Android SDK directory");
        Path sdk = Path.of(value).toAbsolutePath().normalize();
        Path toolsRoot = sdk.resolve("build-tools");
        if (!Files.isDirectory(toolsRoot)) throw new IOException("Android SDK build-tools are missing");
        try (var versions = Files.list(toolsRoot)) {
            Path buildTools = versions.filter(Files::isDirectory)
                    .max(Comparator.comparing(path -> path.getFileName().toString(),
                            AndroidSdkTools::compareVersions))
                    .orElseThrow(() -> new IOException("No Android SDK build-tools version found"));
            Path zipalign = buildTools.resolve(isWindows() ? "zipalign.exe" : "zipalign");
            Path apksigner = buildTools.resolve("lib/apksigner.jar");
            Path d8 = buildTools.resolve("lib/d8.jar");
            if (!Files.isRegularFile(zipalign) || !Files.isRegularFile(apksigner) ||
                    !Files.isRegularFile(d8))
                throw new IOException("Selected Android SDK build-tools are incomplete: " + buildTools);
            return new AndroidSdkTools(sdk, buildTools, zipalign, apksigner, d8);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? number(a[i]) : 0;
            int bv = i < b.length ? number(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return left.compareTo(right);
    }

    private static int number(String part) {
        try { return Integer.parseInt(part); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
