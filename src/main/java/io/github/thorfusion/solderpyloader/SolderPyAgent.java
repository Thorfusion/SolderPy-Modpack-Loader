package io.github.thorfusion.solderpyloader;

import java.lang.instrument.Instrumentation;

/** JVM premain entrypoint invoked by Relauncher before mod discovery. */
public final class SolderPyAgent {
    private SolderPyAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        try {
            RuntimePaths paths = RuntimePaths.locate(SolderPyAgent.class);
            LoaderConfig config = LoaderConfig.load(paths.configFile());
            if (!config.enabled) {
                return;
            }
            config.resolveTarget(agentArguments);
            LoaderLog.info("Detected " + config.target + " launch");
            new BootstrapEngine(paths, config).run();
        } catch (LaunchCancelledException error) {
            LoaderLog.info(error.getMessage());
            throw new IllegalStateException("solder.py modpack launch cancelled", error);
        } catch (Throwable error) {
            LoaderLog.error(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            if (!isFailOpen()) {
                throw new IllegalStateException("solder.py modpack bootstrap failed", error);
            }
            LoaderLog.warn("failOpen is enabled; continuing with the previously installed files");
        }
    }

    private static boolean isFailOpen() {
        try {
            RuntimePaths paths = RuntimePaths.locate(SolderPyAgent.class);
            return LoaderConfig.load(paths.configFile()).failOpen;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
