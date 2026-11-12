package dev.frostbite.host;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Fetches {@code frostbite-runtime.zip} from the project's GitHub Releases and caches it, so a
 * modder never downloads or picks the runtime by hand. The cached copy is reused on later runs.
 */
public final class RuntimeProvider {
    /** The release tag the Desktop ships against. Bump when a new runtime is published. */
    public static final String DEFAULT_TAG = "v0.2.0";
    private static final String REPO = "outcastsoftware/frostbite";
    private static final String ASSET = "frostbite-runtime.zip";
    private static final long MIN_BYTES = 4096;            // a real runtime zip is far larger
    private RuntimeProvider() {}

    /** The cached runtime for {@link #DEFAULT_TAG}, downloading it once if needed. */
    public static Path ensureRuntime(Consumer<String> progress) throws IOException, InterruptedException {
        return ensureRuntime(DEFAULT_TAG, progress);
    }

    /** The cached runtime for a specific release tag, downloading it once if needed. */
    public static Path ensureRuntime(String tag, Consumer<String> progress)
            throws IOException, InterruptedException {
        Path cached = cacheDir().resolve(tag).resolve(ASSET);
        if (Files.isRegularFile(cached) && Files.size(cached) >= MIN_BYTES) {
            if (progress != null) progress.accept("Using cached Frostbite runtime " + tag);
            return cached;
        }
        if (progress != null) progress.accept("Downloading Frostbite runtime " + tag + "…");
        URI uri = URI.create("https://github.com/" + REPO + "/releases/download/" + tag + "/" + ASSET);
        Files.createDirectories(cached.getParent());
        Path temp = Files.createTempFile(cached.getParent(), "runtime-", ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "Frostbite-Desktop")
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200)
                throw new IOException("Runtime download failed (HTTP " + response.statusCode() +
                        ") for " + uri);
            if (Files.size(temp) < MIN_BYTES)
                throw new IOException("Downloaded runtime is too small; the release may be missing " + ASSET);
            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        if (progress != null) progress.accept("Frostbite runtime " + tag + " ready");
        return cached;
    }

    /** {@code %LOCALAPPDATA%/Frostbite/runtime} on Windows, else {@code ~/.frostbite/runtime}. */
    public static Path cacheDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank())
            return Path.of(local, "Frostbite", "runtime");
        return Path.of(System.getProperty("user.home"), ".frostbite", "runtime");
    }
}
