package io.github.thorfusion.solderpyloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelDownloadTest {
    @TempDir Path gameDirectory;

    @Test
    void downloadsChangedPackagesConcurrently() throws Exception {
        byte[] first = "first-package".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-package".getBytes(StandardCharsets.UTF_8);
        CountDownLatch bothRequestsStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        ExecutorService serverWorkers = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/first.jar", new ConcurrentHandler(
            first, bothRequestsStarted, active, maximumActive));
        server.createContext("/second.jar", new ConcurrentHandler(
            second, bothRequestsStarted, active, maximumActive));
        server.start();
        try {
            int port = server.getAddress().getPort();
            LoaderConfig.Limits limits = new LoaderConfig.Limits();
            limits.maxConcurrentDownloads = 2;
            Installer installer = new Installer(
                gameDirectory, gameDirectory.resolve(".solderpy-loader"), limits);

            installer.reconcile(Arrays.asList(
                item("first", first, port, "mods/first.jar"),
                item("second", second, port, "mods/second.jar")),
                new InstalledState(), new InstalledState());

            assertTrue(maximumActive.get() >= 2,
                "both package requests should be active at the same time");
            assertArrayEquals(first, Files.readAllBytes(gameDirectory.resolve("mods/first.jar")));
            assertArrayEquals(second, Files.readAllBytes(gameDirectory.resolve("mods/second.jar")));
        } finally {
            server.stop(0);
            serverWorkers.shutdownNow();
        }
    }

    @Test
    void reusesConnectionForSequentialDownloads() throws Exception {
        byte[] first = "first-package".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-package".getBytes(StandardCharsets.UTF_8);
        Set<Integer> clientPorts = Collections.synchronizedSet(new HashSet<Integer>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first.jar", exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            send(exchange, first);
        });
        server.createContext("/second.jar", exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            send(exchange, second);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path directory = gameDirectory.resolve("downloads");
            DownloadManager downloads = new DownloadManager(1024L * 1024L);

            DownloadManager.DownloadedFile firstFile = downloads.downloadVerified(
                item("first", first, port, "mods/first.jar"), directory,
                BootstrapProgress.console());
            DownloadManager.DownloadedFile secondFile = downloads.downloadVerified(
                item("second", second, port, "mods/second.jar"), directory,
                BootstrapProgress.console());

            assertArrayEquals(first, Files.readAllBytes(firstFile.path));
            assertArrayEquals(second, Files.readAllBytes(secondFile.path));
            assertEquals(1, clientPorts.size(),
                "sequential downloads should share one persistent HTTP connection");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void checksMd5OnlyAfterNetworkDownloadCompletes() throws Exception {
        byte[] content = "download-before-verification".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/example.jar", exchange -> send(exchange, content));
        server.start();
        try {
            BootstrapManifest.Package item = item(
                "example", content, server.getAddress().getPort(), "mods/example.jar");
            item.download.md5 = "00000000000000000000000000000000";
            DownloadManager downloads = new DownloadManager(1024L * 1024L);

            DownloadManager.DownloadedFile downloaded = downloads.downloadFile(
                item, gameDirectory.resolve("downloads"), BootstrapProgress.console());

            assertTrue(Files.isRegularFile(downloaded.path));
            assertNull(downloaded.md5, "the network phase must not calculate MD5");
            LoaderException failure = assertThrows(LoaderException.class,
                () -> downloads.verifyDownloaded(item, downloaded, BootstrapProgress.console()));
            assertTrue(failure.getMessage().contains("MD5 mismatch"));
            assertFalse(Files.exists(downloaded.path),
                "a failed verification must discard the untrusted download");
        } finally {
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, byte[] content) throws IOException {
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        } finally {
            exchange.close();
        }
    }

    private static BootstrapManifest.Package item(
        String name, byte[] content, int port, String output) throws Exception {

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = name;
        item.version = "1.0";
        item.download = new BootstrapManifest.Download();
        item.download.url = "http://127.0.0.1:" + port + "/" + name + ".jar";
        item.download.md5 = md5(content);
        item.download.filesize = (long) content.length;
        item.download.format = "jar";
        item.download.path = output;
        return item;
    }

    private static String md5(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder result = new StringBuilder(32);
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class ConcurrentHandler implements HttpHandler {
        private final byte[] content;
        private final CountDownLatch bothRequestsStarted;
        private final AtomicInteger active;
        private final AtomicInteger maximumActive;

        private ConcurrentHandler(
            byte[] content,
            CountDownLatch bothRequestsStarted,
            AtomicInteger active,
            AtomicInteger maximumActive) {

            this.content = content;
            this.bothRequestsStarted = bothRequestsStarted;
            this.active = active;
            this.maximumActive = maximumActive;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int current = active.incrementAndGet();
            updateMaximum(maximumActive, current);
            bothRequestsStarted.countDown();
            try {
                if (!bothRequestsStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("package requests were not concurrent");
                }
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(content);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while testing concurrent downloads", e);
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        }

        private static void updateMaximum(AtomicInteger maximum, int value) {
            int observed;
            do {
                observed = maximum.get();
                if (observed >= value) {
                    return;
                }
            } while (!maximum.compareAndSet(observed, value));
        }
    }
}
