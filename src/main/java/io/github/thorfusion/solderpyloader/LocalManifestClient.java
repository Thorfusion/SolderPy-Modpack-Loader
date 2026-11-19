package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

/** Reads a manifest shipped with the pack; never resolves metadata over the network. */
final class LocalManifestClient implements ManifestSource {
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;

    private final Path gameDirectory;
    private final LoaderConfig config;

    LocalManifestClient(Path gameDirectory, LoaderConfig config) {
        this.gameDirectory = gameDirectory;
        this.config = config;
    }

    @Override
    public BootstrapManifest load(InstalledState previous, InstalledState next, boolean samePack)
        throws LoaderException {

        return read();
    }

    @Override
    public BootstrapManifest previousManifest(InstalledState previous) throws LoaderException {
        // A missing or invalid current file must never fall back to cached metadata.
        return read();
    }

    BootstrapManifest read() throws LoaderException {
        try {
            JsonObject document = config.manifest == null
                ? readFile() : config.manifest.deepCopy();
            if (document == null) {
                throw new LoaderException("Local bootstrap manifest is empty");
            }
            LoaderDistribution.verifyLocalManifest(config, document);
            BootstrapManifest manifest = JsonSupport.GSON.fromJson(document, BootstrapManifest.class);
            if (manifest == null) {
                throw new LoaderException("Local bootstrap manifest is empty");
            }
            // Hash the actual local content rather than trusting a declared manifest_hash.
            // This also detects edits to optionals or URLs that keep the same build version.
            manifest.manifestHash = contentHash(document);
            manifest.validate(config.modpack, config.target, config.source);
            return manifest;
        } catch (JsonParseException | IllegalStateException e) {
            throw new LoaderException("Local bootstrap manifest contains invalid JSON or fields", e);
        }
    }

    private JsonObject readFile() throws LoaderException {
        String relative = config.manifestFile.replace("{target}", config.target);
        Path file = PathSafety.resolve(gameDirectory, relative);
        PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
        try (InputStream input = Files.newInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() > MAX_JSON_BYTES - read) {
                    throw new LoaderException("Local bootstrap manifest exceeds " + MAX_JSON_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return JsonSupport.GSON.fromJson(
                new String(output.toByteArray(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new LoaderException("Could not read local bootstrap manifest: " + relative, e);
        }
    }

    private static String contentHash(JsonObject document) throws LoaderException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(JsonSupport.GSON.toJson(document).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (GeneralSecurityException e) {
            throw new LoaderException("Could not hash the local bootstrap manifest", e);
        }
    }
}
