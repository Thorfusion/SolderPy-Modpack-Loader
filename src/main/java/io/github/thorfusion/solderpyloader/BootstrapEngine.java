package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                throw new LoaderException(
                    "Another SolderPy Modpack Loader update is already running");
            }
            updateLocked();
        } catch (IOException e) {
            throw new LoaderException("Could not lock the modpack update", e);
        }
    }

    private void updateLocked() throws LoaderException {
        BootstrapProgress progress = BootstrapProgress.create(
            "client".equals(config.target) && OptionalSelectionScreen.isAvailable());
        try {
            progress.beginPreparation("Checking for interrupted updates...");
            InstallTransaction.recoverPending(
                paths.gameDirectory(), paths.dataDirectory(), paths.loaderJar());
            InstalledState previous = InstalledState.load(paths.dataDirectory());
            boolean samePack = previous.matches(config);

            progress.beginPreparation("Loading modpack information...");
            InstalledState next = new InstalledState();
            UpdatePlan plan = prepareUpdate(previous, next, samePack, progress);

            next.api = config.api;
            next.manifestFile = config.manifestFile;
            next.modpack = config.modpack;
            next.target = config.target;
            next.source = config.source;
            next.platform = config.platform;
            next.launcherOwnedMemberships = config.launcherOwnedMemberships == null
                ? null : new ArrayList<Long>(config.launcherOwnedMemberships);
            next.build = plan.manifest.build.version;
            next.manifestHash = plan.manifest.manifestHash;
            next.manifest = plan.manifest;
            next.selectedMemberships = new ArrayList<Long>(plan.requestedMemberships);
            next.selectedOptions = rememberedSelections(
                plan.manifest, plan.requestedMemberships);

            progress.beginPreparation("Checking installed modpack content...");
            boolean alwaysHashFiles =
                plan.manifest.requiresFullHashing(config.alwaysHashFiles);
            Installer installer = new Installer(
                paths.gameDirectory(), paths.dataDirectory(), config.limits,
                paths.loaderJar(), progress, alwaysHashFiles);
            installer.reconcile(
                plan.selected, plan.manifest.packages, previous, next,
                plan.manifest.removesUnlistedModFiles());
            LoaderLog.info("Modpack is ready (" + plan.selected.size() +
                " managed package(s))");
        } catch (LaunchCancelledException e) {
            throw e;
        } catch (LoaderException e) {
            progress.failed(e);
            throw e;
        } catch (RuntimeException e) {
            progress.failed(e);
            throw e;
        } finally {
            progress.close();
        }
    }

    private UpdatePlan prepareUpdate(
        InstalledState previous, InstalledState next, boolean samePack, BootstrapProgress progress)
        throws LoaderException {

        try {
            ManifestSource client = LoaderDistribution.manifestSource(paths, config);
            BootstrapManifest manifest = client.load(previous, next, samePack);
            boolean manifestUnchanged = samePack && sameManifest(previous, manifest);
            LoaderLog.info("Resolved " + manifest.modpack.name + " build " + manifest.build.version);
            manifest.applyConfiguredOwnership(config.launcherOwnedMemberships);

            boolean clientSide = "client".equals(config.target);
            boolean reuseSelections = clientSide && canReuseSavedSelections(
                manifest, previous, samePack && manifestUnchanged);
            Collection<Long> requestedMemberships;
            if (reuseSelections) {
                requestedMemberships = new ArrayList<Long>(previous.selectedMemberships);
                LoaderLog.info(
                    "Manifest is unchanged; reusing saved optional selections");
            } else {
                requestedMemberships = initialMemberships(
                    manifest, previous, samePack && clientSide);
            }
            if (clientSide && !reuseSelections &&
                OptionalSelectionScreen.hasSelectableOptions(manifest)) {
                if (OptionalSelectionScreen.isAvailable()) {
                    progress.waitingForOptionalSelection();
                    requestedMemberships = OptionalSelectionScreen.choose(
                        manifest, requestedMemberships, progress.ownerWindow());
                    progress.beginPreparation("Applying optional selections...");
                } else {
                    LoaderLog.warn("A graphical environment is unavailable; using saved choices and manifest defaults");
                }
            }
            List<BootstrapManifest.Package> selected =
                SelectionResolver.resolve(manifest, requestedMemberships);
            return new UpdatePlan(manifest, requestedMemberships, selected);
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

    static boolean sameManifest(InstalledState previous, BootstrapManifest current) {
        return previous != null && current != null && previous.manifestHash != null &&
            current.manifestHash != null &&
            previous.manifestHash.equalsIgnoreCase(current.manifestHash) &&
            previous.build != null && current.build != null &&
            previous.build.equals(current.build.version);
    }

    static boolean canReuseSavedSelections(
        BootstrapManifest manifest, InstalledState previous, boolean manifestUnchanged) {

        if (!manifestUnchanged || previous == null || previous.manifest == null ||
            previous.selectedMemberships == null) {
            return false;
        }
        try {
            SelectionResolver.resolve(manifest, previous.selectedMemberships);
            return true;
        } catch (LoaderException e) {
            LoaderLog.warn(
                "Saved optional choices are no longer valid; opening the selection screen");
            return false;
        } catch (RuntimeException e) {
            LoaderLog.warn(
                "Saved optional choices are invalid; opening the selection screen");
            return false;
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
            Map<OptionKey, Boolean> savedChoices = savedChoices(previous);
            Set<Long> currentOptions = OptionalSelectionScreen.selectableMemberships(manifest);
            for (BootstrapManifest.Package item : manifest.packages) {
                if (!currentOptions.contains(item.membershipId)) {
                    continue;
                }
                Boolean selected = savedChoices.get(OptionKey.of(item));
                if (selected == null) {
                    continue;
                }
                if (selected.booleanValue()) {
                    initial.add(item.membershipId);
                } else {
                    initial.remove(item.membershipId);
                }
            }
            SelectionResolver.resolve(manifest, initial);
            return initial;
        } catch (LoaderException e) {
            LoaderLog.warn("Saved optional choices no longer satisfy the manifest rules; using manifest defaults");
            return new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        } catch (RuntimeException e) {
            LoaderLog.warn("Saved optional choices are invalid; using manifest defaults");
            return new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        }
    }

    static List<InstalledState.RememberedSelection> rememberedSelections(
        BootstrapManifest manifest, Collection<Long> selectedMemberships) {

        Set<Long> selectable = OptionalSelectionScreen.selectableMemberships(manifest);
        Set<Long> selected = new LinkedHashSet<Long>(selectedMemberships);
        List<InstalledState.RememberedSelection> result =
            new ArrayList<InstalledState.RememberedSelection>();
        for (BootstrapManifest.Package item : manifest.packages) {
            if (selectable.contains(item.membershipId) && selected.contains(item.membershipId)) {
                result.add(new InstalledState.RememberedSelection(
                    item.selection.groupKey, item.name));
            }
        }
        return result;
    }

    private static Map<OptionKey, Boolean> savedChoices(InstalledState previous) {
        Map<OptionKey, Boolean> result = new LinkedHashMap<OptionKey, Boolean>();
        Set<Long> previousOptions =
            OptionalSelectionScreen.selectableMemberships(previous.manifest);

        if (previous.selectedOptions == null) {
            Set<Long> selected = new LinkedHashSet<Long>(previous.selectedMemberships);
            for (BootstrapManifest.Package item : previous.manifest.packages) {
                if (previousOptions.contains(item.membershipId)) {
                    result.put(OptionKey.of(item), selected.contains(item.membershipId));
                }
            }
            return result;
        }

        Set<OptionKey> selected = new LinkedHashSet<OptionKey>();
        for (InstalledState.RememberedSelection remembered : previous.selectedOptions) {
            if (remembered != null && remembered.packageName != null) {
                selected.add(new OptionKey(remembered.groupKey, remembered.packageName));
            }
        }
        for (BootstrapManifest.Package item : previous.manifest.packages) {
            if (previousOptions.contains(item.membershipId)) {
                OptionKey key = OptionKey.of(item);
                result.put(key, selected.contains(key));
            }
        }
        return result;
    }

    private static final class OptionKey {
        private final String groupKey;
        private final String packageName;

        private OptionKey(String groupKey, String packageName) {
            this.groupKey = groupKey;
            this.packageName = packageName;
        }

        private static OptionKey of(BootstrapManifest.Package item) {
            return new OptionKey(item.selection.groupKey, item.name);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OptionKey)) {
                return false;
            }
            OptionKey key = (OptionKey) other;
            return Objects.equals(groupKey, key.groupKey) &&
                Objects.equals(packageName, key.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupKey, packageName);
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
        private final Collection<Long> requestedMemberships;
        private final List<BootstrapManifest.Package> selected;

        private UpdatePlan(
            BootstrapManifest manifest,
            Collection<Long> requestedMemberships,
            List<BootstrapManifest.Package> selected) {

            this.manifest = manifest;
            this.requestedMemberships = new ArrayList<Long>(requestedMemberships);
            this.selected = selected;
        }
    }
}
