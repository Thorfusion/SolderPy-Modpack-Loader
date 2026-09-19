package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathSafetyTest {
    @Test
    void allowsPortableRelativePathsAndRootMarker() throws Exception {
        assertEquals("mods/example.jar", PathSafety.normalizeRelative("mods/example.jar", false));
        assertEquals("", PathSafety.normalizeRelative(".", true));
    }

    @Test
    void rejectsTraversalAlternateStreamsAndWindowsDevices() {
        assertThrows(LoaderException.class,
            () -> PathSafety.normalizeRelative("../outside", false));
        assertThrows(LoaderException.class,
            () -> PathSafety.normalizeRelative("mods/example.jar:payload", false));
        assertThrows(LoaderException.class,
            () -> PathSafety.normalizeRelative("config/NUL.txt", false));
    }
}
