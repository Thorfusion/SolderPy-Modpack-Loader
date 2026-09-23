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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class DownloadManager {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int NETWORK_BUFFER_BYTES = 256 * 1024;
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 250_000_000L;
    private static final int MAX_SOURCE_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 250L;

    private final long maxBytes;
    private final Path cacheDirectory;

    DownloadManager(long maxBytes) {
        this(maxBytes, null);
    }

    DownloadManager(long maxBytes, Path cacheDirectory) {
        this.maxBytes = maxBytes;
        this.cacheDirectory = cacheDirectory == null ? null :
            cacheDirectory.toAbsolutePath().normalize();
    }

    long additionalBytesRequired(List<BootstrapManifest.Package> packages) {
        long required = 0L;
        for (BootstrapManifest.Package item : packages) {
            long expected = item.download.filesize == null
                ? maxBytes : item.download.filesize.longValue();
            long reclaimable = 0L;
            if (cacheDirectory != null) {
                Path cached = cachePath(item.download);
                try {
                    if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
                        reclaimable = Math.min(expected, Files.size(cached));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Conservatively reserve the complete artifact.
                }
            }
            required = DiskSpace.add(required, Math.max(0L, expected - reclaimable));
        }
        return required;
    }

    void pruneCache(List<BootstrapManifest.Package> selected) {
        if (cacheDirectory == null || !Files.exists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Set<Path> retained = new HashSet<Path>();
        for (BootstrapManifest.Package item : selected) {
            retained.add(cachePath(item.download));
        }
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(cacheDirectory)) {
            entries = paths.sorted(java.util.Comparator.reverseOrder())
                .collect(Collectors.toList());
        } catch (IOException | RuntimeException e) {
            LoaderLog.warn("Could not inspect the download cache for pruning");
            return;
        }
        boolean failed = false;
        for (Path entry : entries) {
            try {
                if (entry.equals(cacheDirectory)) {
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    }
                } else if (!retained.contains(entry.toAbsolutePath().normalize())) {
                    Files.deleteIfExists(entry);
                }
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
        }
        if (failed) {
            LoaderLog.warn("Could not prune old files from the download cache");
        }
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

        DownloadedFile cached = cachedDownload(item, progress);
        return cached == null ? downloadFile(item, directory, progress, 0, 1) : cached;
    }

    private DownloadedFile downloadFile(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress,
        int firstSource,
        int firstAttempt) throws LoaderException {

        List<BootstrapManifest.DownloadSource> sources = downloadSources(item.download);
        LoaderException failure = null;
        BootstrapManifest.DownloadSource lastSource = null;
        int lastAttempt = 0;
        int lastAttemptLimit = 0;
        for (int index = firstSource; index < sources.size(); index++) {
            BootstrapManifest.DownloadSource source = sources.get(index);
            int attempts = sourceAttempts(item.download, sources, index);
            int startingAttempt = index == firstSource ? firstAttempt : 1;
            for (int attempt = startingAttempt; attempt <= attempts; attempt++) {
                lastSource = source;
                lastAttempt = attempt;
                lastAttemptLimit = attempts;
                try {
                    return downloadSource(
                        item, directory, progress, source, index, attempt);
                } catch (LoaderException error) {
                    failure = error;
                    if (Thread.currentThread().isInterrupted()) {
                        throw error;
                    }
                    boolean willRetry = attempt < attempts || index + 1 < sources.size();
                    progress.downloadAttemptFailed(item, sourceDescription(source),
                        attempt, attempts, message(error), willRetry);
                    if (attempt < attempts) {
                        LoaderLog.warn("Download attempt " + attempt + " / " + attempts +
                            " from " + sourceName(source) + " failed for " + item.name +
                            "; retrying");
                        waitBeforeRetry(attempt + 1);
                    }
                }
            }
            if (index + 1 < sources.size()) {
                LoaderLog.warn("Download source " + sourceName(source) + " failed for " +
                    item.name + "; trying " + sourceName(sources.get(index + 1)));
            }
        }
        if (failure != null) {
            LoaderException detailed = new LoaderException(
                "Download failed for " + packageName(item) + ". Last attempt used " +
                sourceDescription(lastSource) + " (attempt " + lastAttempt + " / " +
                lastAttemptLimit + "): " + message(failure), failure);
            progress.downloadFailed(item, message(detailed));
            throw detailed;
        }
        throw new LoaderException("Package " + packageName(item) + " has no download source");
    }

    private DownloadedFile downloadSource(
        BootstrapManifest.Package item,
        Path directory,
        BootstrapProgress progress,
        BootstrapManifest.DownloadSource sourceMetadata,
        int sourceIndex,
        int sourceAttempt) throws LoaderException {

        BootstrapManifest.Download metadata = item.download;
        HttpURLConnection connection = null;
        Path temporary = null;
        boolean persistentPartial = cacheDirectory != null;
        boolean responseConsumed = false;
        try {
            URI uri = validateUri(sourceMetadata.url);
            long expected = metadata.filesize == null ? -1 : metadata.filesize.longValue();
            if (persistentPartial) {
                temporary = partialPath(item, sourceMetadata);
                Files.createDirectories(temporary.getParent());
            } else {
                Files.createDirectories(directory);
                temporary = Files.createTempFile(directory, "download-", ".part");
            }

            long resumed = reusablePartialSize(temporary, expected);
            if (persistentPartial && expected >= 0 && resumed == expected &&
                Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                progress.downloadStarted(item, expected, resumed);
                progress.downloadCompleted(item, resumed);
                return new DownloadedFile(
                    temporary, null, resumed, sourceIndex, sourceAttempt, persistentPartial);
            }

            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "solderpy-loader/0.3.2");
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (resumed > 0) {
                connection.setRequestProperty("Range", "bytes=" + resumed + "-");
            }
            int status = connection.getResponseCode();
            long responseTotal = -1;
            if (resumed > 0 && status == HttpURLConnection.HTTP_PARTIAL) {
                responseTotal = validateContentRange(
                    connection.getHeaderField("Content-Range"), resumed, expected, item.name);
            } else if (status == HttpURLConnection.HTTP_OK) {
                // This server does not support Range. Reuse the same response but
                // safely replace the partial file instead of appending to it.
                resumed = 0;
            } else if (resumed > 0 && status == 416 &&
                unsatisfiedRangeIsComplete(
                    connection.getHeaderField("Content-Range"), resumed, expected)) {

                progress.downloadStarted(item, expected, resumed);
                progress.downloadCompleted(item, resumed);
                return new DownloadedFile(
                    temporary, null, resumed, sourceIndex, sourceAttempt, persistentPartial);
            } else {
                throw new LoaderException("Download for " + item.name + " failed with HTTP " + status);
            }

            long declared = connection.getContentLengthLong();
            long declaredTotal = declared < 0 ? -1 : safeTotal(resumed, declared);
            if (declaredTotal > maxBytes || responseTotal > maxBytes ||
                (declaredTotal >= 0 && responseTotal >= 0 &&
                    declaredTotal != responseTotal) ||
                (expected >= 0 && declaredTotal >= 0 && declaredTotal != expected) ||
                (expected >= 0 && responseTotal >= 0 && responseTotal != expected)) {
                throw new LoaderException("Download size is invalid for package " + item.name);
            }
            long progressMaximum = expected >= 0 ? expected :
                (responseTotal >= 0 ? responseTotal : declaredTotal);
            progress.downloadStarted(item, progressMaximum, resumed);
            progress.downloadProgress(item, resumed, progressMaximum);

            ProgressChannel source = new ProgressChannel(
                Channels.newChannel(new BufferedInputStream(
                    connection.getInputStream(), NETWORK_BUFFER_BYTES)),
                item, progress, progressMaximum, resumed);
            long transferLimit = maxBytes == Long.MAX_VALUE ? Long.MAX_VALUE : maxBytes + 1L;
            try (ProgressChannel input = source;
                 FileChannel output = resumed == 0
                     ? FileChannel.open(temporary, StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                     : FileChannel.open(temporary, StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE)) {
                long position = resumed;
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
            return new DownloadedFile(
                temporary, null, count, sourceIndex, sourceAttempt, persistentPartial);
        } catch (LoaderException e) {
            retainPartialOrDelete(temporary, metadata.filesize, persistentPartial);
            throw e;
        } catch (IOException e) {
            retainPartialOrDelete(temporary, metadata.filesize, persistentPartial);
            LoaderException failure =
                new LoaderException("Could not download package " + item.name, e);
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
                int nextSource;
                int nextAttempt;
                if (candidate.sourceIndex < 0) {
                    LoaderLog.warn("Cached download failed verification for " + item.name +
                        "; downloading a fresh copy");
                    nextSource = 0;
                    nextAttempt = 1;
                } else {
                    int attempts = sourceAttempts(
                        item.download, sources, candidate.sourceIndex);
                    if (candidate.sourceAttempt < attempts) {
                        nextSource = candidate.sourceIndex;
                        nextAttempt = candidate.sourceAttempt + 1;
                        LoaderLog.warn("Verification failed for " +
                            sourceName(sources.get(candidate.sourceIndex)) + " source of " +
                            item.name + "; retrying attempt " + nextAttempt + " / " + attempts);
                        waitBeforeRetry(nextAttempt);
                    } else {
                        nextSource = candidate.sourceIndex + 1;
                        nextAttempt = 1;
                    }
                }
                if (nextSource >= sources.size()) {
                    throw failure;
                }
                if (nextSource != candidate.sourceIndex && candidate.sourceIndex >= 0) {
                    LoaderLog.warn("Verification failed for " +
                        sourceName(sources.get(candidate.sourceIndex)) + " source of " +
                        item.name + "; trying " + sourceName(sources.get(nextSource)));
                }
                candidate = downloadFile(
                    item, directory, progress, nextSource, nextAttempt);
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
            Path verifiedPath = storeVerifiedDownload(item.download, downloaded.path);
            return new DownloadedFile(
                verifiedPath, actual, downloaded.bytes, downloaded.sourceIndex,
                downloaded.sourceAttempt,
                cacheDirectory != null && verifiedPath.equals(cachePath(item.download)));
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

    private DownloadedFile cachedDownload(
        BootstrapManifest.Package item, BootstrapProgress progress) {

        if (cacheDirectory == null) {
            return null;
        }
        Path cached = cachePath(item.download);
        try {
            if (!Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            long size = Files.size(cached);
            if (size > maxBytes ||
                (item.download.filesize != null &&
                    size != item.download.filesize.longValue())) {
                deleteQuietly(cached);
                return null;
            }
            progress.downloadCacheHit(item, size);
            return new DownloadedFile(cached, null, size, -1, 0, true);
        } catch (IOException | RuntimeException e) {
            LoaderLog.warn("Could not reuse cached download for " + item.name +
                "; downloading it again");
            deleteQuietly(cached);
            return null;
        }
    }

    private Path storeVerifiedDownload(
        BootstrapManifest.Download metadata, Path source) {

        if (cacheDirectory == null) {
            return source;
        }
        Path destination = cachePath(metadata);
        if (source.toAbsolutePath().normalize().equals(destination)) {
            return destination;
        }
        try {
            Files.createDirectories(cacheDirectory);
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                try {
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ordinaryMoveFailure) {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(source);
                }
            }
            return destination;
        } catch (IOException | RuntimeException e) {
            LoaderLog.warn("Could not store verified download in the persistent cache; " +
                "continuing with the temporary copy");
            return source;
        }
    }

    private Path cachePath(BootstrapManifest.Download metadata) {
        return cacheDirectory.resolve(metadata.md5.toLowerCase(Locale.ROOT) + ".artifact");
    }

    private Path partialPath(
        BootstrapManifest.Package item,
        BootstrapManifest.DownloadSource source) throws LoaderException {

        String sourceKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            String identity = item.name + "\0" + source.url;
            sourceKey = hex(digest.digest(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new LoaderException("This Java runtime does not provide MD5", e);
        }
        return cacheDirectory.resolve("partials").resolve(
            item.download.md5.toLowerCase(Locale.ROOT) + "-" + sourceKey + ".part");
    }

    private long reusablePartialSize(Path partial, long expected) throws IOException {
        if (!Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(partial);
            return 0;
        }
        long size = Files.size(partial);
        if (size < 0 || size > maxBytes || (expected >= 0 && size > expected)) {
            Files.deleteIfExists(partial);
            return 0;
        }
        return size;
    }

    private void retainPartialOrDelete(
        Path partial, Long expected, boolean persistentPartial) {

        if (!persistentPartial || partial == null) {
            deleteQuietly(partial);
            return;
        }
        try {
            long size = Files.size(partial);
            if (size <= 0 || size > maxBytes ||
                (expected != null && size >= expected.longValue())) {
                deleteQuietly(partial);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
        }
    }

    private static long validateContentRange(
        String value, long expectedStart, long expectedTotal, String packageName)
        throws LoaderException {

        try {
            if (value == null || !value.startsWith("bytes ")) {
                throw new IllegalArgumentException();
            }
            int dash = value.indexOf('-', 6);
            int slash = value.indexOf('/', dash + 1);
            if (dash < 0 || slash < 0 ||
                Long.parseLong(value.substring(6, dash)) != expectedStart) {
                throw new IllegalArgumentException();
            }
            long end = Long.parseLong(value.substring(dash + 1, slash));
            long total = Long.parseLong(value.substring(slash + 1));
            if (end < expectedStart || total <= end ||
                (expectedTotal >= 0 && total != expectedTotal)) {
                throw new IllegalArgumentException();
            }
            return total;
        } catch (IllegalArgumentException e) {
            throw new LoaderException(
                "Download server returned an invalid resume range for package " + packageName, e);
        }
    }

    private static boolean unsatisfiedRangeIsComplete(
        String value, long downloaded, long expected) {

        if (expected >= 0 && downloaded == expected) {
            return true;
        }
        if (value == null || !value.startsWith("bytes */")) {
            return false;
        }
        try {
            return Long.parseLong(value.substring(8)) == downloaded;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long safeTotal(long first, long second) throws LoaderException {
        if (first < 0 || second < 0 || second > Long.MAX_VALUE - first) {
            throw new LoaderException("Download size is invalid");
        }
        return first + second;
    }

    private static int sourceAttempts(
        BootstrapManifest.Download metadata,
        List<BootstrapManifest.DownloadSource> sources,
        int sourceIndex) {

        if (sources.size() == 1) {
            return MAX_SOURCE_ATTEMPTS;
        }
        BootstrapManifest.DownloadSource source = sources.get(sourceIndex);
        return isSolderSource(metadata, source) ? MAX_SOURCE_ATTEMPTS : 1;
    }

    private static boolean isSolderSource(
        BootstrapManifest.Download metadata,
        BootstrapManifest.DownloadSource source) {

        return "solder".equalsIgnoreCase(source.provider) ||
            (metadata.url != null && metadata.url.equals(source.url));
    }

    private static String sourceName(BootstrapManifest.DownloadSource source) {
        return source.provider == null || source.provider.trim().isEmpty()
            ? "download" : source.provider;
    }

    private static String sourceDescription(BootstrapManifest.DownloadSource source) {
        if (source == null) {
            return "an unknown source";
        }
        String host = null;
        try {
            host = source.url == null ? null : URI.create(source.url).getHost();
        } catch (RuntimeException ignored) {
        }
        return sourceName(source) + (host == null || host.isEmpty() ? "" : " at " + host);
    }

    private static String packageName(BootstrapManifest.Package item) {
        String display = item.prettyName == null || item.prettyName.trim().isEmpty()
            ? item.name : item.prettyName.trim();
        if (item.version != null && !item.version.trim().isEmpty()) {
            display += " " + item.version.trim();
        }
        if (item.name != null && !item.name.equals(display) &&
            !display.startsWith(item.name + " ")) {
            display += " [" + item.name + "]";
        }
        return display;
    }

    private static void waitBeforeRetry(int nextAttempt) throws LoaderException {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * Math.max(1, nextAttempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoaderException("Interrupted while waiting to retry a download", e);
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
        } catch (RuntimeException e) {
            throw new LoaderException(
                "Package download URL must use HTTPS (or loopback HTTP)", e);
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
            long progressMaximum,
            long initialCount) {

            this.delegate = delegate;
            this.item = item;
            this.progress = progress;
            this.progressMaximum = progressMaximum;
            this.count = initialCount;
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
        final int sourceAttempt;
        final boolean persistent;

        private DownloadedFile(
            Path path,
            String md5,
            long bytes,
            int sourceIndex,
            int sourceAttempt,
            boolean persistent) {

            this.path = path;
            this.md5 = md5;
            this.bytes = bytes;
            this.sourceIndex = sourceIndex;
            this.sourceAttempt = sourceAttempt;
            this.persistent = persistent;
        }
    }
}
