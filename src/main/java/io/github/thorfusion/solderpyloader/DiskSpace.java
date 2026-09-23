package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Conservative disk-space checks for downloads, staging, and rollback data. */
final class DiskSpace {
    static final long MINIMUM_HEADROOM_BYTES = 256L * 1024L * 1024L;
    static final long MAXIMUM_HEADROOM_BYTES = 2L * 1024L * 1024L * 1024L;

    private DiskSpace() {
    }

    static void require(Path storage, long payloadBytes, String operation)
        throws LoaderException {

        Path location = storage.toAbsolutePath().normalize();
        try {
            FileStore store = Files.getFileStore(location);
            require(store.getUsableSpace(), payloadBytes, location.toString(), operation);
        } catch (LoaderException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new LoaderException(
                "Could not determine available disk space at " + location, e);
        }
    }

    static void require(
        long usableBytes, long payloadBytes, String location, String operation)
        throws LoaderException {

        long payload = Math.max(0L, payloadBytes);
        long headroom = headroom(payload);
        long required = add(payload, headroom);
        LoaderLog.info("Disk space check for " + operation + ": " +
            format(required) + " required (including " + format(headroom) +
            " safety margin), " + format(usableBytes) + " available at " + location);
        if (usableBytes < required) {
            long missing = required - Math.max(0L, usableBytes);
            throw new LoaderException("Not enough disk space to " + operation + ". " +
                "Required " + format(required) + " (including a " + format(headroom) +
                " safety margin), but only " + format(usableBytes) + " is available at " +
                location + ". Free at least " + format(missing) + " and try again.");
        }
    }

    static long headroom(long payloadBytes) {
        long proportional = Math.max(0L, payloadBytes) / 10L;
        return Math.max(MINIMUM_HEADROOM_BYTES,
            Math.min(MAXIMUM_HEADROOM_BYTES, proportional));
    }

    static long add(long left, long right) {
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static String format(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
