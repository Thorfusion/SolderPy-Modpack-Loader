package io.github.thorfusion.solderpyloader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe console and Swing progress reporting for bootstrap downloads. */
final class BootstrapProgress implements AutoCloseable {
    private final Map<Long, ProgressRow> rows = new LinkedHashMap<Long, ProgressRow>();
    private final Map<Long, Long> downloadStarts = new ConcurrentHashMap<Long, Long>();
    private final Map<Long, Long> lastProgressLogs = new ConcurrentHashMap<Long, Long>();
    private final AtomicInteger completed = new AtomicInteger();
    private volatile boolean graphical;
    private volatile boolean closed;
    private int total;
    private JDialog dialog;
    private JLabel phaseLabel;
    private JProgressBar overall;

    private BootstrapProgress(boolean graphical) {
        this.graphical = graphical && !GraphicsEnvironment.isHeadless();
    }

    static BootstrapProgress create(boolean graphical) {
        return new BootstrapProgress(graphical);
    }

    static BootstrapProgress console() {
        return new BootstrapProgress(false);
    }

    void begin(List<BootstrapManifest.Package> packages, int concurrency) {
        total = packages.size();
        completed.set(0);
        LoaderLog.info("Downloading " + total + " changed package(s) with up to " +
            concurrency + " concurrent download(s)");
        if (!graphical || packages.isEmpty()) {
            return;
        }
        try {
            runOnEventThreadAndWait(() -> createDialog(packages));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            graphical = false;
            LoaderLog.warn("Download progress window was interrupted; continuing in the log");
        } catch (InvocationTargetException | RuntimeException e) {
            graphical = false;
            LoaderLog.warn("Could not display download progress; continuing in the log");
        }
    }

    void downloadStarted(BootstrapManifest.Package item, long expectedBytes) {
        long now = System.nanoTime();
        downloadStarts.put(item.membershipId, now);
        lastProgressLogs.put(item.membershipId, now);
        LoaderLog.info("Downloading " + displayName(item) +
            (expectedBytes >= 0 ? " (" + formatBytes(expectedBytes) + ")" : ""));
        updateRow(item, "Connecting...", 0, expectedBytes, expectedBytes < 0);
    }

    void downloadProgress(
        BootstrapManifest.Package item, long downloadedBytes, long expectedBytes) {

        String text = formatBytes(downloadedBytes) +
            (expectedBytes >= 0 ? " / " + formatBytes(expectedBytes) : " downloaded") +
            formatRate(item, downloadedBytes);
        logPeriodicProgress(item, text);
        updateRow(item, text, downloadedBytes, expectedBytes, expectedBytes < 0);
    }

