package io.github.thorfusion.solderpyloader;

import java.lang.instrument.Instrumentation;
import java.util.List;

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
            new BootstrapEngine(paths, config).run();
        } catch (LaunchCancelledException error) {
            LoaderLog.info(error.getMessage());
            throw new IllegalStateException("solder.py modpack launch cancelled", error);
        } catch (Throwable error) {
            LoaderLog.error(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            if (!(error instanceof RecoverableBootstrapException) ||
                config == null || !config.failOpen || paths == null ||
                !hasIntactPreviousInstall(paths, config)) {
                throw new IllegalStateException("solder.py modpack bootstrap failed", error);
            }
            LoaderLog.warn("failOpen is enabled and the previous files are intact; continuing without the update");
        }
    }

    private static boolean hasIntactPreviousInstall(RuntimePaths paths, LoaderConfig config) {
        try {
            InstalledState state = InstalledState.load(paths.dataDirectory());
            if (!state.matches(config) || state.manifest == null ||
                state.selectedMemberships == null) {
                return false;
            }
            state.manifest.validate(config.modpack, config.target);
            List<BootstrapManifest.Package> selected =
                SelectionResolver.resolve(state.manifest, state.selectedMemberships);
            Installer installer = new Installer(
                paths.gameDirectory(), paths.dataDirectory(), config.limits, paths.loaderJar());
            return installer.isInstalledStateIntact(selected, state);
        } catch (Throwable verificationFailure) {
            LoaderLog.warn("Cannot use failOpen because the previous installation could not be verified");
            return false;
        }
    }
}
