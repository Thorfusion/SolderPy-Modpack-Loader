package io.github.thorfusion.solderpyloader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DownloadManager {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int NETWORK_BUFFER_BYTES = 256 * 1024;
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 250_000_000L;

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

        return verifyDownloaded(item, downloadFile(item, directory, progress), progress);
    }

    DownloadedFile downloadFile(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress) throws LoaderException {

        return downloadFile(item, directory, progress, 0);
    }

    private DownloadedFile downloadFile(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress,
        int firstSource) throws LoaderException {

        List<BootstrapManifest.DownloadSource> sources = downloadSources(item.download);
        LoaderException failure = null;
        for (int index = firstSource; index < sources.size(); index++) {
            BootstrapManifest.DownloadSource source = sources.get(index);
            try {
                return downloadSource(item, directory, progress, source, index);
            } catch (LoaderException error) {
                failure = error;
                if (Thread.currentThread().isInterrupted()) {
                    throw error;
                }
                if (index + 1 < sources.size()) {
                    LoaderLog.warn("Download source " + source.provider + " failed for " +
                        item.name + "; trying " + sources.get(index + 1).provider);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        throw new LoaderException("Package " + item.name + " has no download source");
    }

    private DownloadedFile downloadSource(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress,
        BootstrapManifest.DownloadSource sourceMetadata,
        int sourceIndex) throws LoaderException {

        BootstrapManifest.Download metadata = item.download;
        HttpURLConnection connection = null;
        Path temporary = null;
        boolean responseConsumed = false;
        try {
            URI uri = validateUri(sourceMetadata.url);
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

            ProgressChannel source = new ProgressChannel(
                Channels.newChannel(new BufferedInputStream(
                    connection.getInputStream(), NETWORK_BUFFER_BYTES)),
                item, progress, progressMaximum);
            long transferLimit = maxBytes == Long.MAX_VALUE ? Long.MAX_VALUE : maxBytes + 1L;
            try (ProgressChannel input = source;
                 FileChannel output = FileChannel.open(temporary,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long position = 0;
                while (position < transferLimit) {
                    long transferred = output.transferFrom(
                        input, position, transferLimit - position);
                    if (transferred <= 0) {
                        responseConsumed = true;
                        break;
                    }
                    position += transferred;
                }
            }
            long count = source.bytesRead();
            if (count > maxBytes) {
                throw new LoaderException("Download exceeds the configured limit for " + item.name);
            }
            progress.downloadProgress(item, count, progressMaximum);
            if (metadata.filesize != null && count != metadata.filesize.longValue()) {
                throw new LoaderException("Downloaded size does not match package " + item.name);
            }
            progress.downloadCompleted(item, count);
            return new DownloadedFile(temporary, null, count, sourceIndex);
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
        } finally {
            // A fully consumed HttpURLConnection input stream is returned to Java's
            // keep-alive cache when it is closed. Calling disconnect here discarded
            // that connection and forced another TCP/TLS handshake for every package.
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    DownloadedFile verifyDownloaded(
        BootstrapManifest.Package item,
        DownloadedFile downloaded,
        BootstrapProgress progress) throws LoaderException {

        List<BootstrapManifest.DownloadSource> sources = downloadSources(item.download);
        DownloadedFile candidate = downloaded;
        while (true) {
            Path directory = candidate.path.getParent();
            try {
                return verifySource(item, candidate, progress);
            } catch (LoaderException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                int nextSource = candidate.sourceIndex + 1;
                if (nextSource >= sources.size()) {
                    throw failure;
                }
                LoaderLog.warn("Verification failed for " + sources.get(candidate.sourceIndex).provider +
                    " source of " + item.name + "; trying " + sources.get(nextSource).provider);
                candidate = downloadFile(item, directory, progress, nextSource);
            }
        }
    }

    private DownloadedFile verifySource(
        BootstrapManifest.Package item,
        DownloadedFile downloaded,
        BootstrapProgress progress) throws LoaderException {

        progress.verificationStarted(item, downloaded.bytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            long count = 0;
            long lastProgressNanos = 0;
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            try (InputStream input = new BufferedInputStream(
                Files.newInputStream(downloaded.path), HASH_BUFFER_BYTES)) {

                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Verification was cancelled");
                    }
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    count += read;
                    long now = System.nanoTime();
                    if (now - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                        progress.verificationProgress(item, count, downloaded.bytes);
                        lastProgressNanos = now;
                    }
                }
            }
            if (count != downloaded.bytes) {
                throw new LoaderException("Downloaded file changed before verification for package " +
                    item.name);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(item.download.md5)) {
                throw new LoaderException("MD5 mismatch for package " + item.name);
            }
            progress.verificationProgress(item, count, downloaded.bytes);
            progress.verificationCompleted(item, count);
            return new DownloadedFile(
                downloaded.path, actual, downloaded.bytes, downloaded.sourceIndex);
        } catch (LoaderException e) {
            deleteQuietly(downloaded.path);
            progress.verificationFailed(item, message(e));
            throw e;
        } catch (IOException e) {
            deleteQuietly(downloaded.path);
            LoaderException failure =
                new LoaderException("Could not verify package " + item.name, e);
            progress.verificationFailed(item, message(failure));
            throw failure;
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(downloaded.path);
            LoaderException failure =
                new LoaderException("This Java runtime does not provide MD5", e);
            progress.verificationFailed(item, message(failure));
            throw failure;
        }
    }

    private static List<BootstrapManifest.DownloadSource> downloadSources(
        BootstrapManifest.Download metadata) {

        Map<String, BootstrapManifest.DownloadSource> unique =
            new LinkedHashMap<String, BootstrapManifest.DownloadSource>();
        if (metadata.sources != null) {
            for (BootstrapManifest.DownloadSource source : metadata.sources) {
                if (source != null && source.url != null && !unique.containsKey(source.url)) {
                    unique.put(source.url, source);
                }
            }
        }
        if (metadata.url != null && !unique.containsKey(metadata.url)) {
            BootstrapManifest.DownloadSource fallback = new BootstrapManifest.DownloadSource();
            fallback.provider = "solder";
            fallback.url = metadata.url;
            unique.put(fallback.url, fallback);
        }
        return new ArrayList<BootstrapManifest.DownloadSource>(unique.values());
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

    /** Counts and samples progress while FileChannel performs the actual transfer. */
    private static final class ProgressChannel implements ReadableByteChannel {
        private final ReadableByteChannel delegate;
        private final BootstrapManifest.Package item;
        private final BootstrapProgress progress;
        private final long progressMaximum;
        private long count;
        private long lastProgressNanos;

        private ProgressChannel(
            ReadableByteChannel delegate,
            BootstrapManifest.Package item,
            BootstrapProgress progress,
            long progressMaximum) {

            this.delegate = delegate;
            this.item = item;
            this.progress = progress;
            this.progressMaximum = progressMaximum;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Download was cancelled");
            }
            int read = delegate.read(target);
            if (read <= 0) {
                return read;
            }
            count += read;

            long now = System.nanoTime();
            if (now - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                progress.downloadProgress(item, count, progressMaximum);
                lastProgressNanos = now;
            }
            return read;
        }

        long bytesRead() {
            return count;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    static final class DownloadedFile {
        final Path path;
        final String md5;
        final long bytes;
        final int sourceIndex;

        private DownloadedFile(Path path, String md5, long bytes, int sourceIndex) {
            this.path = path;
            this.md5 = md5;
            this.bytes = bytes;
            this.sourceIndex = sourceIndex;
        }
    }
}
