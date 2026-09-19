package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalSelectionScreenLayoutTest {
    @Test
    void basicOptionsAreSplitIntoBoundedScrollablePages() throws Exception {
        BootstrapManifest manifest = basicManifest(20);
        final JPanel[] pager = new JPanel[1];

        SwingUtilities.invokeAndWait(() -> pager[0] =
            new OptionalSelectionScreen(manifest, Collections.<Long>emptySet())
                .createBasicOptionsPager());

        assertEquals(4, countComponents(pager[0], JScrollPane.class));
        assertEquals(20, countComponents(pager[0], JCheckBox.class));
        assertTrue(pager[0].getPreferredSize().height <= 500,
            "pagination must keep the basic chooser shorter than the screen viewport");
    }

    @Test
    void wrappedDescriptionsDoNotHideLaterBasicOptions() throws Exception {
        BootstrapManifest manifest = basicManifest(2);
        manifest.packages.get(0).prettyName = "Better Foliage";
        manifest.packages.get(0).description =
            "Better Foliage is a Forge mod that will make your Minecraft worlds more " +
            "impressive, mainly by improving how vegetation looks. It is fully compatible " +
            "with other visual enhancement mods.\n\nThe mod is cosmetic and client-only.";
        manifest.packages.get(1).prettyName = "Smooth Entity Light";
        manifest.packages.get(1).description =
            "Atomic Strykers's Dynamic Lights has been the de-facto standard entity light " +
            "system since the dawn of modded Minecraft. During testing, the light from a " +
            "held torch gives off a popping effect as the player moves. This description is " +
            "deliberately long enough to wrap over several lines at the dialog width.";

        SwingUtilities.invokeAndWait(() -> {
            JPanel pager = new OptionalSelectionScreen(
                manifest, Collections.<Long>emptySet()).createBasicOptionsPager();
            pager.setSize(new Dimension(680, 440));
            layoutTree(pager);

            JScrollPane scroll = findFirst(pager, JScrollPane.class);
            JViewport viewport = scroll.getViewport();
            Component view = viewport.getView();
            Dimension preferred = view.getPreferredSize();
            Dimension extent = viewport.getExtentSize();
            view.setSize(Math.max(preferred.width, extent.width),
                Math.max(preferred.height, extent.height));
            layoutTree(view);

            List<JCheckBox> boxes = findAll((Container) view, JCheckBox.class);
            assertEquals(2, boxes.size());
            List<JButton> buttons = findAll((Container) view, JButton.class);
            assertEquals(2, buttons.stream()
                .filter(button -> "Details".equals(button.getText())).count(),
                "each long description must be moved behind a bounded Details control");
            int previousBottom = -1;
            for (JCheckBox box : boxes) {
                Rectangle bounds = SwingUtilities.convertRectangle(
                    box.getParent(), box.getBounds(), view);
                assertTrue(bounds.width > 0 && bounds.height > 0,
                    box.getText() + " must receive visible layout bounds");
                assertTrue(bounds.y >= previousBottom,
                    box.getText() + " must not be hidden behind the previous option");
                assertTrue(bounds.y + bounds.height <= view.getHeight(),
                    box.getText() + " must be reachable inside the scrollable view");
                previousBottom = bounds.y + bounds.height;
            }
        });
    }

    private static BootstrapManifest basicManifest(int optionCount) {
        BootstrapManifest manifest = new BootstrapManifest();
        manifest.optionalMode = new BootstrapManifest.OptionalMode();
        manifest.optionalMode.name = "basic";
        manifest.selectionPolicy = new BootstrapManifest.SelectionPolicy();
        manifest.packages = new ArrayList<BootstrapManifest.Package>();
        for (int index = 1; index <= optionCount; index++) {
            BootstrapManifest.Package item = new BootstrapManifest.Package();
            item.membershipId = index;
            item.name = "optional-" + index;
            item.prettyName = "Optional package " + index;
            item.version = "1.0";
            item.description = "A deliberately descriptive optional package used to exercise " +
                "the wrapped, paginated basic-option layout.";
            item.bootstrapManaged = true;
            item.selection = new BootstrapManifest.Selection();
            item.selection.state = 1;
            manifest.packages.add(item);
        }
        return manifest;
    }

    private static int countComponents(Container root, Class<? extends Component> type) {
        int count = 0;
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                count++;
            }
            if (component instanceof Container) {
                count += countComponents((Container) component, type);
            }
        }
        return count;
    }

    private static void layoutTree(Component component) {
        if (!(component instanceof Container)) {
            return;
        }
        Container container = (Container) component;
        container.doLayout();
        for (Component child : container.getComponents()) {
            layoutTree(child);
        }
    }

    private static <T extends Component> T findFirst(
        Container root, Class<T> type) {

        List<T> matches = findAll(root, type);
        assertTrue(!matches.isEmpty(), "Expected a " + type.getSimpleName());
        return matches.get(0);
    }

    private static <T extends Component> List<T> findAll(
        Container root, Class<T> type) {

        List<T> matches = new ArrayList<T>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
            if (component instanceof Container) {
                matches.addAll(findAll((Container) component, type));
            }
        }
        return matches;
    }
}
