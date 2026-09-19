package io.github.thorfusion.solderpyloader;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

final class RuntimePaths {
    private final Path gameDirectory;
    private final Path loaderJar;

    private RuntimePaths(Path gameDirectory, Path loaderJar) {
        this.gameDirectory = gameDirectory;
        this.loaderJar = loaderJar;
    }

    static RuntimePaths locate(Class<?> anchor) throws LoaderException {
        try {
            URI source = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jar = Paths.get(source).toAbsolutePath().normalize();
            Path parent = jar.getParent();
            if (parent == null || parent.getParent() == null ||
                !"mods".equalsIgnoreCase(parent.getFileName().toString())) {
                throw new LoaderException(
                    "The loader JAR must be placed directly in the instance's mods directory: " + jar);
            }
            return new RuntimePaths(parent.getParent().toAbsolutePath().normalize(), jar);
        } catch (LoaderException e) {
            throw e;
        } catch (Exception e) {
            throw new LoaderException("Could not locate the loader JAR", e);
        }
    }

    Path gameDirectory() {
        return gameDirectory;
    }

    Path loaderJar() {
        return loaderJar;
    }

    Path configFile() {
        return gameDirectory.resolve("config").resolve("solderpy-loader.json");
    }

    Path dataDirectory() {
        return gameDirectory.resolve(".solderpy-loader");
    }
}

