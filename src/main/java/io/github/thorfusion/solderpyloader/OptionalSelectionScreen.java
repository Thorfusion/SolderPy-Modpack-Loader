package io.github.thorfusion.solderpyloader;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loader-neutral optional-content chooser displayed before mod discovery. */
final class OptionalSelectionScreen {
    private static final int BASIC_OPTIONS_PER_PAGE = 6;
    private static final Dimension CHOICE_VIEW_SIZE = new Dimension(680, 440);

    private final BootstrapManifest manifest;
    private final Map<Long, BootstrapManifest.Package> packagesByMembership;
    private final Set<Long> selectableMemberships;
    private final Set<Long> defaults;
    private final Set<Long> initialSelections;
    private final List<ChoiceControl> controls = new ArrayList<ChoiceControl>();
    private final List<ButtonGroup> buttonGroups = new ArrayList<ButtonGroup>();

    private Set<Long> result;

    OptionalSelectionScreen(BootstrapManifest manifest, Collection<Long> initialSelections) {
        this.manifest = manifest;
        this.packagesByMembership = new LinkedHashMap<Long, BootstrapManifest.Package>();
        for (BootstrapManifest.Package item : manifest.packages) {
            packagesByMembership.put(item.membershipId, item);
        }
        this.selectableMemberships = selectableMemberships(manifest);
        this.defaults = new LinkedHashSet<Long>(manifest.selectionPolicy.defaultMemberships);
        this.initialSelections = new LinkedHashSet<Long>(initialSelections);
    }

