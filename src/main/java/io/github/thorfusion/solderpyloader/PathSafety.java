package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

final class PathSafety {
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
        "(?i)^(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9]|LPT[1-9])(?:\\..*)?$");

    private PathSafety() {
    }

    static String normalizeRelative(String value, boolean allowRoot) throws LoaderException {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new LoaderException("Package contains an invalid output path");
        }
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/") || portable.startsWith("//") ||
            portable.matches("^[A-Za-z]:.*")) {
            throw new LoaderException("Package contains an absolute output path: " + value);
        }
        String[] segments = portable.split("/", -1);
        for (String segment : segments) {
            if (".".equals(portable) && ".".equals(segment)) {
                continue;
            }
            if (containsWindowsIllegalCharacter(segment) ||
                segment.endsWith(" ") || segment.endsWith(".") ||
                WINDOWS_DEVICE.matcher(segment).matches()) {
                throw new LoaderException("Package path is not portable across filesystems: " + value);
            }
        }
        Path path;
        try {
            path = Paths.get(portable).normalize();
        } catch (InvalidPathException e) {
            throw new LoaderException("Package contains an invalid output path: " + value, e);
        }
        String normalized = path.toString().replace('\\', '/');
        if (path.isAbsolute() || normalized.equals("..") || normalized.startsWith("../")) {
            throw new LoaderException("Package path escapes the game directory: " + value);
        }
        if (normalized.equals(".") || normalized.isEmpty()) {
            if (allowRoot) {
                return "";
            }
            throw new LoaderException("Package contains an empty output path");
        }
        return normalized;
    }

    static String combine(String base, String child) throws LoaderException {
        String normalizedBase = normalizeRelative(base, true);
        String normalizedChild = normalizeRelative(child, false);
        return normalizeRelative(normalizedBase.isEmpty()
            ? normalizedChild : normalizedBase + "/" + normalizedChild, false);
    }

    static Path resolve(Path root, String relative) throws LoaderException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path result = absoluteRoot.resolve(normalizeRelative(relative, false)).normalize();
        if (!result.startsWith(absoluteRoot)) {
            throw new LoaderException("Package path escapes the game directory: " + relative);
        }
        return result;
    }

    static String collisionKey(String relative) {
        // Modpacks are commonly moved between case-sensitive and insensitive filesystems.
        return relative.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static void rejectSymlinkAncestors(Path root, String relative) throws LoaderException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path current = absoluteRoot;
        Path relativePath;
        try {
            relativePath = Paths.get(normalizeRelative(relative, false));
        } catch (InvalidPathException e) {
            throw new LoaderException("Package contains an invalid output path: " + relative, e);
        }
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            current = current.resolve(relativePath.getName(i));
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new LoaderException("Refusing to modify a path through a symbolic link: " + current);
            }
        }
    }

    private static boolean containsWindowsIllegalCharacter(String segment) {
        for (int index = 0; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if (value < 0x20 || value == 0x7f || value == '<' || value == '>' ||
                value == ':' || value == '"' || value == '|' || value == '?' || value == '*') {
                return true;
            }
        }
        return false;
    }
}
