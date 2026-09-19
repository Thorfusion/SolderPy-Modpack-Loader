package io.github.thorfusion.solderpyloader;

import com.juanmuscaria.relauncher.jvm.JavaRequirement;
import com.juanmuscaria.relauncher.jvm.JavaRuntimeProvider;

import java.util.Collections;
import java.util.List;

/** Declares the loader's Java 8 minimum without forcing Minecraft to a newer Java major. */
public final class SolderPyJavaRuntimeProvider implements JavaRuntimeProvider {
    private static final int[] SUPPORTED_MAJORS = {
        8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 24, 25, 26
    };

    @Override
    public List<JavaRequirement> javaRequirements() {
        try {
            RuntimePaths paths = RuntimePaths.locate(SolderPyJavaRuntimeProvider.class);
            if (!LoaderConfig.shouldRelaunch(paths.configFile())) {
                return Collections.emptyList();
            }
        } catch (LoaderException ignored) {
            // Let the command-line provider and agent report configuration/path errors.
        }
        return Collections.singletonList(JavaRequirement.majors(SUPPORTED_MAJORS));
    }

    @Override
    public int priority() {
        return -1000;
    }
}
