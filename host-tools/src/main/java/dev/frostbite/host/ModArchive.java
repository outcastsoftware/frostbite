package dev.frostbite.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** A compact DEX package with metadata and optional ARM native libraries. */
public record ModArchive(String id, String fileName, byte[] bytes) {
    private static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final String HOOKS = "META-INF/frostbite/hooks.json";

    public static ModArchive fromFile(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".frostbite")) return fromFrostbite(file);
        if (name.endsWith(".apk")) return fromApk(file);
        throw new IOException("Choose a .frostbite mod package or legacy mod APK: " + file);
    }

    public static ModArchive fromApk(Path apk) throws IOException {
        return readPackage(apk, true);
    }

    public static ModArchive fromFrostbite(Path archive) throws IOException {
        if (Files.size(archive) > MAX_PACKAGE_BYTES)
            throw new IOException("Frostbite package exceeds 64 MiB: " + archive);
        return readPackage(archive, false);
    }

    private static ModArchive readPackage(Path source, boolean apkInput) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<String> dexNames = new ArrayList<>();
            List<String> libraries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seen.add(name) && !apkInput)
                    throw new IOException("Duplicate Frostbite package entry: " + name);
                if (name.matches("classes(?:[2-9][0-9]*)?\\.dex")) dexNames.add(name);
                else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    if (nativeLibrary(name)) libraries.add(name);
                    else if (!apkInput) throw new IOException("Unsupported native library path: " + name);
                } else if (!apkInput && !entry.isDirectory() &&
                        !name.equals("mod.json") && !name.equals(HOOKS)) {
                    throw new IOException("Unexpected Frostbite package entry: " + name);
                }
            }
            dexNames.sort(Comparator.comparingInt(ModArchive::dexNumber));
            libraries.sort(String::compareTo);
            if (dexNames.isEmpty() || !dexNames.getFirst().equals("classes.dex"))
                throw new IOException("Mod has no classes.dex: " + source);
            for (int i = 0; i < dexNames.size(); i++) {
                if (dexNumber(dexNames.get(i)) != i + 1)
                    throw new IOException("Non-contiguous mod DEX sequence: " + source);
            }
            String descriptorPath = apkInput ? metadataPath(zip, "mod.json") : "mod.json";
            String hooksPath = apkInput ? metadataPath(zip, "hooks.json") : HOOKS;
            byte[] descriptor = read(zip, descriptorPath, MAX_JSON_BYTES);
            byte[] hooks = read(zip, hooksPath, MAX_JSON_BYTES);
            JSONObject info = new JSONObject(new String(descriptor, StandardCharsets.UTF_8));
            JSONObject manifest = new JSONObject(new String(hooks, StandardCharsets.UTF_8));
            if (info.getInt("schemaVersion") != 1 || info.getInt("apiVersion") != 1 ||
                    manifest.getInt("schemaVersion") != 1)
                throw new IOException("Unsupported Frostbite descriptor in " + source);
            String id = info.getString("id");
            if (!id.matches("[a-z0-9][a-z0-9._-]*") || id.contains(".."))
                throw new IOException("Invalid Frostbite mod ID: " + id);
            if (!info.getString("entrypoint").matches("[A-Za-z_$][A-Za-z0-9_.$]*") ||
                    !info.getString("hooks").equals(HOOKS) || info.getString("version").isBlank())
                throw new IOException("Invalid Frostbite metadata in " + source);

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (ZipOutputStream output = new ZipOutputStream(data, StandardCharsets.UTF_8)) {
                add(output, "mod.json", descriptor);
                int totalPayloadBytes = 0;
                for (String dexName : dexNames) {
                    byte[] dex = read(zip, dexName, MAX_PACKAGE_BYTES);
                    if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' ||
                            dex[3] != '\n' || dex[7] != 0)
                        throw new IOException("Invalid " + dexName + " in " + source);
                    totalPayloadBytes = addSize(totalPayloadBytes, dex.length, source);
                    add(output, dexName, dex);
                }
                add(output, HOOKS, hooks);
                for (String library : libraries) {
                    byte[] binary = read(zip, library, MAX_PACKAGE_BYTES);
                    if (binary.length < 4 || binary[0] != 0x7f || binary[1] != 'E' ||
                            binary[2] != 'L' || binary[3] != 'F')
                        throw new IOException("Invalid ELF library " + library + " in " + source);
                    totalPayloadBytes = addSize(totalPayloadBytes, binary.length, source);
                    add(output, library, binary);
                }
            }
            if (data.size() > MAX_PACKAGE_BYTES)
                throw new IOException("Frostbite package exceeds 64 MiB: " + source);
            return new ModArchive(id, id + ".frostbite", data.toByteArray());
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid Frostbite metadata in " + source, invalid);
        }
    }

    private static int addSize(int accumulated, int next, Path source) throws IOException {
        if (next > MAX_PACKAGE_BYTES - accumulated)
            throw new IOException("Mod DEX and native libraries exceed 64 MiB: " + source);
        return accumulated + next;
    }

    private static boolean nativeLibrary(String path) {
        return path.matches("lib/(?:arm64-v8a|armeabi-v7a)/lib[A-Za-z0-9_.+-]+\\.so") &&
                !path.contains("..");
    }

    private static int dexNumber(String name) {
        return name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length() - 4));
    }

    private static String metadataPath(ZipFile apk, String file) throws IOException {
        String generated = "META-INF/frostbite/" + file;
        String asset = "assets/frostbite/" + file;
        if (apk.getEntry(generated) != null && apk.getEntry(asset) != null)
            throw new IOException("Ambiguous Frostbite metadata: " + file);
        return apk.getEntry(generated) != null ? generated : asset;
    }

    private static byte[] read(ZipFile zip, String name, int limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing " + name + " in " + zip.getName());
        if (entry.getSize() > limit) throw new IOException(name + " exceeds size limit");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException(name + " exceeds size limit");
            return bytes;
        }
    }

    private static void add(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
