package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SolderPyWindowIconTest {
    @Test
    void packagedLogoProvidesCommonWindowIconSizes() {
        List<Image> images = SolderPyWindowIcon.images();

        assertFalse(images.isEmpty());
        assertSize(images.get(0), 16);
        assertSize(images.get(2), 32);
        assertSize(images.get(5), 128);
        assertSize(images.get(6), 256);
    }

    private static void assertSize(Image image, int expected) {
        assertEquals(expected, image.getWidth(null));
        assertEquals(expected, image.getHeight(null));
    }
}