    void downloadCompleted(BootstrapManifest.Package item, long downloadedBytes) {
        int finished = completed.incrementAndGet();
        String rate = formatRate(item, downloadedBytes);
        LoaderLog.info("Downloaded " + displayName(item) + " (" +
            formatBytes(downloadedBytes) + rate + ")");
        updateRow(item, "Downloaded - " + formatBytes(downloadedBytes) + rate, 1, 1, false);
        downloadStarts.remove(item.membershipId);
        lastProgressLogs.remove(item.membershipId);
        runOnEventThread(() -> {
            if (overall != null) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages downloaded");
            }
        });
    }

    void downloadFailed(BootstrapManifest.Package item, String message) {
        downloadStarts.remove(item.membershipId);
        lastProgressLogs.remove(item.membershipId);
        LoaderLog.warn("Download failed for " + displayName(item) + ": " + message);
        updateRow(item, "Failed - " + message, 0, 1, false);
    }

    void installing(BootstrapManifest.Package item) {
        installing(item, "Installing");
    }

    void installing(BootstrapManifest.Package item, String action) {
        LoaderLog.info(action + " " + displayName(item));
        updateRow(item, action + "...", 1, 1, false);
    }

    void installed(BootstrapManifest.Package item) {
        updateRow(item, "Ready", 1, 1, false);
    }

    void phase(String message) {
        LoaderLog.info(message);
        runOnEventThread(() -> {
            if (phaseLabel != null) {
                phaseLabel.setText(message);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (!graphical || dialog == null) {
            return;
        }
        try {
            runOnEventThreadAndWait(() -> {
                if (dialog != null) {
                    dialog.dispose();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException | RuntimeException ignored) {
        }
    }

    private void createDialog(List<BootstrapManifest.Package> packages) {
        dialog = new JDialog((Frame) null, "SolderPy Loader", false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Downloading modpack content");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(Box.createVerticalStrut(5));
        phaseLabel = new JLabel("Starting downloads...");
        phaseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(phaseLabel);
        heading.add(Box.createVerticalStrut(8));
        overall = new JProgressBar(0, total);
        overall.setStringPainted(true);
        overall.setString("0 / " + total + " packages downloaded");
        overall.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(overall);
        root.add(heading, BorderLayout.NORTH);

        JPanel packageList = new JPanel();
        packageList.setLayout(new BoxLayout(packageList, BoxLayout.Y_AXIS));
        for (BootstrapManifest.Package item : packages) {
            JLabel label = new JLabel(displayName(item));
            JProgressBar bar = new JProgressBar(0, 1000);
            bar.setStringPainted(true);
            bar.setString("Waiting...");
            JPanel row = new JPanel(new BorderLayout(4, 4));
            row.setBorder(BorderFactory.createEmptyBorder(4, 2, 5, 2));
            row.add(label, BorderLayout.NORTH);
            row.add(bar, BorderLayout.CENTER);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            packageList.add(row);
            rows.put(item.membershipId, new ProgressRow(bar));
        }

        JScrollPane scroll = new JScrollPane(packageList);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(680, 360));
        root.add(scroll, BorderLayout.CENTER);

        dialog.setContentPane(root);
        dialog.pack();
        fitToUsableScreen(dialog);
        dialog.setVisible(true);
    }

    private void updateRow(
        BootstrapManifest.Package item,
        String text,
        long value,
        long maximum,
        boolean indeterminate) {

        runOnEventThread(() -> {
            ProgressRow row = rows.get(item.membershipId);
            if (row == null) {
                return;
            }
            row.progress.setIndeterminate(indeterminate);
            if (!indeterminate) {
                int scaled = maximum <= 0 ? 0 :
                    (int) Math.min(1000L, (value * 1000L) / maximum);
                row.progress.setValue(scaled);
            }
            row.progress.setString(text);
        });
    }

    private void runOnEventThread(Runnable action) {
        if (!graphical || closed) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static void runOnEventThreadAndWait(Runnable action)
        throws InterruptedException, InvocationTargetException {

        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeAndWait(action);
        }
    }

    private static void fitToUsableScreen(JDialog window) {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getMaximumWindowBounds();
        int maximumWidth = Math.max(1, usable.width - 32);
        int maximumHeight = Math.max(1, usable.height - 32);
        int width = Math.min(window.getWidth(), maximumWidth);
        int height = Math.min(window.getHeight(), maximumHeight);
        window.setSize(width, height);
        window.setLocation(
            usable.x + Math.max(0, (usable.width - width) / 2),
            usable.y + Math.max(0, (usable.height - height) / 2));
    }

    private static String displayName(BootstrapManifest.Package item) {
        String name = item.prettyName == null || item.prettyName.trim().isEmpty()
            ? item.name : item.prettyName;
        return item.version == null || item.version.trim().isEmpty()
            ? name : name + " " + item.version;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private String formatRate(BootstrapManifest.Package item, long downloadedBytes) {
        Long started = downloadStarts.get(item.membershipId);
        if (started == null || downloadedBytes <= 0) {
            return "";
        }
        double seconds = (System.nanoTime() - started.longValue()) / 1_000_000_000.0;
        if (seconds < 0.2) {
            return "";
        }
        return " at " + formatBytes((long) (downloadedBytes / seconds)) + "/s";
    }

    private void logPeriodicProgress(BootstrapManifest.Package item, String text) {
        long now = System.nanoTime();
        Long last = lastProgressLogs.get(item.membershipId);
        if (last != null && now - last.longValue() >= 5_000_000_000L) {
            lastProgressLogs.put(item.membershipId, now);
            LoaderLog.info("Downloading " + displayName(item) + ": " + text);
        }
    }

    private static final class ProgressRow {
        private final JProgressBar progress;

        private ProgressRow(JProgressBar progress) {
            this.progress = progress;
        }
    }
}
