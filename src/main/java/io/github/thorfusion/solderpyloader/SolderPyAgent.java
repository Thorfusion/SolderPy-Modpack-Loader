package io.github.thorfusion.solderpyloader;

import java.lang.instrument.Instrumentation;
/** JVM premain entrypoint invoked by Relauncher before mod discovery. */
public final class SolderPyAgent {
    private SolderPyAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        RuntimePaths paths = null;
        LoaderConfig config = null;
        try {
            paths = RuntimePaths.locate(SolderPyAgent.class);
            config = LoaderConfig.load(paths.configFile());
            if (!config.enabled) {
                return;
            }
            config.resolveTarget(agentArguments);
            LoaderLog.info("Detected " + config.target + " launch");
            int exitCode = BootstrapWorker.launch(paths, config.target, config.bootstrapJava);
            if (exitCode == BootstrapWorker.EXIT_CANCELLED) {
                throw new LaunchCancelledException("solder.py modpack launch cancelled");
            }
            if (exitCode != BootstrapWorker.EXIT_SUCCESS) {
                throw new LoaderException(
                    "The solder.py bootstrap worker exited with code " + exitCode);
            }
        } catch (LaunchCancelledException error) {
            LoaderLog.info(error.getMessage());
            throw new IllegalStateException("solder.py modpack launch cancelled", error);
        } catch (Throwable error) {
            LoaderLog.error(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            throw new IllegalStateException("solder.py modpack bootstrap failed", error);
        }
    }
}
