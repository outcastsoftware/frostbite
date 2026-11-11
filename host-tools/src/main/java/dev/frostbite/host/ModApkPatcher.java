package dev.frostbite.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adds, removes, reorders, and toggles mod packages in an already Frostbite-enabled APK. */
public final class ModApkPatcher {
    private static final String ROOT = "assets/frostbite/mods/";
    private static final int MAX_INDEX_BYTES = 1024 * 1024;
    private static final int MAX_MOD_BYTES = 64 * 1024 * 1024;
    private ModApkPatcher() {}

    /** One embedded mod and what the index says about it. */
    public record ModInfo(String fileName, String id, String version, boolean enabled,
                          int hookCount, List<String> abis) {}

    /** Desired state of a kept mod: its file name and whether it is enabled. Order matters. */
    public record ModState(String fileName, boolean enabled) {}

    /** Just the ordered file names currently in the APK (kept for callers that only need names). */
    public static List<String> installedMods(Path gameApk) throws IOException {
        List<String> names = new ArrayList<>();
        for (ModInfo info : inspect(gameApk)) names.add(info.fileName());
        return List.copyOf(names);
    }

    /** Full details of every embedded mod, in load order, for display. */
    public static List<ModInfo> inspect(Path gameApk) throws IOException {
        if (!Files.isRegularFile(gameApk)) throw new IOException("Choose a Frostbite-enabled APK");
        try (ZipFile apk = new ZipFile(gameApk.toFile())) {
            ModIndex index = readIndex(apk);
            List<ModInfo> result = new ArrayList<>();
            for (IndexEntry entry : index.entries()) {
                byte[] bytes = readEntryBytes(apk, ROOT + entry.name(), MAX_MOD_BYTES);
                result.add(detailsOf(entry, bytes));
            }
            return List.copyOf(result);
        }
    }

    /**
     * Add/remove in one pass (legacy shape). Kept mods preserve their current order and enabled
     * flags; added mods are appended enabled. Equivalent to the richer {@link #applyState}.
     */
    public static void apply(Path gameApk, List<Path> modFiles, List<String> removeNames,
                             Path outputApk, Path keystore, String alias,
                             char[] storePassword, char[] keyPassword,
                             Consumer<String> progress) throws IOException, InterruptedException {
        if (modFiles.isEmpty() && removeNames.isEmpty())
            throw new IOException("Add or remove at least one mod");
        Set<String> removed = new HashSet<>(removeNames);
        if (removed.size() != removeNames.size()) throw new IOException("Duplicate mod to remove");
        List<ModState> keep = new ArrayList<>();
        try (ZipFile apk = new ZipFile(gameApk.toFile())) {
            for (IndexEntry entry : readIndex(apk).entries())
                if (!removed.remove(entry.name())) keep.add(new ModState(entry.name(), entry.enabled()));
        }
        if (!removed.isEmpty())
            throw new IOException("Selected mod is no longer present in the input APK");
        applyState(gameApk, keep, modFiles, outputApk, keystore, alias,
                storePassword, keyPassword, progress);
    }

    /**
     * Rebuild the APK to a new signed output. {@code states} is the final set of EXISTING mods to
     * keep, in the desired order, each with its enabled flag — so this covers remove (omit a mod),
     * reorder (change the order), and toggle (flip enabled). {@code addFiles} are new mods appended
     * enabled. Only the index changes for a pure reorder/toggle; dex assets are copied as-is.
     */
    public static void applyState(Path gameApk, List<ModState> states, List<Path> addFiles,
                                  Path outputApk, Path keystore, String alias,
                                  char[] storePassword, char[] keyPassword,
                                  Consumer<String> progress) throws IOException, InterruptedException {
        if (!Files.isRegularFile(gameApk)) throw new IOException("Choose a Frostbite-enabled APK");
        if (gameApk.toAbsolutePath().normalize().equals(outputApk.toAbsolutePath().normalize()))
            throw new IOException("Output APK must differ from input APK");
        Path parent = outputApk.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Output directory does not exist");

        // Validate added packages up front.
        List<ModArchive> added = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : addFiles) {
            progress.accept("Validating " + file.getFileName());
            ModArchive archive = ModArchive.fromFile(file);
            if (!ids.add(archive.id())) throw new IOException("Duplicate selected mod ID: " + archive.id());
            added.add(archive);
        }

