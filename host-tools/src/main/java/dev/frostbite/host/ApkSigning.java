package dev.frostbite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ApkSigning {
    private ApkSigning() {}

    public static void alignSignVerify(Path unsignedApk, Path outputApk, Path keystore,
                                       String alias, char[] storePassword, char[] keyPassword,
                                       AndroidSdkTools sdk, Consumer<String> progress)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(unsignedApk) || !Files.isRegularFile(keystore))
            throw new IOException("Unsigned APK or keystore does not exist");
        if (alias == null || alias.isBlank() || storePassword.length == 0)
            throw new IOException("Keystore alias and password are required");
        if (outputApk.toAbsolutePath().normalize().equals(unsignedApk.toAbsolutePath().normalize()))
            throw new IOException("Output APK must differ from the unsigned APK");
        Path parent = outputApk.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Output directory does not exist");
        Path aligned = Files.createTempFile(parent, "frostbite-aligned-", ".apk");
        Files.delete(aligned);
        try {
            progress.accept("Aligning APK");
            ToolProcess.run(List.of(sdk.zipalign().toString(), "-f", "-p", "4",
                    unsignedApk.toString(), aligned.toString()), null, Map.of(), progress);
            progress.accept("Signing APK with v1, v2, and v3");
            List<String> command = new ArrayList<>(List.of(javaBinary(), "-jar",
                    sdk.apksignerJar().toString(), "sign", "--ks", keystore.toString(),
                    "--ks-key-alias", alias, "--ks-pass", "env:FROSTBITE_KS_PASS",
                    "--key-pass", "env:FROSTBITE_KEY_PASS",
                    "--v1-signing-enabled", "true",
                    "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", "true",
                    "--out", outputApk.toString(), aligned.toString()));
            String store = new String(storePassword);
            String key = keyPassword.length == 0 ? store : new String(keyPassword);
            try {
                ToolProcess.run(command, null,
                        Map.of("FROSTBITE_KS_PASS", store, "FROSTBITE_KEY_PASS", key), progress);
            } finally {
                Arrays.fill(storePassword, '\0');
                Arrays.fill(keyPassword, '\0');
            }
            progress.accept("Verifying APK signatures");
            String report = ToolProcess.run(List.of(javaBinary(), "-jar",
                    sdk.apksignerJar().toString(), "verify", "--verbose", outputApk.toString()),
                    null, Map.of(), progress);
            for (String scheme : List.of("v2", "v3"))
                requireScheme(report, scheme);
            // Modern SDK ranges verify with v2/v3 and report v1=false even when a valid
            // JAR signature is present. Check v1 in the pre-v2 API range separately.
            String jarReport = ToolProcess.run(List.of(javaBinary(), "-jar",
                    sdk.apksignerJar().toString(), "verify", "--verbose",
                    "--min-sdk-version", "18", "--max-sdk-version", "23", outputApk.toString()),
                    null, Map.of(), ignored -> { });
            requireScheme(jarReport, "v1");
        } finally {
            Files.deleteIfExists(aligned);
        }
    }

    private static void requireScheme(String report, String scheme) throws IOException {
        if (report.lines().noneMatch(line -> line.contains("Verified using " + scheme + " scheme")
                && line.trim().endsWith("true")))
            throw new IOException("APK did not verify with signature scheme " + scheme);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }
}
