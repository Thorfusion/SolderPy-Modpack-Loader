package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        UpdatePlan plan = prepareUpdate(previous, samePack);

        InstalledState next = new InstalledState();
        next.api = config.api;
        next.modpack = config.modpack;
        next.target = config.target;
        next.build = plan.manifest.build.version;
        next.manifestHash = plan.manifest.manifestHash;
        next.etag = plan.etag;
        next.manifest = plan.manifest;
        next.selectedMemberships = new ArrayList<Long>(plan.requestedMemberships);

        Installer installer = new Installer(
            paths.gameDirectory(), paths.dataDirectory(), config.limits, paths.loaderJar());
        installer.reconcile(plan.selected, previous, next);
        LoaderLog.info("Modpack is ready (" + plan.selected.size() + " managed package(s))");
    }

    private UpdatePlan prepareUpdate(InstalledState previous, boolean samePack)
        throws LoaderException {

        try {
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
                LoaderLog.info("Manifest is unchanged; reconciling selections");
            } else {
                manifest = response.manifest;
                etag = response.etag;
                LoaderLog.info("Resolved " + manifest.modpack.name + " build " + manifest.build.version);
            }

            Collection<Long> requestedMemberships = initialMemberships(
                manifest, previous, samePack && "client".equals(config.target));
            if ("client".equals(config.target) && OptionalSelectionScreen.hasSelectableOptions(manifest)) {
                if (OptionalSelectionScreen.isAvailable()) {
                    requestedMemberships = OptionalSelectionScreen.choose(manifest, requestedMemberships);
                } else {
                    LoaderLog.warn("A graphical environment is unavailable; using saved choices and API defaults");
                }
            }
            List<BootstrapManifest.Package> selected =
                SelectionResolver.resolve(manifest, requestedMemberships);
            return new UpdatePlan(manifest, etag, requestedMemberships, selected);
        } catch (LaunchCancelledException e) {
            throw e;
        } catch (RecoverableBootstrapException e) {
            throw e;
        } catch (LoaderException e) {
            throw new RecoverableBootstrapException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new RecoverableBootstrapException("Could not prepare the modpack update", e);
        }
    }

    static Collection<Long> initialMemberships(
        BootstrapManifest manifest, InstalledState previous, boolean restoreSavedChoices) {

        Set<Long> initial = new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        if (!restoreSavedChoices || previous == null || previous.manifest == null ||
            previous.selectedMemberships == null) {
            return initial;
        }

        try {
            Set<Long> existingOptions = OptionalSelectionScreen.selectableMemberships(manifest);
            existingOptions.retainAll(
                OptionalSelectionScreen.selectableMemberships(previous.manifest));
            Set<Long> saved = new LinkedHashSet<Long>(previous.selectedMemberships);
            for (Long membership : existingOptions) {
                if (saved.contains(membership)) {
                    initial.add(membership);
                } else {
                    initial.remove(membership);
                }
            }
            SelectionResolver.resolve(manifest, initial);
            return initial;
        } catch (LoaderException e) {
            LoaderLog.warn("Saved optional choices no longer satisfy the API rules; using API defaults");
            return new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        } catch (RuntimeException e) {
            LoaderLog.warn("Saved optional choices are invalid; using API defaults");
            return new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static final class UpdatePlan {
        private final BootstrapManifest manifest;
        private final String etag;
        private final Collection<Long> requestedMemberships;
        private final List<BootstrapManifest.Package> selected;

        private UpdatePlan(
            BootstrapManifest manifest,
            String etag,
            Collection<Long> requestedMemberships,
            List<BootstrapManifest.Package> selected) {

            this.manifest = manifest;
            this.etag = etag;
            this.requestedMemberships = new ArrayList<Long>(requestedMemberships);
            this.selected = selected;
        }
    }
}
