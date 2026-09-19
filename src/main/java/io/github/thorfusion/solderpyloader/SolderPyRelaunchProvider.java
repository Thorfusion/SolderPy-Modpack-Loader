package io.github.thorfusion.solderpyloader;

import com.juanmuscaria.relauncher.CommandLineProvider;
import com.juanmuscaria.relauncher.launch.Platform;
import com.juanmuscaria.relauncher.launch.Side;

import java.util.Collections;
import java.util.List;

/** Adds the loader as a premain agent during Relauncher's controlled restart. */
public final class SolderPyRelaunchProvider implements CommandLineProvider {
    @Override
    public List<String> extraJvmArguments() {
        try {
            RuntimePaths paths = RuntimePaths.locate(SolderPyRelaunchProvider.class);
            if (!LoaderConfig.shouldRelaunch(paths.configFile())) {
                return Collections.emptyList();
            }
            String jar = paths.loaderJar().toString();
            if (jar.indexOf('=') >= 0) {
                LoaderLog.warn("The loader JAR path cannot contain '=': " + jar);
                return Collections.emptyList();
            }
            String target = detectTarget();
            if (target == null) {
                LoaderLog.warn(
                    "Relauncher could not detect the launch side; an explicit loader target " +
                    "will be required");
                return Collections.singletonList("-javaagent:" + jar);
            }
            return Collections.singletonList("-javaagent:" + jar + "=" + target);
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
}
