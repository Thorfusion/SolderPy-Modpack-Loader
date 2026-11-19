package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Locale;

/** The universal distribution supports both local manifests and the signed API. */
final class LoaderDistribution {
    private LoaderDistribution() {
    }

    static void validateConfiguration(LoaderConfig config) throws LoaderException {
        if (config.manifestVerification != null) {
            ManifestVerifier.validateConfiguration(config.manifestVerification);
        }
        if (config.api != null) {
            try {
                config.apiUri = normalizeApiUri(config.api);
                config.api = config.apiUri.toASCIIString();
            } catch (IllegalArgumentException e) {
                throw new LoaderException("Invalid Solder API URL: " + config.api, e);
            }
        }
    }

    static void verifyLocalManifest(LoaderConfig config, JsonObject document) throws LoaderException {
        if (config.manifestVerification != null) {
            ManifestVerifier.verify(document, config.manifestVerification);
        }
    }

    static ManifestSource manifestSource(RuntimePaths paths, LoaderConfig config)
        throws LoaderException {

        return config.isFileBased()
            ? new LocalManifestClient(paths.gameDirectory(), config)
            : new BootstrapClient(config);
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
}
