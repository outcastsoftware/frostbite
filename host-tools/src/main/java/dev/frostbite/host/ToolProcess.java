package dev.frostbite.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ToolProcess {
    private ToolProcess() {}

    public static String run(List<String> command, Path directory, Map<String, String> environment,
                             Consumer<String> progress) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (progress != null && !output.isBlank()) progress.accept(output.strip());
        if (exit != 0)
            throw new IOException("Tool exited with code " + exit + ": " +
                    command.getFirst() + "\n" + output);
        return output;
    }
}
