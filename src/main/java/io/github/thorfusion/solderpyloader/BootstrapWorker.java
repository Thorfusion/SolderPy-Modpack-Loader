package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Runs bootstrap work after JVM startup so legacy HotSpot can JIT-compile TLS and I/O code. */
public final class BootstrapWorker {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_CANCELLED = 2;

    private BootstrapWorker() {
    }

    public static void main(String[] arguments) {
        int exitCode = execute(arguments);
        if (exitCode != EXIT_SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int launch(RuntimePaths paths, String target, String configuredJava)
        throws LoaderException {

        List<Path> executables = javaExecutables(configuredJava);
        IOException lastStartFailure = null;
        for (int index = 0; index < executables.size(); index++) {
            Path executable = executables.get(index);
            LoaderLog.info("Starting bootstrap worker with " + executable);
            List<String> command = workerCommand(executable, paths.loaderJar(), target);

            Process process;
            try {
                process = new ProcessBuilder(command)
                    .directory(paths.gameDirectory().toFile())
                    .inheritIO()
                    .start();
            } catch (IOException e) {
                lastStartFailure = e;
                LoaderLog.warn("Could not start bootstrap worker with " + executable +
                    (index + 1 < executables.size() ? "; trying the next Java runtime" : ""));
                continue;
            }

            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new LoaderException("Interrupted while waiting for the bootstrap worker", e);
            }
        }
        throw new LoaderException(
            "Could not start the solder.py bootstrap worker with any detected Java runtime",
            lastStartFailure);
    }

    private static List<String> workerCommand(Path executable, Path loaderJar, String target) {
        List<String> command = new ArrayList<String>();
        command.add(executable.toString());
        if (Boolean.getBoolean("solderpy.loader.debug")) {
            command.add("-Dsolderpy.loader.debug=true");
        }
        command.add("-cp");
        command.add(loaderJar.toString());
        command.add(BootstrapWorker.class.getName());
        command.add(target);
        return command;
    }

    private static int execute(String[] arguments) {
        RuntimePaths paths = null;
        LoaderConfig config = null;
        try {
            if (arguments.length != 1) {
                throw new LoaderException("Bootstrap worker requires exactly one launch target");
            }
            paths = RuntimePaths.locate(BootstrapWorker.class);
            config = LoaderConfig.load(paths.configFile());
            if (!config.enabled) {
                return EXIT_SUCCESS;
            }
            config.resolveTarget(arguments[0]);
            new BootstrapEngine(paths, config).run();
            return EXIT_SUCCESS;
        } catch (LaunchCancelledException error) {
            LoaderLog.info(error.getMessage());
            return EXIT_CANCELLED;
        } catch (Throwable error) {
            LoaderLog.error(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            if (error instanceof RecoverableBootstrapException &&
                config != null && config.failOpen && paths != null &&
                hasIntactPreviousInstall(paths, config)) {

                LoaderLog.warn(
                    "failOpen is enabled and the previous files are intact; continuing without the update");
                return EXIT_SUCCESS;
            }
            return EXIT_FAILED;
        }
    }

    private static boolean hasIntactPreviousInstall(RuntimePaths paths, LoaderConfig config) {
        try {
            InstalledState state = InstalledState.load(paths.dataDirectory());
            if (!state.matches(config) || state.manifest == null ||
                state.selectedMemberships == null) {
                return false;
            }
            state.manifest.validate(config.modpack, config.target, config.source);
            List<BootstrapManifest.Package> selected =
                SelectionResolver.resolve(state.manifest, state.selectedMemberships);
            Installer installer = new Installer(
                paths.gameDirectory(), paths.dataDirectory(), config.limits, paths.loaderJar());
            return installer.isInstalledStateIntact(selected, state);
        } catch (Throwable verificationFailure) {
            LoaderLog.warn(
                "Cannot use failOpen because the previous installation could not be verified");
            return false;
        }
    }

    static Path javaExecutable(String configuredJava) throws LoaderException {
        return javaExecutables(configuredJava).get(0);
    }

    static List<Path> javaExecutables(String configuredJava) throws LoaderException {
        Set<Path> candidates = new LinkedHashSet<Path>();
        addCandidate(candidates, configuredJava, "configured bootstrapJava");
        addCandidate(candidates, System.getProperty("solderpy.loader.java"),
            "bootstrap Java property");
        for (int index = 0; index < 16; index++) {
            String candidate = System.getProperty("solderpy.loader.java." + index);
            if (candidate == null) {
                break;
            }
            addCandidate(candidates, candidate, "detected bootstrap Java " + index);
        }

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            addCandidate(candidates, Paths.get(javaHome).resolve("bin").toString(),
                "current Java runtime");
        }
        if (candidates.isEmpty()) {
            throw new LoaderException("Could not locate any Java runtime for the bootstrap worker");
        }
        return new ArrayList<Path>(candidates);
    }

    private static void addCandidate(Set<Path> candidates, String requested, String source) {
        if (requested == null || requested.trim().isEmpty()) {
            return;
        }
        try {
            candidates.add(resolveExecutable(
                Paths.get(requested.trim()).toAbsolutePath().normalize()));
        } catch (LoaderException error) {
            LoaderLog.warn("Ignoring unavailable " + source + ": " + requested.trim());
        }
    }

    private static Path resolveExecutable(Path configured) throws LoaderException {
        boolean windows = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path executable = configured;
        if (Files.isDirectory(executable)) {
            if (Files.isDirectory(executable.resolve("bin"))) {
                executable = executable.resolve("bin");
            }
            executable = executable.resolve(windows ? "javaw.exe" : "java");
        } else if (windows && "java.exe".equalsIgnoreCase(
            executable.getFileName() == null ? "" : executable.getFileName().toString())) {

            Path graphical = executable.resolveSibling("javaw.exe");
            if (Files.isRegularFile(graphical)) {
                executable = graphical;
            }
        }
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        if (windows) {
            Path consoleExecutable = executable.resolveSibling("java.exe");
            if (Files.isRegularFile(consoleExecutable)) {
                return consoleExecutable;
            }
        }
        throw new LoaderException("Could not locate a Java executable from " + configured);
    }
}
