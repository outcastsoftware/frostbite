package dev.frostbite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads metadata out of an APK without decoding it — used to prefill the launch Activity. */
public final class ApkInspector {
    // aapt/aapt2 badging prints: launchable-activity: name='com.foo.Bar'  label='…' icon='…'
    private static final Pattern LAUNCHABLE =
            Pattern.compile("launchable-activity:\\s*name='([^']+)'");
    private ApkInspector() {}

    /**
     * The fully-qualified launch Activity class of an APK, via {@code aapt dump badging}, or
     * {@code null} if it cannot be determined (no aapt, or no launcher activity declared).
     */
    public static String detectLaunchActivity(Path apk) throws IOException, InterruptedException {
        if (!Files.isRegularFile(apk)) throw new IOException("Choose a game APK");
        AndroidSdkTools sdk = AndroidSdkTools.find();
        if (sdk.aapt() == null) return null;   // detection unavailable; caller lets the user type it
        String output = ToolProcess.run(
                List.of(sdk.aapt().toString(), "dump", "badging", apk.toAbsolutePath().toString()),
                null, Map.of(), null);
        Matcher matcher = LAUNCHABLE.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }
}
