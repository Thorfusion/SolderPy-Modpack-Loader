package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class DownloadManager {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int COPY_BUFFER_BYTES = 256 * 1024;

    private final long maxBytes;

    DownloadManager(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    Path download(BootstrapManifest.Package item, Path directory) throws LoaderException {
        return downloadVerified(item, directory, BootstrapProgress.console()).path;
    }

    DownloadedFile downloadVerified(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress) throws LoaderException {

        BootstrapManifest.Download metadata = item.download;
        HttpURLConnection connection = null;
        Path temporary = null;
        try {
            URI uri = validateUri(metadata.url);
            long expected = metadata.filesize == null ? -1 : metadata.filesize.longValue();
            progress.downloadStarted(item, expected);
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "download-", ".part");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "solderpy-loader/0.1");
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new LoaderException("Download for " + item.name + " failed with HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes ||
                (metadata.filesize != null && declared >= 0 && declared != metadata.filesize.longValue())) {
                throw new LoaderException("Download size is invalid for package " + item.name);
            }
            long progressMaximum = expected >= 0 ? expected : declared;
            progress.downloadProgress(item, 0, progressMaximum);

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            long count = 0;
            long lastProgressNanos = 0;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Download was cancelled");
                    }
                    count += read;
                    if (count > maxBytes) {
                        throw new LoaderException("Download exceeds the configured limit for " + item.name);
                    }
                    md5.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    long now = System.nanoTime();
                    if (now - lastProgressNanos >= 100_000_000L) {
                        progress.downloadProgress(item, count, progressMaximum);
                        lastProgressNanos = now;
                    }
                }
            }
            progress.downloadProgress(item, count, progressMaximum);
            if (metadata.filesize != null && count != metadata.filesize.longValue()) {
                throw new LoaderException("Downloaded size does not match package " + item.name);
            }
            String actual = hex(md5.digest());
            if (!actual.equalsIgnoreCase(metadata.md5)) {
                throw new LoaderException("MD5 mismatch for package " + item.name);
            }
            progress.downloadCompleted(item, count);
            return new DownloadedFile(temporary, actual, count);
        } catch (LoaderException e) {
            deleteQuietly(temporary);
            progress.downloadFailed(item, message(e));
            throw e;
        } catch (IOException e) {
            deleteQuietly(temporary);
            LoaderException failure =
                new LoaderException("Could not download package " + item.name, e);
            progress.downloadFailed(item, message(failure));
            throw failure;
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(temporary);
            LoaderException failure =
                new LoaderException("This Java runtime does not provide MD5", e);
            progress.downloadFailed(item, message(failure));
            throw failure;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static URI validateUri(String value) throws LoaderException {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) ||
                "::1".equals(host) || "[::1]".equals(host);
            if (host == null || uri.getUserInfo() != null ||
                !("https".equalsIgnoreCase(scheme) ||
                    (loopback && "http".equalsIgnoreCase(scheme)))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new LoaderException("Package download URL must use HTTPS (or loopback HTTP): " + value, e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    static final class DownloadedFile {
        final Path path;
        final String md5;
        final long bytes;

        private DownloadedFile(Path path, String md5, long bytes) {
            this.path = path;
            this.md5 = md5;
            this.bytes = bytes;
        }
    }
}
