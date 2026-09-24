package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class InstalledState {
    int version = 1;
    String api;
    String modpack;
    String target;
    String source;
    String platform;
    List<Long> launcherOwnedMemberships;
    String build;
    String manifestHash;
    String etag;
    BootstrapManifest manifest;
    JsonObject signedManifest;
    List<Long> selectedMemberships;
    List<RememberedSelection> selectedOptions;
    Map<String, FileMetadata> launcherFileMetadata =
        new LinkedHashMap<String, FileMetadata>();
    Map<String, Receipt> receipts = new LinkedHashMap<String, Receipt>();

    static final class FileMetadata {
        String path;
        long size;
        long lastModifiedNanos;

        FileMetadata() {
        }

        FileMetadata(String path, long size, long lastModifiedNanos) {
            this.path = path;
            this.size = size;
            this.lastModifiedNanos = lastModifiedNanos;
        }
    }

    static final class RememberedSelection {
        String groupKey;
        String packageName;

        RememberedSelection() {
        }

        RememberedSelection(String groupKey, String packageName) {
            this.groupKey = groupKey;
            this.packageName = packageName;
        }
    }

    static final class Receipt {
        String slug;
        String version;
        String md5;
        String installKey;
        List<String> files = new ArrayList<String>();
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        Map<String, FileMetadata> fileMetadata =
            new LinkedHashMap<String, FileMetadata>();

        Receipt() {
        }

        Receipt(String slug, String version, String md5, String installKey, List<String> files) {
            this(slug, version, md5, installKey, files,
                new LinkedHashMap<String, String>());
        }

        Receipt(String slug, String version, String md5, String installKey, List<String> files,
                Map<String, String> hashes) {
            this(slug, version, md5, installKey, files, hashes,
                new LinkedHashMap<String, FileMetadata>());
        }

        Receipt(String slug, String version, String md5, String installKey, List<String> files,
                Map<String, String> hashes, Map<String, FileMetadata> fileMetadata) {
            this.slug = slug;
            this.version = version;
            this.md5 = md5;
            this.installKey = installKey;
            this.files = new ArrayList<String>(files);
            this.hashes = new LinkedHashMap<String, String>(hashes);
            this.fileMetadata = new LinkedHashMap<String, FileMetadata>(fileMetadata);
        }
    }

    static InstalledState load(Path dataDirectory) throws LoaderException {
        Path file = dataDirectory.resolve("state.json");
        if (!Files.exists(file)) {
            return new InstalledState();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            InstalledState state = JsonSupport.GSON.fromJson(reader, InstalledState.class);
            if (state == null || state.version != 1) {
                throw new LoaderException("Unsupported or empty installed state: " + file);
            }
            if (state.receipts == null) {
                state.receipts = new LinkedHashMap<String, Receipt>();
            }
            if (state.launcherFileMetadata == null) {
                state.launcherFileMetadata =
                    new LinkedHashMap<String, FileMetadata>();
            }
            for (Receipt receipt : state.receipts.values()) {
                if (receipt != null && receipt.hashes == null) {
                    receipt.hashes = new LinkedHashMap<String, String>();
                }
                if (receipt != null && receipt.fileMetadata == null) {
                    receipt.fileMetadata =
                        new LinkedHashMap<String, FileMetadata>();
                }
            }
            return state;
        } catch (JsonParseException e) {
            throw new LoaderException("Installed state is invalid JSON: " + file, e);
        } catch (IOException e) {
            throw new LoaderException("Could not read installed state: " + file, e);
        }
    }

    void save(Path dataDirectory) throws LoaderException {
        try {
            Files.createDirectories(dataDirectory);
            Path destination = dataDirectory.resolve("state.json");
            Path temporary = Files.createTempFile(dataDirectory, "state-", ".tmp");
            boolean moved = false;
            try {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    JsonSupport.GSON.toJson(this, writer);
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException e) {
            throw new LoaderException("Could not save installed state", e);
        }
    }

    boolean matches(LoaderConfig config) {
        return config.api.equals(api) && config.modpack.equals(modpack) &&
            config.target.equals(target) && Objects.equals(config.source, source) &&
            Objects.equals(config.platform, platform) &&
            Objects.equals(config.launcherOwnedMemberships,
                launcherOwnedMemberships) &&
            manifest != null &&
            Objects.equals(config.source, manifest.source);
    }
}
