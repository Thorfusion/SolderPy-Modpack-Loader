package io.github.thorfusion.solderpyloader;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Applies the packaged SolderPy Loader logo to Swing windows. */
final class SolderPyWindowIcon {
    private static final String RESOURCE = "/assets/solderpyloader/icon.png";
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final List<Image> IMAGES = loadImages();

    private SolderPyWindowIcon() {
    }

    static void apply(Window window) {
        if (window != null && !IMAGES.isEmpty()) {
            window.setIconImages(IMAGES);
        }
    }

    static List<Image> images() {
        return IMAGES;
    }

    private static List<Image> loadImages() {
        try (InputStream input = SolderPyWindowIcon.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                LoaderLog.warn("The packaged window icon is missing: " + RESOURCE);
                return Collections.emptyList();
            }
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                LoaderLog.warn("The packaged window icon could not be decoded: " + RESOURCE);
                return Collections.emptyList();
            }

            List<Image> images = new ArrayList<Image>(SIZES.length + 1);
            for (int size : SIZES) {
                images.add(scale(source, size));
            }
            images.add(source);
            return Collections.unmodifiableList(images);
        } catch (IOException | RuntimeException e) {
            LoaderLog.warn("Could not load the packaged window icon: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

            double factor = Math.min(
                size / (double) source.getWidth(),
                size / (double) source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            int x = (size - width) / 2;
            int y = (size - height) / 2;
            graphics.drawImage(source, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
