package io.github.thorfusion.solderpyloader;

import java.lang.reflect.Method;

/** Runs in a separate JVM with legacy Commons IO ahead of the shaded loader. */
public final class PackagedArtifactProbe {
    private PackagedArtifactProbe() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> legacyIo = Class.forName("org.apache.commons.io.IOUtils");
        String legacySource = legacyIo.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!legacySource.contains("commons-io-2.4")) {
            throw new AssertionError("Legacy Commons IO was not first on the test classpath: " + legacySource);
        }

        Class<?> zipFile = Class.forName(
            "io.github.thorfusion.solderpyloader.internal.compress.archivers.zip.ZipFile");
        Method builder = zipFile.getMethod("builder");
        Object value = builder.invoke(null);
        if (value == null) {
            throw new AssertionError("Relocated Commons Compress did not create its ZIP builder");
        }

        Class<?> relocatedIo = Class.forName(
            "io.github.thorfusion.solderpyloader.internal.io.IOUtils");
        if (relocatedIo.getProtectionDomain().getCodeSource().getLocation() == null) {
            throw new AssertionError("Relocated Commons IO has no code source");
        }
    }
}
