package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class LoaderConfig {
    static final long DEFAULT_MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    static final long DEFAULT_MAX_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L;
    static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 100_000;
    static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 4;
    static final int MAX_CONCURRENT_DOWNLOADS = 16;
    static final int DEFAULT_MAX_CONCURRENT_EXTRACTIONS = 1;
    static final int MAX_CONCURRENT_EXTRACTIONS = 16;
    static final int DEFAULT_BOOTSTRAP_JAVA_MAJOR = 25;

    boolean enabled = true;
    String api;
    String modpack;
    String build = "recommended";
    String target = "auto";
    String clientId;
    String bootstrapJava;
    Integer bootstrapJavaMajor = DEFAULT_BOOTSTRAP_JAVA_MAJOR;
    boolean failOpen;
    @SerializedName("selections") JsonElement legacySelections;
    Limits limits = new Limits();

    transient URI apiUri;

    static final class Limits {
        long maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES;
        long maxExpandedBytes = DEFAULT_MAX_EXPANDED_BYTES;
        int maxArchiveEntries = DEFAULT_MAX_ARCHIVE_ENTRIES;
        int maxConcurrentDownloads = DEFAULT_MAX_CONCURRENT_DOWNLOADS;
        int maxConcurrentExtractions = DEFAULT_MAX_CONCURRENT_EXTRACTIONS;
    }

    static LoaderConfig load(Path file) throws LoaderException {
        if (!Files.isRegularFile(file)) {
            throw new LoaderException("Missing bootstrap configuration: " + file);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LoaderConfig config = JsonSupport.GSON.fromJson(reader, LoaderConfig.class);
            if (config == null) {
                throw new LoaderException("Bootstrap configuration is empty: " + file);
            }
            config.validate();
            return config;
        } catch (JsonParseException e) {
            throw new LoaderException("Invalid JSON in bootstrap configuration: " + file, e);
        } catch (IOException e) {
            throw new LoaderException("Could not read bootstrap configuration: " + file, e);
        }
    }

    static boolean shouldRelaunch(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return load(file).enabled;
        } catch (LoaderException e) {
            // Relaunch so premain can report the complete, fatal configuration error.
            return true;
        }
    }

    private void validate() throws LoaderException {
        if (!enabled) {
            return;
        }
        requireText(api, "api");
        requireText(modpack, "modpack");
        requireText(build, "build");
        requireText(target, "target");

        api = api.trim();
        modpack = modpack.trim();
        build = build.trim();
        target = target.trim().toLowerCase(Locale.ROOT);
        if (!modpack.matches("[A-Za-z0-9_-]+")) {
            throw new LoaderException("modpack must be a Solder slug containing letters, digits, '_' or '-'");
        }
        if (!"auto".equals(target) && !"client".equals(target) && !"server".equals(target)) {
            throw new LoaderException("target must be auto, client, or server");
        }
        if (clientId != null && clientId.trim().isEmpty()) {
            clientId = null;
        } else if (clientId != null) {
            clientId = clientId.trim();
        }
        if (bootstrapJava != null && bootstrapJava.trim().isEmpty()) {
            bootstrapJava = null;
        } else if (bootstrapJava != null) {
            bootstrapJava = bootstrapJava.trim();
        }
        if (bootstrapJavaMajor == null) {
            bootstrapJavaMajor = DEFAULT_BOOTSTRAP_JAVA_MAJOR;
        }
        if (bootstrapJavaMajor < 8) {
            throw new LoaderException("bootstrapJavaMajor must be Java 8 or newer");
        }
        if (legacySelections != null) {
            throw new LoaderException(
                "The selections configuration field is no longer supported; " +
                "optional definitions and defaults must be supplied by the bootstrap API");
        }
        if (limits == null) {
            limits = new Limits();
        }
        if (limits.maxDownloadBytes <= 0 || limits.maxDownloadBytes > DEFAULT_MAX_DOWNLOAD_BYTES) {
            throw new LoaderException("limits.maxDownloadBytes must be between 1 and " +
                DEFAULT_MAX_DOWNLOAD_BYTES);
        }
        if (limits.maxExpandedBytes <= 0 || limits.maxExpandedBytes > DEFAULT_MAX_EXPANDED_BYTES) {
            throw new LoaderException("limits.maxExpandedBytes must be between 1 and " +
                DEFAULT_MAX_EXPANDED_BYTES);
        }
        if (limits.maxArchiveEntries <= 0 ||
            limits.maxArchiveEntries > DEFAULT_MAX_ARCHIVE_ENTRIES) {
            throw new LoaderException("limits.maxArchiveEntries must be between 1 and " +
                DEFAULT_MAX_ARCHIVE_ENTRIES);
        }
        if (limits.maxConcurrentDownloads <= 0 ||
            limits.maxConcurrentDownloads > MAX_CONCURRENT_DOWNLOADS) {
            throw new LoaderException("limits.maxConcurrentDownloads must be between 1 and " +
                MAX_CONCURRENT_DOWNLOADS);
        }
        if (limits.maxConcurrentExtractions <= 0 ||
            limits.maxConcurrentExtractions > MAX_CONCURRENT_EXTRACTIONS) {
            throw new LoaderException("limits.maxConcurrentExtractions must be between 1 and " +
                MAX_CONCURRENT_EXTRACTIONS);
        }

        try {
            apiUri = normalizeApiUri(api);
            api = apiUri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new LoaderException("Invalid Solder API URL: " + api, e);
        }
    }

    void resolveTarget(String detectedTarget) throws LoaderException {
        if (!"auto".equals(target)) {
            return;
        }
        String detected = detectedTarget == null
            ? ""
            : detectedTarget.trim().toLowerCase(Locale.ROOT);
        if (!"client".equals(detected) && !"server".equals(detected)) {
            throw new LoaderException(
                "Relauncher could not determine whether this is a client or server launch; " +
                "set target explicitly in config/solderpy-loader.json");
        }
        target = detected;
    }

    private static URI normalizeApiUri(String value) {
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null ||
            uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("The API URL must be an absolute URL without credentials, query, or fragment");
        }
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) ||
            "::1".equals(host) || "[::1]".equals(host);
        if (!"https".equalsIgnoreCase(scheme) && !(loopback && "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("HTTPS is required outside loopback development");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/api/";
        } else {
            if (!path.endsWith("/")) {
                path += "/";
            }
            if (!path.endsWith("/api/")) {
                throw new IllegalArgumentException("The API URL path must end in /api/");
            }
        }
        try {
            return new URI(scheme.toLowerCase(Locale.ROOT), null, host,
                uri.getPort(), path, null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void requireText(String value, String field) throws LoaderException {
        if (value == null || value.trim().isEmpty()) {
            throw new LoaderException("Missing required configuration field: " + field);
        }
    }
}
