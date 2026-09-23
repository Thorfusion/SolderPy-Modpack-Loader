package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Persists the worker's user-facing failure reason for launchers and support. */
final class FailureReport {
    static final String FILE_NAME = "last-error.txt";
    private static final int MAX_READ_BYTES = 64 * 1024;

    private FailureReport() {
    }

    static String describe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "Unknown bootstrap failure" : error.toString();
        }
        if (error instanceof RecoverableBootstrapException) {
            return message + System.lineSeparator() + System.lineSeparator() +
                "The previously installed modpack files were left intact.";
        }
        if (error instanceof UnrecoverableBootstrapException) {
            return message + System.lineSeparator() + System.lineSeparator() +
                "The previous installation could not be fully restored. " +
                "Do not continue until the transaction recovery error is resolved.";
        }
        return message;
    }

    static void write(Path dataDirectory, Throwable error) {
        if (dataDirectory == null) {
            return;
        }
        Path report = dataDirectory.resolve(FILE_NAME);
        String text = "SolderPy Modpack Loader failed at " + Instant.now() +
            System.lineSeparator() + System.lineSeparator() + describe(error) +
            System.lineSeparator();
        try {
            Files.createDirectories(dataDirectory);
            Files.write(report, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            LoaderLog.warn("Failure details were saved to " + report.toAbsolutePath().normalize());
        } catch (IOException | RuntimeException writeFailure) {
            LoaderLog.warn("Could not save the bootstrap failure report");
        }
    }

    static String read(Path dataDirectory) {
        if (dataDirectory == null) {
            return null;
        }
        Path report = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(report) || Files.size(report) > MAX_READ_BYTES) {
                return null;
            }
            return new String(Files.readAllBytes(report), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static void clear(Path dataDirectory) {
        if (dataDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(dataDirectory.resolve(FILE_NAME));
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
