package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class BootstrapProgressTest {
    @Test
    void oneStatusWindowSurvivesPreparationSelectionAndDownloadPhases() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(),
            "Swing status-window assertions require a graphical environment");

        BootstrapProgress progress = BootstrapProgress.create(true);
        try {
            progress.beginPreparation("Loading test manifest...");
            Window statusWindow = progress.ownerWindow();
            assertTrue(statusWindow.isVisible());

            progress.waitingForOptionalSelection();
            progress.beginInstalledCheck(Collections.singletonList(item()));
            progress.beginDownloads(Collections.singletonList(item()), 1);
            SwingUtilities.invokeAndWait(() -> { });

            assertSame(statusWindow, progress.ownerWindow());
            assertTrue(statusWindow.isVisible());
        } finally {
            progress.close();
        }
    }

    private static BootstrapManifest.Package item() {
        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.membershipId = 1;
        item.name = "example";
        item.prettyName = "Example";
        item.version = "1.0";
        return item;
    }
}
