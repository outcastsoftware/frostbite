package dev.frostbite.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adds or removes mod packages in an already Frostbite-enabled APK. */
public final class ModApkPatcher {
    private static final String ROOT = "assets/frostbite/mods/";
    private ModApkPatcher() {}

    public static List<String> installedMods(Path gameApk) throws IOException {
        if (!Files.isRegularFile(gameApk)) throw new IOException("Choose a Frostbite-enabled APK");
        try (ZipFile apk = new ZipFile(gameApk.toFile())) {
            return List.copyOf(readIndex(apk).names());
        }
    }

    /** Builds a new signed APK with modFiles added and removeNames removed in one pass. */
    public static void apply(Path gameApk, List<Path> modFiles, List<String> removeNames,
                             Path outputApk, Path keystore, String alias,
                             char[] storePassword, char[] keyPassword,
                             Consumer<String> progress) throws IOException, InterruptedException {
        if (modFiles.isEmpty() && removeNames.isEmpty())
            throw new IOException("Add or remove at least one mod");
        List<ModArchive> added = new ArrayList<>();
        Set<String> newIds = new HashSet<>();
        for (Path file : modFiles) {
            progress.accept("Validating " + file.getFileName());
            ModArchive archive = ModArchive.fromFile(file);
            if (!newIds.add(archive.id())) throw new IOException("Duplicate selected mod ID: " + archive.id());
            added.add(archive);
        }
        Set<String> removed = new HashSet<>(removeNames);
        if (removed.size() != removeNames.size()) throw new IOException("Duplicate mod to remove");
        rewrite(gameApk, added, removed, outputApk, keystore,
                alias, storePassword, keyPassword, progress);
    }

    private static void rewrite(Path gameApk, List<ModArchive> added, Set<String> removed,
                                Path outputApk, Path keystore, String alias,
                                char[] storePassword, char[] keyPassword,
                                Consumer<String> progress) throws IOException, InterruptedException {
        if (!Files.isRegularFile(gameApk)) throw new IOException("Choose a Frostbite-enabled APK");
        if (gameApk.toAbsolutePath().normalize().equals(outputApk.toAbsolutePath().normalize()))
            throw new IOException("Output APK must differ from input APK");
        Path parent = outputApk.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Output directory does not exist");
        AndroidSdkTools sdk = AndroidSdkTools.find();
        Path unsigned = Files.createTempFile(parent, "frostbite-mods-", ".apk");
        try {
            writeUnsigned(gameApk, added, removed, unsigned);
            ApkSigning.alignSignVerify(unsigned, outputApk, keystore, alias,
                    storePassword, keyPassword, sdk, progress);
        } finally {
            Files.deleteIfExists(unsigned);
        }
    }

    private static void writeUnsigned(Path gameApk, List<ModArchive> added,
                                      Set<String> removed, Path unsigned) throws IOException {
        try (ZipFile input = new ZipFile(gameApk.toFile())) {
            ModIndex original = readIndex(input);
            Set<String> used = new HashSet<>(original.names());
            if (!used.containsAll(removed))
                throw new IOException("Selected mod is no longer present in the input APK");
            JSONArray updated = new JSONArray();
            for (String name : original.names()) if (!removed.contains(name)) updated.put(name);
            for (ModArchive mod : added) {
                if (!used.add(mod.fileName()))
                    throw new IOException("Mod already present in APK: " + mod.id());
                updated.put(mod.fileName());
            }
            original.json().put("mods", updated);
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(unsigned),
                    StandardCharsets.UTF_8)) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.equals(ROOT + "index.json") || isOldSignature(name) ||
                            name.startsWith(ROOT) && removed.contains(name.substring(ROOT.length())))
                        continue;
                    if (name.startsWith("/") || name.contains("../") || name.contains("..\\"))
                        throw new IOException("Unsafe APK entry: " + name);
                    output.putNextEntry(new ZipEntry(entry));
                    if (!entry.isDirectory()) {
                        try (InputStream stream = input.getInputStream(entry)) { stream.transferTo(output); }
                    }
                    output.closeEntry();
                }
                add(output, ROOT + "index.json",
                        original.json().toString(2).getBytes(StandardCharsets.UTF_8));
                for (ModArchive mod : added) add(output, ROOT + mod.fileName(), mod.bytes());
            }
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Frostbite mod index", invalid);
        }
    }

    private static ModIndex readIndex(ZipFile input) throws IOException {
        ZipEntry indexEntry = input.getEntry(ROOT + "index.json");
        if (indexEntry == null) throw new IOException("Input APK has no Frostbite mod index");
        if (input.getEntry("lib/arm64-v8a/libfrostbite.so") == null &&
                input.getEntry("lib/armeabi-v7a/libfrostbite.so") == null)
            throw new IOException("Input APK has no supported Frostbite native library");
        try (InputStream stream = input.getInputStream(indexEntry)) {
            byte[] raw = stream.readNBytes(1024 * 1024 + 1);
            if (raw.length > 1024 * 1024) throw new IOException("Frostbite mod index is too large");
            JSONObject index = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (index.getInt("schemaVersion") != 1)
                throw new IOException("Unsupported Frostbite mod index version");
            JSONArray names = index.getJSONArray("mods");
            List<String> result = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                if (!name.matches("[a-z0-9][a-z0-9._-]*\\.(?:frostbite|frostmod)") ||
                        name.contains("..") || !used.add(name))
                    throw new IOException("Invalid or duplicate indexed mod: " + name);
                if (input.getEntry(ROOT + name) == null)
                    throw new IOException("Indexed mod is missing from APK: " + name);
                result.add(name);
            }
            return new ModIndex(index, result);
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Frostbite mod index", invalid);
        }
    }

    private record ModIndex(JSONObject json, List<String> names) {}

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") ||
                upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static void add(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
