package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class BootstrapClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final String USER_AGENT = "solderpy-loader/0.1";

    private final LoaderConfig config;

    BootstrapClient(LoaderConfig config) {
        this.config = config;
    }

    void verifyCapability() throws LoaderException {
        Response response = get(config.apiUri, null, MAX_JSON_BYTES);
        if (response.status != 200) {
            throw statusError("Solder API discovery", response);
        }
        try {
            JsonObject root = JsonSupport.GSON.fromJson(response.body, JsonObject.class);
            JsonObject capabilities = root == null ? null : root.getAsJsonObject("capabilities");
            boolean supported = capabilities != null &&
                capabilities.has("bootstrap_manifest") &&
                capabilities.get("bootstrap_manifest").getAsBoolean() &&
                capabilities.has("bootstrap_schema") &&
                capabilities.get("bootstrap_schema").getAsInt() == 1;
            if (!supported) {
                throw new LoaderException("The Solder server does not advertise bootstrap schema 1");
            }
        } catch (JsonParseException e) {
            throw new LoaderException("Solder API discovery returned invalid JSON", e);
        } catch (RuntimeException e) {
            throw new LoaderException("Solder API discovery has invalid capability data", e);
        }
    }

    ManifestResponse fetchManifest(String installedBuild, String etag) throws LoaderException {
        StringBuilder url = new StringBuilder(config.apiUri.toASCIIString());
        url.append("modpack/").append(encode(config.modpack))
            .append('/').append(encode(config.build)).append("/bootstrap")
            .append("?target=").append(encode(config.target));
        if (installedBuild != null && !installedBuild.isEmpty()) {
            url.append("&from=").append(encode(installedBuild));
        }
        if (config.clientId != null) {
            url.append("&cid=").append(encode(config.clientId));
        }

        Response response = get(URI.create(url.toString()), etag, MAX_JSON_BYTES);
        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return ManifestResponse.notModified();
        }
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw statusError("Bootstrap manifest", response);
        }
        try {
            BootstrapManifest manifest = JsonSupport.GSON.fromJson(response.body, BootstrapManifest.class);
            if (manifest == null) {
                throw new LoaderException("Bootstrap manifest response is empty");
            }
            manifest.validate(config.modpack, config.target);
            return ManifestResponse.modified(manifest, validEtag(response.etag));
        } catch (JsonParseException e) {
            throw new LoaderException("Bootstrap manifest returned invalid JSON", e);
        }
    }

    private static Response get(URI uri, String etag, int maxBytes) throws LoaderException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (etag != null && validEtag(etag) != null) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = input == null ? new byte[0] : readLimited(input, maxBytes);
            return new Response(status, new String(bytes, StandardCharsets.UTF_8),
                connection.getHeaderField("ETag"));
        } catch (IOException e) {
            throw new LoaderException("HTTP request failed for " + uri, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("Response exceeds " + limit + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String encode(String value) throws LoaderException {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new LoaderException("Could not encode bootstrap URL", e);
        }
    }

    private static String validEtag(String value) {
        if (value == null || value.length() > 200 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private static LoaderException statusError(String operation, Response response) {
        String body = response.body == null ? "" : response.body.trim();
        if (body.length() > 500) {
            body = body.substring(0, 500) + "...";
        }
        return new LoaderException(operation + " request failed with HTTP " + response.status +
            (body.isEmpty() ? "" : ": " + body));
    }

    static final class ManifestResponse {
        final boolean notModified;
        final BootstrapManifest manifest;
        final String etag;

        private ManifestResponse(boolean notModified, BootstrapManifest manifest, String etag) {
            this.notModified = notModified;
            this.manifest = manifest;
            this.etag = etag;
        }

        static ManifestResponse notModified() {
            return new ManifestResponse(true, null, null);
        }

        static ManifestResponse modified(BootstrapManifest manifest, String etag) {
            return new ManifestResponse(false, manifest, etag);
        }
    }

    private static final class Response {
        final int status;
        final String body;
        final String etag;

        Response(int status, String body, String etag) {
            this.status = status;
            this.body = body;
            this.etag = etag;
        }
    }
}
