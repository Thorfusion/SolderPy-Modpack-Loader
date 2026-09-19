package io.github.thorfusion.solderpyloader;

import com.juanmuscaria.relauncher.CommandLineProvider;
import com.juanmuscaria.relauncher.jvm.JavaInstallation;
import com.juanmuscaria.relauncher.jvm.JavaInstallationDetector;
import com.juanmuscaria.relauncher.launch.Platform;
import com.juanmuscaria.relauncher.launch.Side;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Adds the loader as a premain agent during Relauncher's controlled restart. */
public final class SolderPyRelaunchProvider implements CommandLineProvider {
    private static final int MAX_WORKER_JAVA_CANDIDATES = 8;

    @Override
    public List<String> extraJvmArguments() {
        try {
            RuntimePaths paths = RuntimePaths.locate(SolderPyRelaunchProvider.class);
            if (!LoaderConfig.shouldRelaunch(paths.configFile())) {
                return Collections.emptyList();
            }
            applyLegacyForgeRelaunchWorkaround();
            String jar = paths.loaderJar().toString();
            if (jar.indexOf('=') >= 0) {
                LoaderLog.warn("The loader JAR path cannot contain '=': " + jar);
                return Collections.emptyList();
            }
            String target = detectTarget();
            List<String> arguments = new ArrayList<String>();
            int preferredMajor = preferredWorkerJavaMajor(paths.configFile());
            List<String> workerJavas = findPreferredJavas(preferredMajor);
            for (int index = 0; index < workerJavas.size(); index++) {
                arguments.add("-Dsolderpy.loader.java." + index + "=" + workerJavas.get(index));
            }
            if (target == null) {
                LoaderLog.warn(
                    "Relauncher could not detect the launch side; an explicit loader target " +
                    "will be required");
                arguments.add("-javaagent:" + jar);
                return arguments;
            }
            arguments.add("-javaagent:" + jar + "=" + target);
            return arguments;
        } catch (LoaderException e) {
            LoaderLog.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public int priority() {
        return -1000;
    }

    static String detectTarget() {
        try {
            Side side = Platform.current().side();
            if (side == Side.CLIENT) {
                return "client";
            }
            if (side == Side.SERVER) {
                return "server";
            }
        } catch (IllegalStateException ignored) {
            // The agent reports a useful configuration error after relaunch.
        }
        return null;
    }

    private static int preferredWorkerJavaMajor(Path configFile) {
        try {
            return LoaderConfig.load(configFile).bootstrapJavaMajor;
        } catch (LoaderException ignored) {
            // Premain reports invalid configuration after Relauncher adds the agent.
            return LoaderConfig.DEFAULT_BOOTSTRAP_JAVA_MAJOR;
        }
    }

    @SuppressWarnings("removal")
    private static void applyLegacyForgeRelaunchWorkaround() {
        SecurityManager manager = System.getSecurityManager();
        if (manager == null || !"cpw.mods.fml.relauncher.FMLSecurityManager".equals(
            manager.getClass().getName())) {
            return;
        }
        // Relauncher's Windows DLL strategy calls Runtime.exit, which Forge 1.7.10
        // deliberately traps. Its Java process fallback ends with Runtime.halt and
        // therefore performs the same handoff without being intercepted.
        System.setProperty("relauncher.test.disableNativeExec", "true");
        LoaderLog.info("Using Relauncher's process fallback for Forge 1.7.10 exit handling");
    }

    static List<String> findPreferredJavas(int preferredMajor) {
        try {
            JavaInstallation current = JavaInstallationDetector.probeHome(
                JavaInstallationDetector.currentJavaHome());
            if (current == null || current.version().getMajor() < 8) {
                return Collections.emptyList();
            }
            List<JavaInstallation> compatible = JavaInstallationDetector.detect().stream()
                .filter(candidate -> candidate.version().getMajor() >= 8)
                .filter(candidate -> sameArchitecture(candidate.arch(), current.arch()))
                .collect(java.util.stream.Collectors.toList());
            if (!compatible.contains(current)) {
                compatible.add(current);
            }
            compatible.sort(workerJavaComparator(preferredMajor));

            Set<String> executables = new LinkedHashSet<String>();
            for (JavaInstallation candidate : compatible) {
                executables.add(candidate.executable().toAbsolutePath().normalize().toString());
                if (executables.size() >= MAX_WORKER_JAVA_CANDIDATES) {
                    break;
                }
            }
            String currentExecutable = current.executable().toAbsolutePath().normalize().toString();
            if (!executables.contains(currentExecutable)) {
                if (executables.size() >= MAX_WORKER_JAVA_CANDIDATES) {
                    String last = null;
                    for (String executable : executables) {
                        last = executable;
                    }
                    executables.remove(last);
                }
                executables.add(currentExecutable);
            }

            JavaInstallation best = compatible.get(0);
            LoaderLog.info("Selected " + best.vendor().getDisplayName() + " Java " +
                best.version().getOriginalVersionString() + " for the bootstrap worker with " +
                Math.max(0, executables.size() - 1) + " runtime fallback(s)");
            return new ArrayList<String>(executables);
        } catch (Throwable error) {
            LoaderLog.warn("Could not inspect installed Java runtimes; " +
                "the bootstrap worker will use the current runtime");
            return Collections.emptyList();
        }
    }

    private static Comparator<JavaInstallation> workerJavaComparator(int preferredMajor) {
        return (left, right) -> {
            int rank = Integer.compare(
                workerJavaRank(left.version().getMajor(), preferredMajor),
                workerJavaRank(right.version().getMajor(), preferredMajor));
            if (rank != 0) {
                return rank;
            }
            return right.version().compareTo(left.version());
        };
    }

    private static int workerJavaRank(int major, int preferredMajor) {
        if (major == preferredMajor) {
            return 0;
        }
        if (major >= 17) {
            return 1;
        }
        if (major >= 11) {
            return 2;
        }
        return 3;
    }

    private static boolean sameArchitecture(String left, String right) {
        return normalizeArchitecture(left).equals(normalizeArchitecture(right));
    }

    private static String normalizeArchitecture(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        if ("amd64".equals(normalized) || "x86_64".equals(normalized)) {
            return "x86_64";
        }
        if ("aarch64".equals(normalized) || "arm64".equals(normalized)) {
            return "aarch64";
        }
        return normalized;
    }
}
