package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;

/** Replaces the universal factory in the platform distribution at build time. */
final class LoaderDistribution {
    private LoaderDistribution() {
    }

    static void validateConfiguration(LoaderConfig config) throws LoaderException {
        if (!config.isFileBased() || config.api != null) {
            throw new LoaderException(
                "This file-only build requires manifest or manifestFile in " +
                "config/solderpy-loader.json; the webserver API is unavailable. " +
                "Use the universal build for API-based packs.");
        }
        if (config.manifestVerification != null || config.clientId != null) {
            throw new LoaderException(
                "The file-only build does not support manifestVerification or clientId; " +
                "use the universal build for signed manifests or API settings.");
        }
    }

    static void verifyLocalManifest(LoaderConfig config, JsonObject document) throws LoaderException {
        validateConfiguration(config);
    }

    static ManifestSource manifestSource(RuntimePaths paths, LoaderConfig config)
        throws LoaderException {

        validateConfiguration(config);
        return new LocalManifestClient(paths.gameDirectory(), config);
    }
}
