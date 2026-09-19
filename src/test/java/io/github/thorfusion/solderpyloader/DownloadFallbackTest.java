package io.github.thorfusion.solderpyloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadFallbackTest {
    @TempDir Path temporaryDirectory;

    @Test
    void retriesSolderWhenPreferredSourceFailsMd5Verification() throws Exception {
        byte[] expected = "verified-modrinth-jar".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = "unverified-source-jar".getBytes(StandardCharsets.UTF_8);
        AtomicInteger preferredRequests = new AtomicInteger();
        AtomicInteger fallbackRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modrinth.jar", exchange -> {
            preferredRequests.incrementAndGet();
            send(exchange, invalid);
        });
        server.createContext("/solder.jar", exchange -> {
            fallbackRequests.incrementAndGet();
            send(exchange, expected);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            BootstrapManifest.Package item = downloadPackage(
                port, expected.length, md5(expected));

            Path downloaded = new DownloadManager(1024 * 1024)
                .download(item, temporaryDirectory);

            assertArrayEquals(expected, Files.readAllBytes(downloaded));
            assertEquals(1, preferredRequests.get());
            assertEquals(1, fallbackRequests.get());
        } finally {
            server.stop(0);
        }
    }

    private static BootstrapManifest.Package downloadPackage(int port, long size, String md5) {
        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = "example";
        item.download = new BootstrapManifest.Download();
        item.download.url = "http://127.0.0.1:" + port + "/solder.jar";
        item.download.md5 = md5;
        item.download.filesize = size;
        item.download.format = "jar";
        item.download.path = "mods/example.jar";

        BootstrapManifest.DownloadSource preferred = new BootstrapManifest.DownloadSource();
        preferred.provider = "modrinth";
        preferred.url = "http://127.0.0.1:" + port + "/modrinth.jar";
        item.download.sources.add(preferred);

        BootstrapManifest.DownloadSource fallback = new BootstrapManifest.DownloadSource();
        fallback.provider = "solder";
        fallback.url = item.download.url;
        item.download.sources.add(fallback);
        return item;
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String md5(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
