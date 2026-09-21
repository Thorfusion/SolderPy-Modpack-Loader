package io.github.thorfusion.solderpyloader;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadResilienceTest {
    @TempDir Path temporary;

    @Test
    void retriesSingleSourceAfterHashFailures() throws Exception {
        byte[] expected = "expected-download".getBytes(StandardCharsets.UTF_8);
        byte[] corrupt = "corrupt-download!".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/package.jar", exchange -> {
            int request = requests.incrementAndGet();
            send(exchange, 200, request < 3 ? corrupt : expected);
        });
        server.start();
        try {
            BootstrapManifest.Package item = item(
                server.getAddress().getPort(), "/package.jar", expected);
            Path cache = temporary.resolve("cache");

            Path downloaded = new DownloadManager(1024 * 1024, cache)
                .download(item, temporary.resolve("transaction"));

            assertEquals(3, requests.get());
            assertArrayEquals(expected, Files.readAllBytes(downloaded));
            assertTrue(downloaded.startsWith(cache));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOnlySolderWhenMultipleSourcesExist() throws Exception {
        byte[] expected = "solder-fallback".getBytes(StandardCharsets.UTF_8);
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicInteger solderRequests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/provider.jar", exchange -> {
            providerRequests.incrementAndGet();
            send(exchange, 503, new byte[0]);
        });
        server.createContext("/solder.jar", exchange -> {
            int request = solderRequests.incrementAndGet();
            if (request < 3) {
                send(exchange, 503, new byte[0]);
            } else {
                send(exchange, 200, expected);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            BootstrapManifest.Package item = item(port, "/solder.jar", expected);
            BootstrapManifest.DownloadSource provider = new BootstrapManifest.DownloadSource();
            provider.provider = "modrinth";
            provider.url = "http://127.0.0.1:" + port + "/provider.jar";
            item.download.sources.add(provider);
            BootstrapManifest.DownloadSource solder = new BootstrapManifest.DownloadSource();
            solder.provider = "solder";
            solder.url = item.download.url;
            item.download.sources.add(solder);

            Path downloaded = new DownloadManager(1024 * 1024, temporary.resolve("cache"))
                .download(item, temporary.resolve("transaction"));

            assertEquals(1, providerRequests.get());
            assertEquals(3, solderRequests.get());
            assertArrayEquals(expected, Files.readAllBytes(downloaded));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesPartialDownloadAndThenReusesVerifiedCache() throws Exception {
        byte[] expected = new byte[64 * 1024];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31);
        }
        int firstLength = expected.length / 2;
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> resumeRange = new AtomicReference<String>();
        HttpServer server = server();
        server.createContext("/package.jar", exchange -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                exchange.sendResponseHeaders(200, expected.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(expected, 0, firstLength);
                }
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            resumeRange.set(range);
            exchange.getResponseHeaders().set("Content-Range",
                "bytes " + firstLength + "-" + (expected.length - 1) + "/" + expected.length);
            exchange.sendResponseHeaders(206, expected.length - firstLength);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(expected, firstLength, expected.length - firstLength);
            }
        });
        server.start();
        try {
            BootstrapManifest.Package item = item(
                server.getAddress().getPort(), "/package.jar", expected);
            Path cache = temporary.resolve("cache");
            DownloadManager firstManager = new DownloadManager(1024 * 1024, cache);

            Path first = firstManager.download(item, temporary.resolve("first-transaction"));

            assertEquals(2, requests.get());
            assertEquals("bytes=" + firstLength + "-", resumeRange.get());
            assertArrayEquals(expected, Files.readAllBytes(first));

            Path second = new DownloadManager(1024 * 1024, cache)
                .download(item, temporary.resolve("second-transaction"));

            assertEquals(2, requests.get(), "verified cache should avoid another HTTP request");
            assertArrayEquals(expected, Files.readAllBytes(second));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completedPackagesSurviveAnotherPackagesFailedUpdate() throws Exception {
        byte[] firstBytes = "first-package".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "second-package".getBytes(StandardCharsets.UTF_8);
        AtomicInteger firstRequests = new AtomicInteger();
        AtomicInteger secondRequests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/first.jar", exchange -> {
            firstRequests.incrementAndGet();
            send(exchange, 200, firstBytes);
        });
        server.createContext("/second.jar", exchange -> {
            int request = secondRequests.incrementAndGet();
            send(exchange, request <= 3 ? 503 : 200,
                request <= 3 ? new byte[0] : secondBytes);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            BootstrapManifest.Package first = item(port, "/first.jar", firstBytes);
            first.name = "first";
            first.download.path = "mods/first.jar";
            BootstrapManifest.Package second = item(port, "/second.jar", secondBytes);
            second.name = "second";
            second.download.path = "mods/second.jar";
            LoaderConfig.Limits limits = new LoaderConfig.Limits();
            limits.maxConcurrentDownloads = 2;
            Installer installer = new Installer(
                temporary.resolve("game"), temporary.resolve("game/.solderpy-loader"), limits);

            assertThrows(RecoverableBootstrapException.class, () -> installer.reconcile(
                Arrays.asList(first, second), new InstalledState(), new InstalledState()));

            installer.reconcile(
                Arrays.asList(first, second), new InstalledState(), new InstalledState());

            assertEquals(1, firstRequests.get(),
                "the package completed before the failure should come from cache");
            assertEquals(4, secondRequests.get());
            assertArrayEquals(firstBytes,
                Files.readAllBytes(temporary.resolve("game/mods/first.jar")));
            assertArrayEquals(secondBytes,
                Files.readAllBytes(temporary.resolve("game/mods/second.jar")));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static BootstrapManifest.Package item(
        int port, String path, byte[] expected) throws Exception {

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = "example";
        item.version = "1.0";
        item.download = new BootstrapManifest.Download();
        item.download.url = "http://127.0.0.1:" + port + path;
        item.download.md5 = md5(expected);
        item.download.filesize = (long) expected.length;
        item.download.format = "jar";
        item.download.path = "mods/example.jar";
        return item;
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    private static String md5(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder result = new StringBuilder(32);
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
