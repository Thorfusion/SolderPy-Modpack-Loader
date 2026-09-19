package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;

final class BootstrapEngine {
    private final RuntimePaths paths;
    private final LoaderConfig config;

    BootstrapEngine(RuntimePaths paths, LoaderConfig config) {
        this.paths = paths;
        this.config = config;
    }

    void run() throws LoaderException {
        try {
            Files.createDirectories(paths.dataDirectory());
        } catch (IOException e) {
            throw new LoaderException("Could not create loader data directory", e);
        }

        try (FileChannel channel = FileChannel.open(paths.dataDirectory().resolve("update.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = tryLock(channel)) {
            if (lock == null) {
                throw new LoaderException("Another solder.py loader update is already running");
            }
            updateLocked();
        } catch (IOException e) {
            throw new LoaderException("Could not lock the modpack update", e);
        }
    }

    private void updateLocked() throws LoaderException {
        InstalledState previous = InstalledState.load(paths.dataDirectory());
        boolean samePack = previous.matches(config);

        BootstrapClient client = new BootstrapClient(config);
        client.verifyCapability();
        BootstrapClient.ManifestResponse response = client.fetchManifest(
            samePack ? previous.build : null,
            samePack ? previous.etag : null);

        BootstrapManifest manifest;
        String etag;
        if (response.notModified) {
            if (previous.manifest == null) {
                throw new LoaderException("Server returned 304 but no cached manifest is available");
            }
            manifest = previous.manifest;
            manifest.validate(config.modpack, config.target);
            etag = previous.etag;
            LoaderLog.info("Manifest is unchanged; reconciling API selections");
        } else {
            manifest = response.manifest;
            etag = response.etag;
            LoaderLog.info("Resolved " + manifest.modpack.name + " build " + manifest.build.version);
        }

        Collection<Long> requestedMemberships = manifest.selectionPolicy.defaultMemberships;
        if ("client".equals(config.target) && OptionalSelectionScreen.hasSelectableOptions(manifest)) {
            if (OptionalSelectionScreen.isAvailable()) {
                requestedMemberships = OptionalSelectionScreen.choose(manifest);
            } else {
                LoaderLog.warn("A graphical environment is unavailable; using API optional defaults");
            }
        }
        List<BootstrapManifest.Package> selected =
            SelectionResolver.resolve(manifest, requestedMemberships);

        InstalledState next = new InstalledState();
        next.api = config.api;
        next.modpack = config.modpack;
        next.target = config.target;
        next.build = manifest.build.version;
        next.manifestHash = manifest.manifestHash;
        next.etag = etag;
        next.manifest = manifest;

        Installer installer = new Installer(
            paths.gameDirectory(), paths.dataDirectory(), config.limits, paths.loaderJar());
        installer.reconcile(selected, previous, next);
        LoaderLog.info("Modpack is ready (" + selected.size() + " managed package(s))");
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }
}