        AndroidSdkTools sdk = AndroidSdkTools.find();
        Path unsigned = Files.createTempFile(parent, "frostbite-mods-", ".apk");
        try {
            writeUnsigned(gameApk, states, added, unsigned);
            ApkSigning.alignSignVerify(unsigned, outputApk, keystore, alias,
                    storePassword, keyPassword, sdk, progress);
        } finally {
            Files.deleteIfExists(unsigned);
        }
    }

    private static void writeUnsigned(Path gameApk, List<ModState> states, List<ModArchive> added,
                                      Path unsigned) throws IOException {
        try (ZipFile input = new ZipFile(gameApk.toFile())) {
            ModIndex original = readIndex(input);
            Set<String> originalNames = new HashSet<>();
            for (IndexEntry e : original.entries()) originalNames.add(e.name());

            // Build the final ordered index from the kept states + appended additions.
            JSONArray finalIndex = new JSONArray();
            Set<String> kept = new LinkedHashSet<>();
            for (ModState state : states) {
                if (!originalNames.contains(state.fileName()))
                    throw new IOException("Mod to keep is not in the APK: " + state.fileName());
                if (!kept.add(state.fileName()))
                    throw new IOException("Duplicate mod in desired order: " + state.fileName());
                finalIndex.put(new JSONObject().put("file", state.fileName()).put("enabled", state.enabled()));
            }
            for (ModArchive mod : added) {
                if (originalNames.contains(mod.fileName()) || !kept.add(mod.fileName()))
                    throw new IOException("Mod already present in APK: " + mod.id());
                finalIndex.put(new JSONObject().put("file", mod.fileName()).put("enabled", true));
            }
            // Anything originally present but not kept is removed.
            Set<String> removed = new HashSet<>(originalNames);
            removed.removeAll(kept);

            JSONObject index = original.json();
            index.put("mods", finalIndex);

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
                add(output, ROOT + "index.json", index.toString(2).getBytes(StandardCharsets.UTF_8));
                for (ModArchive mod : added) add(output, ROOT + mod.fileName(), mod.bytes());
            }
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Frostbite mod index", invalid);
        }
    }

    /** One index entry: a file name and whether it is enabled. */
    private record IndexEntry(String name, boolean enabled) {}
    private record ModIndex(JSONObject json, List<IndexEntry> entries) {}

    private static ModIndex readIndex(ZipFile input) throws IOException {
        ZipEntry indexEntry = input.getEntry(ROOT + "index.json");
        if (indexEntry == null) throw new IOException("Input APK has no Frostbite mod index");
        if (input.getEntry("lib/arm64-v8a/libfrostbite.so") == null &&
                input.getEntry("lib/armeabi-v7a/libfrostbite.so") == null)
            throw new IOException("Input APK has no supported Frostbite native library");
        try (InputStream stream = input.getInputStream(indexEntry)) {
            byte[] raw = stream.readNBytes(MAX_INDEX_BYTES + 1);
            if (raw.length > MAX_INDEX_BYTES) throw new IOException("Frostbite mod index is too large");
            JSONObject index = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (index.getInt("schemaVersion") != 1)
                throw new IOException("Unsupported Frostbite mod index version");
            JSONArray mods = index.getJSONArray("mods");
            List<IndexEntry> result = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int i = 0; i < mods.length(); i++) {
                // Each entry is a bare string (back-compat) or {"file":...,"enabled":...}.
                Object value = mods.get(i);
                String name;
                boolean enabled;
                if (value instanceof JSONObject entry) {
                    name = entry.getString("file");
                    enabled = entry.optBoolean("enabled", true);
                } else {
                    name = mods.getString(i);
                    enabled = true;
                }
                if (!name.matches("[a-z0-9][a-z0-9._-]*\\.(?:frostbite|frostmod)") ||
                        name.contains("..") || !used.add(name))
                    throw new IOException("Invalid or duplicate indexed mod: " + name);
                if (input.getEntry(ROOT + name) == null)
                    throw new IOException("Indexed mod is missing from APK: " + name);
                result.add(new IndexEntry(name, enabled));
            }
            return new ModIndex(index, result);
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Frostbite mod index", invalid);
        }
    }

    /** Parse an embedded .frostbite package's bytes for display: id, version, hook count, ABIs. */
    private static ModInfo detailsOf(IndexEntry entry, byte[] bytes) throws IOException {
        String id = null, version = null;
        int hookCount = 0;
        Set<String> abis = new TreeSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                String name = item.getName();
                if (name.equals("mod.json")) {
                    JSONObject info = new JSONObject(new String(readStream(zip, MAX_INDEX_BYTES), StandardCharsets.UTF_8));
                    id = info.optString("id", null);
                    version = info.optString("version", null);
                } else if (name.equals("META-INF/frostbite/hooks.json")) {
                    JSONObject manifest = new JSONObject(new String(readStream(zip, MAX_INDEX_BYTES), StandardCharsets.UTF_8));
                    hookCount = manifest.getJSONArray("hooks").length();
                } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 2) abis.add(parts[1]);
                }
            }
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid metadata in " + entry.name(), invalid);
        }
        if (id == null || version == null) throw new IOException("Missing metadata in " + entry.name());
        return new ModInfo(entry.name(), id, version, entry.enabled(), hookCount, List.copyOf(abis));
    }

    private static byte[] readEntryBytes(ZipFile zip, String name, int limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Indexed mod is missing from APK: " + name);
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException(name + " exceeds size limit");
            return bytes;
        }
    }

    private static byte[] readStream(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Frostbite metadata exceeds size limit");
        return bytes;
    }

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