    static boolean isAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }

    static boolean hasSelectableOptions(BootstrapManifest manifest) {
        return !selectableMemberships(manifest).isEmpty();
    }

    static Set<Long> selectableMemberships(BootstrapManifest manifest) {
        Set<Long> result = new LinkedHashSet<Long>();
        if ("advanced".equals(manifest.optionalMode.name)) {
            for (BootstrapManifest.Group group : manifest.groups) {
                for (BootstrapManifest.Choice choice : group.choices) {
                    BootstrapManifest.Package item = findPackage(manifest, choice.membershipId);
                    if (item != null && item.bootstrapManaged) {
                        result.add(choice.membershipId);
                    }
                }
            }
        }
        for (BootstrapManifest.Package item : manifest.packages) {
            if (item.bootstrapManaged && item.selection.state == 1 &&
                item.selection.groupKey == null) {
                result.add(item.membershipId);
            }
        }
        return result;
    }

    static Set<Long> choose(final BootstrapManifest manifest) throws LoaderException {
        return choose(manifest, manifest.selectionPolicy.defaultMemberships);
    }

    static Set<Long> choose(
        final BootstrapManifest manifest, final Collection<Long> initialSelections)
        throws LoaderException {

        if (!isAvailable()) {
            throw new LoaderException("The optional selection screen requires a graphical environment");
        }

        final OptionalSelectionScreen screen =
            new OptionalSelectionScreen(manifest, initialSelections);
        Runnable display = new Runnable() {
            @Override
            public void run() {
                screen.showDialog();
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                display.run();
            } else {
                SwingUtilities.invokeAndWait(display);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoaderException("Interrupted while waiting for optional selection", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new LoaderException("Could not display the optional selection screen", cause);
        }

        if (screen.result == null) {
            throw new LaunchCancelledException("Optional selection was cancelled");
        }
        return screen.result;
    }

    private void showDialog() {
        String packName = manifest.modpack.name == null ? manifest.modpack.slug : manifest.modpack.name;
        final JDialog dialog = new JDialog((Frame) null, packName + " - Optional Content", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                dialog.dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Select optional content", SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        JLabel subtitle = new JLabel(packName + "  |  Build " + manifest.build.version);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(subtitle);
        heading.add(Box.createVerticalStrut(4));
        JLabel note = new JLabel("Required packages and dependencies are installed automatically.");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(note);
        root.add(heading, BorderLayout.NORTH);

        if ("basic".equals(manifest.optionalMode.name)) {
            root.add(createBasicOptionsPager(), BorderLayout.CENTER);
        } else {
            JPanel choices = createChoiceList();
            addAdvancedGroups(choices);
            addUngroupedOptions(choices);
            root.add(createScrollPane(choices), BorderLayout.CENTER);
        }

        JButton restore = new JButton("Restore API Defaults");
        restore.addActionListener(event -> restoreDefaults());
        JButton cancel = new JButton("Cancel Launch");
        cancel.addActionListener(event -> dialog.dispose());
        JButton proceed = new JButton("Continue");
        proceed.addActionListener(event -> accept(dialog));
        dialog.getRootPane().setDefaultButton(proceed);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(restore, BorderLayout.WEST);
        JPanel confirmation = new JPanel();
        confirmation.add(cancel);
        confirmation.add(proceed);
        buttons.add(confirmation, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        fitToUsableScreen(dialog);
        dialog.setVisible(true);
    }

    JPanel createBasicOptionsPager() {
        List<BootstrapManifest.Package> options = new ArrayList<BootstrapManifest.Package>();
        for (BootstrapManifest.Package item : manifest.packages) {
            if (selectableMemberships.contains(item.membershipId) &&
                item.selection.groupKey == null) {
                options.add(item);
            }
        }

        final int pageCount = basicPageCount(options.size());
        final CardLayout pageLayout = new CardLayout();
        final JPanel pages = new JPanel(pageLayout);
        for (int page = 0; page < pageCount; page++) {
            int first = page * BASIC_OPTIONS_PER_PAGE;
            int last = Math.min(options.size(), first + BASIC_OPTIONS_PER_PAGE);
            JPanel choices = createChoiceList();
            JPanel section = section("Optional packages", null,
                pageCount == 1 ? "Select any packages you want installed."
                    : "Showing " + (first + 1) + "-" + last + " of " + options.size() + ".");
            for (int index = first; index < last; index++) {
                BootstrapManifest.Package item = options.get(index);
                JCheckBox button = new JCheckBox(
                    label(item), initialSelections.contains(item.membershipId));
                addChoice(section, button, item);
                controls.add(new ChoiceControl(item.membershipId, button));
            }
            addSection(choices, section);
            pages.add(createScrollPane(choices), Integer.toString(page));
        }

        JPanel result = new JPanel(new BorderLayout(0, 8));
        result.add(pages, BorderLayout.CENTER);
        if (pageCount > 1) {
            final int[] currentPage = {0};
            final JButton previous = new JButton("Previous");
            final JButton next = new JButton("Next");
            final JLabel status = new JLabel("Page 1 of " + pageCount, SwingConstants.CENTER);
            previous.setEnabled(false);

            previous.addActionListener(event -> {
                if (currentPage[0] > 0) {
                    currentPage[0]--;
                    showBasicPage(pageLayout, pages, currentPage[0], pageCount,
                        previous, next, status);
                }
            });
            next.addActionListener(event -> {
                if (currentPage[0] + 1 < pageCount) {
                    currentPage[0]++;
                    showBasicPage(pageLayout, pages, currentPage[0], pageCount,
                        previous, next, status);
                }
            });

            JPanel navigation = new JPanel(new BorderLayout(8, 0));
            navigation.add(previous, BorderLayout.WEST);
            navigation.add(status, BorderLayout.CENTER);
            navigation.add(next, BorderLayout.EAST);
            result.add(navigation, BorderLayout.SOUTH);
        }
        return result;
    }

    static int basicPageCount(int optionCount) {
        return Math.max(1,
            (Math.max(0, optionCount) + BASIC_OPTIONS_PER_PAGE - 1) /
                BASIC_OPTIONS_PER_PAGE);
    }

    private static void showBasicPage(
        CardLayout layout,
        JPanel pages,
        int page,
        int pageCount,
        JButton previous,
        JButton next,
        JLabel status) {

        layout.show(pages, Integer.toString(page));
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < pageCount);
        status.setText("Page " + (page + 1) + " of " + pageCount);
    }

    private static JPanel createChoiceList() {
        JPanel choices = new ChoiceListPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        return choices;
    }

    private static JScrollPane createScrollPane(JPanel choices) {
        JScrollPane scroll = new JScrollPane(choices);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setBlockIncrement(160);
        scroll.setPreferredSize(CHOICE_VIEW_SIZE);
        scroll.setMinimumSize(new Dimension(320, 180));
        return scroll;
    }

    private static void fitToUsableScreen(JDialog dialog) {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getMaximumWindowBounds();
        int maximumWidth = Math.max(1, usable.width - 32);
        int maximumHeight = Math.max(1, usable.height - 32);
        int minimumWidth = Math.min(560, maximumWidth);
        int minimumHeight = Math.min(360, maximumHeight);
        int width = Math.min(Math.max(dialog.getWidth(), minimumWidth), maximumWidth);
        int height = Math.min(Math.max(dialog.getHeight(), minimumHeight), maximumHeight);

        dialog.setMinimumSize(new Dimension(minimumWidth, minimumHeight));
        dialog.setSize(width, height);
        dialog.setLocation(
            usable.x + Math.max(0, (usable.width - width) / 2),
            usable.y + Math.max(0, (usable.height - height) / 2));
    }

    private void addAdvancedGroups(JPanel choices) {
        for (BootstrapManifest.Group group : manifest.groups) {
            JPanel section = section(group.name == null ? group.key : group.name,
                group.description, constraintText(group));
            ButtonGroup radioGroup = null;
            if ("single".equals(group.selectionType)) {
                radioGroup = new ButtonGroup();
                buttonGroups.add(radioGroup);
            }
            for (BootstrapManifest.Choice choice : group.choices) {
                if (!selectableMemberships.contains(choice.membershipId)) {
                    continue;
                }
                BootstrapManifest.Package item = packagesByMembership.get(choice.membershipId);
                AbstractButton button = radioGroup == null
                    ? new JCheckBox(label(item), initialSelections.contains(choice.membershipId))
                    : new JRadioButton(label(item), initialSelections.contains(choice.membershipId));
                if (radioGroup != null) {
                    radioGroup.add(button);
                }
                addChoice(section, button, item);
                controls.add(new ChoiceControl(choice.membershipId, button));
            }
            addSection(choices, section);
        }
    }

    private void addUngroupedOptions(JPanel choices) {
        JPanel section = section("Optional packages", null, "Select any packages you want installed.");
        int added = 0;
        for (BootstrapManifest.Package item : manifest.packages) {
            if (!selectableMemberships.contains(item.membershipId) ||
                item.selection.groupKey != null) {
                continue;
            }
            JCheckBox button = new JCheckBox(label(item), initialSelections.contains(item.membershipId));
            addChoice(section, button, item);
            controls.add(new ChoiceControl(item.membershipId, button));
            added++;
        }
        if (added > 0) {
            addSection(choices, section);
        }
    }

    private static JPanel section(String title, String description, String constraint) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(title),
            BorderFactory.createEmptyBorder(3, 8, 8, 8)));
        if (description != null && !description.trim().isEmpty()) {
            section.add(description(description));
            section.add(Box.createVerticalStrut(4));
        }
        if (constraint != null && !constraint.isEmpty()) {
            JLabel rule = new JLabel(constraint);
            rule.setFont(rule.getFont().deriveFont(Font.ITALIC));
            rule.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(rule);
            section.add(Box.createVerticalStrut(4));
        }
        return section;
    }

    private static void addChoice(
        JPanel section, AbstractButton button, BootstrapManifest.Package item) {

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(button, BorderLayout.CENTER);
        if (hasText(item.description)) {
            JButton details = new JButton("Details");
            details.setToolTipText("Show the package description");
            details.addActionListener(event -> showOptionDetails(details, item));
            row.add(details, BorderLayout.EAST);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        section.add(row);
        section.add(Box.createVerticalStrut(5));
    }

    private static void showOptionDetails(Component parent, BootstrapManifest.Package item) {
        JTextArea text = new JTextArea(item.description, 12, 60);
        text.setEditable(false);
        text.setFocusable(true);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new JLabel().getFont());
        text.setCaretPosition(0);
        text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(text);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(600, 260));

        JOptionPane.showMessageDialog(parent, scroll, label(item),
            JOptionPane.INFORMATION_MESSAGE);
    }

    private static JTextArea description(String value) {
        JTextArea text = new JTextArea(value);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new JLabel().getFont());
        Color foreground = new JLabel().getForeground();
        text.setForeground(foreground);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        return text;
    }

    private static void addSection(JPanel choices, JPanel section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Wrapped text can change its preferred height after the viewport assigns its
        // final width. Never freeze the section at its pre-layout preferred height.
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        choices.add(section);
        choices.add(Box.createVerticalStrut(8));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void restoreDefaults() {
        for (ButtonGroup group : buttonGroups) {
            group.clearSelection();
        }
        for (ChoiceControl control : controls) {
            control.button.setSelected(defaults.contains(control.membershipId));
        }
    }

    private void accept(JDialog dialog) {
        Set<Long> requested = collectSelections();
        try {
            SelectionResolver.resolve(manifest, requested);
            result = requested;
            dialog.dispose();
        } catch (LoaderException e) {
            JOptionPane.showMessageDialog(dialog, e.getMessage(), "Invalid selection",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private Set<Long> collectSelections() {
        Set<Long> requested = new LinkedHashSet<Long>(defaults);
        requested.removeAll(selectableMemberships);
        for (ChoiceControl control : controls) {
            if (control.button.isSelected()) {
                requested.add(control.membershipId);
            }
        }
        return requested;
    }

    private static String label(BootstrapManifest.Package item) {
        String name = item.prettyName == null || item.prettyName.trim().isEmpty()
            ? item.name : item.prettyName;
        return item.version == null || item.version.trim().isEmpty()
            ? name : name + " (" + item.version + ")";
    }

    private static String constraintText(BootstrapManifest.Group group) {
        if ("single".equals(group.selectionType)) {
            return "Choose exactly one.";
        }
        if (group.maximum == null) {
            return group.minimum == 0 ? "Choose any number."
                : "Choose at least " + group.minimum + ".";
        }
        if (group.minimum == group.maximum.intValue()) {
            return "Choose exactly " + group.minimum + ".";
        }
        return "Choose " + group.minimum + " to " + group.maximum + ".";
    }

    private static BootstrapManifest.Package findPackage(
        BootstrapManifest manifest, long membershipId) {

        for (BootstrapManifest.Package item : manifest.packages) {
            if (item.membershipId == membershipId) {
                return item;
            }
        }
        return null;
    }

    private static final class ChoiceControl {
        private final long membershipId;
        private final AbstractButton button;

        private ChoiceControl(long membershipId, AbstractButton button) {
            this.membershipId = membershipId;
            this.button = button;
        }
    }

    private static final class ChoiceListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(
                Math.min(preferred.width, CHOICE_VIEW_SIZE.width),
                Math.min(preferred.height, CHOICE_VIEW_SIZE.height));
        }

        @Override
        public int getScrollableUnitIncrement(
            Rectangle visibleRectangle, int orientation, int direction) {

            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(
            Rectangle visibleRectangle, int orientation, int direction) {

            return Math.max(24, visibleRectangle.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
