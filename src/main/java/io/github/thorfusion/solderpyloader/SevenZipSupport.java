package io.github.thorfusion.solderpyloader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Optional 7-Zip command-line acceleration discovered from the host system. */
final class SevenZipSupport {
    private static final long EXTRACTION_TIMEOUT_MINUTES = 15;

    private final Path executable;

    private SevenZipSupport(Path executable) {
        this.executable = executable;
    }

    static SevenZipSupport discover() {
        for (Path candidate : candidates()) {
            if (isUsable(candidate)) {
                Path resolved = candidate.toAbsolutePath().normalize();
                LoaderLog.info("Using installed 7-Zip for ZIP extraction: " + resolved);
                return new SevenZipSupport(resolved);
            }
        }
        LoaderLog.info("7-Zip was not found; using the built-in ZIP extractor");
        return null;
    }

    boolean extract(Path archive, Path outputDirectory) throws LoaderException {
        Path log = null;
        Process process = null;
        try {
            Files.createDirectories(outputDirectory);
            Path logDirectory = outputDirectory.toAbsolutePath().normalize().getParent();
            log = Files.createTempFile(logDirectory, "solderpy-7zip-", ".log");
            List<String> command = new ArrayList<String>();
            command.add(executable.toString());
            command.add("x");
            command.add(archive.toAbsolutePath().normalize().toString());
            command.add("-o" + outputDirectory.toAbsolutePath().normalize());
            command.add("-y");
            command.add("-bd");
            command.add("-bb0");
            command.add("-mmt=on");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            process = builder.start();
            if (!process.waitFor(EXTRACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                waitAfterDestroy(process);
                LoaderLog.warn("7-Zip extraction timed out; retrying with the built-in extractor");
                return false;
            }
            if (process.exitValue() != 0) {
                LoaderLog.warn("7-Zip exited with code " + process.exitValue() +
                    "; retrying with the built-in extractor");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new LoaderException("Interrupted while extracting with 7-Zip", e);
        } catch (IOException | RuntimeException e) {
            LoaderLog.warn("Could not run 7-Zip; retrying with the built-in extractor");
            return false;
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void waitAfterDestroy(Process process) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Path> candidates() {
        Set<Path> result = new LinkedHashSet<Path>();
        addConfigured(result, System.getProperty("solderpy.loader.7zip"));
        addConfigured(result, System.getenv("SOLDERPY_7ZIP"));

        boolean windows = isWindows();
        if (windows) {
            addWindowsInstall(result, System.getenv("ProgramW6432"));
            addWindowsInstall(result, System.getenv("ProgramFiles"));
            addWindowsInstall(result, System.getenv("ProgramFiles(x86)"));
        } else {
            result.add(Paths.get("/usr/bin/7zz"));
            result.add(Paths.get("/usr/local/bin/7zz"));
            result.add(Paths.get("/usr/bin/7z"));
            result.add(Paths.get("/usr/local/bin/7z"));
            result.add(Paths.get("/usr/bin/7za"));
            result.add(Paths.get("/usr/local/bin/7za"));
        }

        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            String[] names = windows
                ? new String[] {"7z.exe", "7zz.exe", "7za.exe"}
                : new String[] {"7zz", "7z", "7za"};
            for (String directory : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory == null || directory.trim().isEmpty()) {
                    continue;
                }
                for (String name : names) {
                    try {
                        String normalizedDirectory = directory.trim();
                        if (normalizedDirectory.length() >= 2 &&
                            normalizedDirectory.startsWith("\"") &&
                            normalizedDirectory.endsWith("\"")) {
                            normalizedDirectory = normalizedDirectory.substring(
                                1, normalizedDirectory.length() - 1);
                        }
                        result.add(Paths.get(normalizedDirectory).resolve(name));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return new ArrayList<Path>(result);
    }

    private static void addConfigured(Set<Path> result, String value) {
        if (value != null && !value.trim().isEmpty()) {
            try {
                result.add(Paths.get(value.trim()));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void addWindowsInstall(Set<Path> result, String root) {
        if (root != null && !root.trim().isEmpty()) {
            try {
                result.add(Paths.get(root.trim(), "7-Zip", "7z.exe"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static boolean isUsable(Path candidate) {
        try {
            return Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
