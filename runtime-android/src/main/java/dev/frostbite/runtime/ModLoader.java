package dev.frostbite.runtime;

import android.content.Context;
import android.util.Log;
import dalvik.system.DexClassLoader;
import dev.frostbite.api.At;
import dev.frostbite.api.MethodSelector;
import dev.frostbite.api.ModContext;
import dev.frostbite.api.ModInitializer;
import dev.frostbite.api.SelectorMode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Loads only packages explicitly listed in assets/frostbite/mods/index.json. */
public final class ModLoader {
    private static final String TAG = "Frostbite";
    private static final String ROOT = "frostbite/mods/";
    private static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private final Context context;

    public ModLoader(Context context) { this.context = context.getApplicationContext(); }

    public List<LoadedMod> load() throws Exception {
        JSONObject index;
        try (InputStream input = context.getAssets().open(ROOT + "index.json")) {
            index = new JSONObject(readLimited(input, MAX_JSON_BYTES));
        } catch (FileNotFoundException missingIndex) {
            Log.i(TAG, "No mod index found; starting without mods");
            return List.of();
        }
        requireSchema(index, "mod index");
        JSONArray names = index.getJSONArray("mods");
        List<LoadedMod> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*\\.(?:frostbite|frostmod)") ||
                    name.contains("..")) throw new IOException("Unsafe mod package name: " + name);
            LoadedMod mod = loadOne(name);
            if (!ids.add(mod.id())) throw new IOException("Duplicate Frostbite mod ID: " + mod.id());
            loaded.add(mod);
        }
        for (LoadedMod mod : loaded) {
            mod.initializer().onLoad(new ModContext() {
                @Override public String modId() { return mod.id(); }
                @Override public void log(String message) { Log.i(TAG + "/" + mod.id(), message); }
            });
        }
        return List.copyOf(loaded);
    }

    private LoadedMod loadOne(String name) throws Exception {
        File directory = new File(context.getCodeCacheDir(), "frostbite");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create Frostbite code cache");
        File archive = new File(directory, name + ".jar");
        File staging = new File(directory, name + ".tmp");
        try (InputStream input = context.getAssets().open(ROOT + name)) {
            copyLimited(input, staging, MAX_PACKAGE_BYTES);
        }
        Files.move(staging.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!archive.setReadOnly()) throw new IOException("Cannot make mod archive read only");

        JSONObject descriptor;
        JSONObject manifest;
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.getEntry("classes.dex") == null)
                throw new IOException(name + " has no classes.dex");
            descriptor = readJson(zip, "mod.json");
            requireSchema(descriptor, name + " descriptor");
            if (descriptor.getInt("apiVersion") != 1)
                throw new IOException(name + " requires unsupported Frostbite API version");
            String hooksPath = descriptor.getString("hooks");
            if (!hooksPath.equals("META-INF/frostbite/hooks.json"))
                throw new IOException(name + " has unsupported hook manifest path");
            manifest = readJson(zip, hooksPath);
            requireSchema(manifest, name + " hooks");
        }
        String id = descriptor.getString("id");
        if (!id.matches("[a-z0-9][a-z0-9._-]*"))
            throw new IOException("Invalid mod ID in " + name);
        if (!name.equals(id + ".frostbite") && !name.equals(id + ".frostmod"))
            throw new IOException("Mod ID does not match package name: " + name);
        String version = descriptor.getString("version");
        if (version.trim().isEmpty()) throw new IOException("Empty mod version in " + name);
        ClassLoader loader = new DexClassLoader(archive.getAbsolutePath(),
                directory.getAbsolutePath(), null, context.getClassLoader());
        String entrypointName = descriptor.getString("entrypoint");
        Class<?> entrypointClass = Class.forName(entrypointName, true, loader);
        if (entrypointClass.getClassLoader() != loader)
            throw new IOException("Entrypoint resolved outside mod DEX: " + entrypointName);
        if (!ModInitializer.class.isAssignableFrom(entrypointClass))
            throw new IOException(entrypointName + " does not implement ModInitializer");
        ModInitializer initializer = (ModInitializer) entrypointClass.getDeclaredConstructor().newInstance();
        List<HookDeclaration> hooks = loadHooks(id, manifest.getJSONArray("hooks"), loader);
        return new LoadedMod(id, version, loader, initializer, List.copyOf(hooks));
    }

    private List<HookDeclaration> loadHooks(String id, JSONArray array, ClassLoader loader) throws Exception {
        List<HookDeclaration> hooks = new ArrayList<>();
        Map<Class<?>, Object> mixinInstances = new HashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Class<?> type = Class.forName(item.getString("callbackClass"), true, loader);
            if (type.getClassLoader() != loader)
                throw new IOException("Mixin resolved outside mod DEX: " + type.getName());
            JSONArray parameterNames = item.getJSONArray("parameters");
            Class<?>[] parameters = new Class<?>[parameterNames.length()];
            for (int p = 0; p < parameters.length; p++)
                parameters[p] = resolveJavaType(parameterNames.getString(p), loader);
            Method callback = type.getMethod(item.getString("callbackMethod"), parameters);
            if (callback.getReturnType() != void.class ||
                    Modifier.isStatic(callback.getModifiers()) != item.getBoolean("static"))
                throw new IOException("Invalid callback signature: " + callback);
            Object mixin = Modifier.isStatic(callback.getModifiers())
                    ? null : mixinInstances.computeIfAbsent(type, t -> {
                        try { return t.getDeclaredConstructor().newInstance(); }
                        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    });
            MethodSelector selector = new MethodSelector(item.getString("method"),
                    SelectorMode.valueOf(item.getString("mode")));
            hooks.add(new HookDeclaration(id, item.getString("assembly"), item.getString("namespace"),
                    item.getString("class"), selector, At.valueOf(item.getString("at")),
                    item.getInt("priority"), mixin, callback));
        }
        return hooks;
    }

    private static Class<?> resolveJavaType(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> Class.forName(name, false, loader);
        };
    }

    private static JSONObject readJson(ZipFile zip, String path) throws IOException, JSONException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("Missing " + path + " in mod archive");
        try (InputStream input = zip.getInputStream(entry)) {
            return new JSONObject(readLimited(input, MAX_JSON_BYTES));
        }
    }

    private static void requireSchema(JSONObject json, String source) throws IOException, JSONException {
        if (json.getInt("schemaVersion") != 1)
            throw new IOException("Unsupported schema version in " + source);
    }

    private static String readLimited(InputStream input, int maximum) throws IOException {
        // InputStream.readNBytes(int) is API 33+, but minSdk is 26; read manually instead.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, count);
            if (buffer.size() > maximum) throw new IOException("Frostbite metadata exceeds size limit");
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void copyLimited(InputStream input, File output, int maximum) throws IOException {
        try (var stream = Files.newOutputStream(output.toPath())) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new IOException("Frostbite mod package exceeds size limit");
                stream.write(buffer, 0, count);
            }
        } catch (IOException error) {
            Files.deleteIfExists(output.toPath());
            throw error;
        }
    }
}
