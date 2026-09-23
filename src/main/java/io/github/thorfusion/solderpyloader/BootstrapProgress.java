package io.github.thorfusion.solderpyloader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe console and Swing progress reporting for bootstrap downloads. */
final class BootstrapProgress implements AutoCloseable {
    private static final int MAX_VISIBLE_DOWNLOADS = 4;

    private final ConcurrentHashMap<Long, Long> downloadStarts =
        new ConcurrentHashMap<Long, Long>();
    private final ConcurrentHashMap<Long, Long> lastProgressLogs =
        new ConcurrentHashMap<Long, Long>();
    private final ConcurrentHashMap<Long, Long> downloadStartOffsets =
        new ConcurrentHashMap<Long, Long>();
    private final AtomicInteger checkedPackages = new AtomicInteger();
    private final AtomicInteger downloadedPackages = new AtomicInteger();
    private final AtomicInteger verifiedPackages = new AtomicInteger();
    private final AtomicInteger installedPackages = new AtomicInteger();
    private volatile boolean graphical;
    private volatile boolean closed;
    private volatile boolean downloadPhase;
    private volatile int total;
    private final Object downloadSlotLock = new Object();
    private final ConcurrentHashMap<Long, Integer> downloadSlotIndexes =
        new ConcurrentHashMap<Long, Integer>();
    private long[] downloadSlotOwners = new long[0];
    private long[] downloadSlotVersions = new long[0];
    private int visibleDownloads = 1;
    private volatile JDialog dialog;
    private JLabel titleLabel;
    private JLabel phaseLabel;
    private JProgressBar overall;
    private JPanel downloadPanel;
    private final List<JLabel> downloadLabels = new ArrayList<JLabel>();
    private final List<JProgressBar> downloadProgressBars = new ArrayList<JProgressBar>();
    private JPanel currentPanel;
    private JLabel currentLabel;
    private JProgressBar currentProgress;

    private BootstrapProgress(boolean graphical) {
        this.graphical = graphical && !GraphicsEnvironment.isHeadless();
    }

    static BootstrapProgress create(boolean graphical) {
        return new BootstrapProgress(graphical);
    }

    static BootstrapProgress console() {
        return new BootstrapProgress(false);
    }

    void beginPreparation(String message) {
        downloadPhase = false;
        LoaderLog.info(message);
        if (!graphical) {
            return;
        }
        try {
            runOnEventThreadAndWait(() -> {
                ensureDialog();
                showSingleActivity();
                titleLabel.setText("Loading SolderPy Modpack Loader");
                phaseLabel.setText(message);
                overall.setMinimum(0);
                overall.setMaximum(1);
                overall.setValue(0);
                overall.setIndeterminate(true);
                overall.setString("Please wait...");
                currentLabel.setText(message);
                currentProgress.setValue(0);
                currentProgress.setIndeterminate(true);
                currentProgress.setString("Working in the background...");
                resizeDialog();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            graphical = false;
            LoaderLog.warn("SolderPy Modpack Loader status window was interrupted; continuing in the log");
        } catch (InvocationTargetException | RuntimeException e) {
            graphical = false;
            LoaderLog.warn("Could not display SolderPy Modpack Loader status; continuing in the log");
        }
    }

    void waitingForOptionalSelection() {
        downloadPhase = false;
        LoaderLog.info("Waiting for optional content selection");
        runOnEventThread(() -> {
            showSingleActivity();
            if (titleLabel != null) {
                titleLabel.setText("SolderPy Modpack Loader");
            }
            if (phaseLabel != null) {
                phaseLabel.setText("Waiting for optional content selection...");
            }
            if (overall != null) {
                overall.setIndeterminate(false);
                overall.setValue(0);
                overall.setString("Choose your optional content to continue");
            }
            if (currentLabel != null && currentProgress != null) {
                currentLabel.setText("Optional content selection is open");
                currentProgress.setIndeterminate(false);
                currentProgress.setValue(0);
                currentProgress.setString("Waiting for your choice...");
            }
        });
    }

    Window ownerWindow() {
        return graphical ? dialog : null;
    }

    void beginInstalledCheck(List<BootstrapManifest.Package> packages) {
        downloadPhase = false;
        total = packages.size();
        checkedPackages.set(0);
        String message = "Checking " + total + " selected package(s)...";
        LoaderLog.info(message);
        runOnEventThread(() -> {
            showSingleActivity();
            if (titleLabel != null) {
                titleLabel.setText("Checking installed modpack content");
            }
            if (phaseLabel != null) {
                phaseLabel.setText(message);
            }
            if (overall != null) {
                overall.setIndeterminate(false);
                overall.setMinimum(0);
                overall.setMaximum(Math.max(1, total));
                overall.setValue(0);
                overall.setString("0 / " + total + " packages checked");
            }
            resetCurrent("Waiting for installed-file check...");
            resizeDialog();
        });
    }

    void checking(BootstrapManifest.Package item) {
        updateCurrent(item, "Checking installed files",
            "Verifying existing files...", 0, 1, true);
    }

    void checked(BootstrapManifest.Package item, boolean reusable) {
        int finished = checkedPackages.incrementAndGet();
        updateCurrent(item, reusable ? "Already installed" : "Update required",
            reusable ? "Existing files are verified" : "Package will be downloaded",
            1, 1, false);
        runOnEventThread(() -> {
            if (overall != null) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages checked");
            }
        });
    }

    void beginDownloads(List<BootstrapManifest.Package> packages, int concurrency) {
        downloadPhase = true;
        total = packages.size();
        downloadedPackages.set(0);
        resetDownloadSlots(Math.max(1, Math.min(MAX_VISIBLE_DOWNLOADS, concurrency)));
        LoaderLog.info("Downloading " + total + " changed package(s) with up to " +
            concurrency + " concurrent download(s)");
        if (!graphical || packages.isEmpty()) {
            return;
        }
        try {
            runOnEventThreadAndWait(() -> {
                ensureDialog();
                rebuildDownloadRows();
                showDownloadActivity();
                titleLabel.setText("Downloading modpack content");
                phaseLabel.setText("Downloading " + total + " changed package(s)...");
                overall.setIndeterminate(false);
                overall.setMinimum(0);
                overall.setMaximum(total);
                overall.setValue(0);
                overall.setString("0 / " + total + " packages downloaded");
                resizeDialog();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            graphical = false;
            LoaderLog.warn("Download progress window was interrupted; continuing in the log");
        } catch (InvocationTargetException | RuntimeException e) {
            graphical = false;
            LoaderLog.warn("Could not display download progress; continuing in the log");
        }
    }

    void beginInstallation(List<BootstrapManifest.Package> packages, int concurrency) {
        downloadPhase = false;
        invalidateDownloadSlots();
        total = packages.size();
        installedPackages.set(0);
        String message = "Staging " + total + " downloaded package(s) with up to " +
            concurrency + " extraction worker(s)...";
        LoaderLog.info(message);
        runOnEventThread(() -> {
            showSingleActivity();
            if (titleLabel != null) {
                titleLabel.setText("Preparing modpack content");
            }
            if (phaseLabel != null) {
                phaseLabel.setText(message);
            }
            if (overall != null) {
                overall.setIndeterminate(false);
                overall.setMinimum(0);
                overall.setMaximum(total);
                overall.setValue(0);
                overall.setString("0 / " + total + " packages staged");
            }
            resetCurrent("Waiting for staging...");
            resizeDialog();
        });
    }

    void beginVerification(List<BootstrapManifest.Package> packages) {
        downloadPhase = false;
        invalidateDownloadSlots();
        total = packages.size();
        verifiedPackages.set(0);
        String message = "Verifying MD5 for " + total + " downloaded package(s)...";
        LoaderLog.info(message);
        runOnEventThread(() -> {
            showSingleActivity();
            if (titleLabel != null) {
                titleLabel.setText("Verifying downloaded content");
            }
            if (phaseLabel != null) {
                phaseLabel.setText(message);
            }
            if (overall != null) {
                overall.setIndeterminate(false);
                overall.setMinimum(0);
                overall.setMaximum(total);
                overall.setValue(0);
                overall.setString("0 / " + total + " packages verified");
            }
            resetCurrent("Waiting for verification...");
            resizeDialog();
        });
    }

    void downloadStarted(BootstrapManifest.Package item, long expectedBytes) {
        downloadStarted(item, expectedBytes, 0);
    }

    void downloadStarted(
        BootstrapManifest.Package item, long expectedBytes, long resumedBytes) {

        long now = System.nanoTime();
        downloadStarts.put(item.membershipId, now);
        downloadStartOffsets.put(item.membershipId, resumedBytes);
        lastProgressLogs.put(item.membershipId, now);
        LoaderLog.info((resumedBytes > 0 ? "Resuming " : "Downloading ") + displayName(item) +
            (expectedBytes >= 0 ? " (" + formatBytes(expectedBytes) + ")" : ""));
        updateDownload(item, resumedBytes > 0 ? "Resuming download" : "Downloading",
            resumedBytes > 0 ? "Continuing from " + formatBytes(resumedBytes) : "Connecting...",
            resumedBytes, expectedBytes,
            expectedBytes < 0, true);
    }

    void downloadCacheHit(BootstrapManifest.Package item, long bytes) {
        int finished = downloadedPackages.incrementAndGet();
        LoaderLog.info("Using cached download for " + displayName(item) +
            " (" + formatBytes(bytes) + ")");
        updateDownload(item, "Cached", formatBytes(bytes), 1, 1, false, true);
        releaseDownloadSlot(item.membershipId);
        runOnEventThread(() -> {
            if (overall != null && downloadPhase) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages ready");
            }
        });
    }

    void downloadProgress(
        BootstrapManifest.Package item, long downloadedBytes, long expectedBytes) {

        String text = formatBytes(downloadedBytes) +
            (expectedBytes >= 0 ? " / " + formatBytes(expectedBytes) : " downloaded") +
            formatRate(item, downloadedBytes);
        logPeriodicProgress(item, text);
        updateDownload(item, "Downloading", text, downloadedBytes, expectedBytes,
            expectedBytes < 0, true);
    }

    void downloadCompleted(BootstrapManifest.Package item, long downloadedBytes) {
        int finished = downloadedPackages.incrementAndGet();
        String rate = formatRate(item, downloadedBytes);
        LoaderLog.info("Downloaded " + displayName(item) + " (" +
            formatBytes(downloadedBytes) + rate + ")");
        releaseDownloadSlot(item.membershipId);
        downloadStarts.remove(item.membershipId);
        downloadStartOffsets.remove(item.membershipId);
        lastProgressLogs.remove(item.membershipId);
        runOnEventThread(() -> {
            if (overall != null && downloadPhase) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages downloaded");
            }
        });
    }

    void downloadFailed(BootstrapManifest.Package item, String message) {
        downloadStarts.remove(item.membershipId);
        downloadStartOffsets.remove(item.membershipId);
        lastProgressLogs.remove(item.membershipId);
        LoaderLog.warn("Download failed for " + displayName(item) + ": " + message);
        releaseDownloadSlot(item.membershipId);
    }

    void verificationStarted(BootstrapManifest.Package item, long bytes) {
        LoaderLog.info("Verifying MD5 for " + displayName(item));
        updateCurrent(item, "Verifying", "0 B / " + formatBytes(bytes), 0, bytes, false);
    }

    void verificationProgress(
        BootstrapManifest.Package item, long verifiedBytes, long totalBytes) {

        updateCurrent(item, "Verifying",
            formatBytes(verifiedBytes) + " / " + formatBytes(totalBytes),
            verifiedBytes, totalBytes, false);
    }

    void verificationCompleted(BootstrapManifest.Package item, long bytes) {
        int finished = verifiedPackages.incrementAndGet();
        LoaderLog.info("Verified " + displayName(item) + " (" + formatBytes(bytes) + ")");
        updateCurrent(item, "Verified", formatBytes(bytes), 1, 1, false);
        runOnEventThread(() -> {
            if (overall != null) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages verified");
            }
        });
    }

    void verificationFailed(BootstrapManifest.Package item, String message) {
        LoaderLog.warn("Verification failed for " + displayName(item) + ": " + message);
        updateCurrent(item, "Verification failed", message, 0, 1, false);
    }

    void installing(BootstrapManifest.Package item) {
        installing(item, "Staging");
    }

    void installing(BootstrapManifest.Package item, String action) {
        installing(item, action, "Working in the background...");
    }

    void installing(BootstrapManifest.Package item, String action, String detail) {
        LoaderLog.info(action + " " + displayName(item));
        updateCurrent(item, action, detail, 0, 1, true);
    }

    void installed(BootstrapManifest.Package item) {
        int finished = installedPackages.incrementAndGet();
        LoaderLog.info("Staged " + displayName(item));
        updateCurrent(item, "Staged", "Prepared", 1, 1, false);
        runOnEventThread(() -> {
            if (overall != null) {
                overall.setValue(finished);
                overall.setString(finished + " / " + total + " packages staged");
            }
        });
    }

    void phase(String message) {
        downloadPhase = false;
        LoaderLog.info(message);
        runOnEventThread(() -> {
            showSingleActivity();
            if (titleLabel != null) {
                titleLabel.setText("Finishing SolderPy Modpack Loader update");
            }
            if (phaseLabel != null) {
                phaseLabel.setText(message);
            }
            if (overall != null) {
                overall.setIndeterminate(true);
                overall.setString("Please wait...");
            }
            if (currentLabel != null && currentProgress != null) {
                currentLabel.setText(message);
                currentProgress.setValue(0);
                currentProgress.setIndeterminate(true);
                currentProgress.setString("Working in the background...");
            }
            resizeDialog();
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

    private void ensureDialog() {
        if (dialog == null) {
            createDialog();
        }
    }

    private void createDialog() {
        dialog = new JDialog((Frame) null, "SolderPy Modpack Loader", false);
        SolderPyWindowIcon.apply(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        titleLabel = new JLabel("Loading SolderPy Modpack Loader");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20.0f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(5));
        phaseLabel = new JLabel("Starting...");
        phaseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(phaseLabel);
        heading.add(Box.createVerticalStrut(8));
        overall = new JProgressBar(0, 1);
        overall.setStringPainted(true);
        overall.setIndeterminate(true);
        overall.setString("Please wait...");
        overall.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(overall);
        root.add(heading, BorderLayout.NORTH);

        downloadPanel = new JPanel();
        downloadPanel.setLayout(new BoxLayout(downloadPanel, BoxLayout.Y_AXIS));
        rebuildDownloadRows();
        downloadPanel.setVisible(false);

        currentPanel = new JPanel(new BorderLayout(4, 6));
        currentPanel.setBorder(BorderFactory.createTitledBorder("Current package"));
        currentLabel = new JLabel("Starting SolderPy Modpack Loader...");
        currentProgress = new JProgressBar(0, 1000);
        currentProgress.setStringPainted(true);
        currentProgress.setIndeterminate(true);
        currentProgress.setString("Working in the background...");
        currentProgress.setPreferredSize(new Dimension(640, 24));
        currentPanel.add(currentLabel, BorderLayout.NORTH);
        currentPanel.add(currentProgress, BorderLayout.CENTER);
        currentPanel.setVisible(true);

        JPanel activity = new JPanel();
        activity.setLayout(new BoxLayout(activity, BoxLayout.Y_AXIS));
        activity.add(downloadPanel);
        activity.add(currentPanel);
        root.add(activity, BorderLayout.CENTER);

        dialog.setContentPane(root);
        resizeDialog();
        dialog.setVisible(true);
    }

    private void rebuildDownloadRows() {
        if (downloadPanel == null) {
            return;
        }
        downloadPanel.removeAll();
        downloadLabels.clear();
        downloadProgressBars.clear();
        for (int index = 0; index < visibleDownloads; index++) {
            JPanel row = new JPanel(new BorderLayout(4, 6));
            row.setBorder(BorderFactory.createTitledBorder("Download " + (index + 1)));
            JLabel label = new JLabel("Waiting for download...");
            JProgressBar bar = new JProgressBar(0, 1000);
            bar.setStringPainted(true);
            bar.setString("Waiting...");
            bar.setPreferredSize(new Dimension(640, 24));
            row.add(label, BorderLayout.NORTH);
            row.add(bar, BorderLayout.CENTER);
            downloadLabels.add(label);
            downloadProgressBars.add(bar);
            downloadPanel.add(row);
        }
        downloadPanel.revalidate();
        downloadPanel.repaint();
    }

    private void updateCurrent(
        BootstrapManifest.Package item,
        String action,
        String text,
        long value,
        long maximum,
        boolean indeterminate) {

        runOnEventThread(() -> {
            if (currentLabel == null || currentProgress == null) {
                return;
            }
            currentLabel.setText(action + ": " + displayName(item));
            currentProgress.setIndeterminate(indeterminate);
            if (!indeterminate) {
                int scaled = maximum <= 0 ? 0 :
                    (int) Math.min(1000L, (value * 1000L) / maximum);
                currentProgress.setValue(scaled);
            }
            currentProgress.setString(text);
        });
    }

    private void updateDownload(
        BootstrapManifest.Package item,
        String action,
        String text,
        long value,
        long maximum,
        boolean indeterminate,
        boolean claimIfMissing) {

        SlotRef slot = downloadSlot(item.membershipId, claimIfMissing);
        if (slot == null) {
            return;
        }
        runOnEventThread(() -> {
            if (!ownsDownloadSlot(item.membershipId, slot) ||
                slot.index >= downloadLabels.size()) {
                return;
            }
            JLabel label = downloadLabels.get(slot.index);
            JProgressBar bar = downloadProgressBars.get(slot.index);
            label.setText(action + ": " + displayName(item));
            bar.setIndeterminate(indeterminate);
            if (!indeterminate) {
                int scaled = maximum <= 0 ? 0 :
                    (int) Math.min(1000L, (value * 1000L) / maximum);
                bar.setValue(scaled);
            }
            bar.setString(text);
        });
    }

    private void resetDownloadSlots(int count) {
        synchronized (downloadSlotLock) {
            visibleDownloads = count;
            downloadSlotIndexes.clear();
            downloadSlotOwners = new long[count];
            downloadSlotVersions = new long[count];
        }
    }

    private SlotRef downloadSlot(long membershipId, boolean claimIfMissing) {
        synchronized (downloadSlotLock) {
            Integer existing = downloadSlotIndexes.get(membershipId);
            if (existing != null) {
                int index = existing.intValue();
                return new SlotRef(index, downloadSlotVersions[index]);
            }
            if (!claimIfMissing) {
                return null;
            }
            for (int index = 0; index < downloadSlotOwners.length; index++) {
                if (downloadSlotOwners[index] == 0L) {
                    downloadSlotOwners[index] = membershipId;
                    downloadSlotVersions[index]++;
                    downloadSlotIndexes.put(membershipId, index);
                    return new SlotRef(index, downloadSlotVersions[index]);
                }
            }
            return null;
        }
    }

    private boolean ownsDownloadSlot(long membershipId, SlotRef slot) {
        synchronized (downloadSlotLock) {
            return slot.index < downloadSlotOwners.length &&
                downloadSlotOwners[slot.index] == membershipId &&
                downloadSlotVersions[slot.index] == slot.version;
        }
    }

    private void releaseDownloadSlot(long membershipId) {
        final SlotRef released;
        synchronized (downloadSlotLock) {
            Integer value = downloadSlotIndexes.remove(membershipId);
            if (value == null) {
                return;
            }
            int index = value.intValue();
            if (downloadSlotOwners[index] != membershipId) {
                return;
            }
            downloadSlotOwners[index] = 0L;
            downloadSlotVersions[index]++;
            released = new SlotRef(index, downloadSlotVersions[index]);
        }
        runOnEventThread(() -> {
            synchronized (downloadSlotLock) {
                if (released.index >= downloadSlotOwners.length ||
                    downloadSlotOwners[released.index] != 0L ||
                    downloadSlotVersions[released.index] != released.version) {
                    return;
                }
            }
            if (released.index < downloadLabels.size()) {
                downloadLabels.get(released.index).setText("Waiting for download...");
                JProgressBar bar = downloadProgressBars.get(released.index);
                bar.setIndeterminate(false);
                bar.setValue(0);
                bar.setString("Waiting...");
            }
        });
    }

    private void invalidateDownloadSlots() {
        synchronized (downloadSlotLock) {
            downloadSlotIndexes.clear();
            for (int index = 0; index < downloadSlotOwners.length; index++) {
                downloadSlotOwners[index] = 0L;
                downloadSlotVersions[index]++;
            }
        }
    }

    private void showSingleActivity() {
        if (downloadPanel != null) {
            downloadPanel.setVisible(false);
        }
        if (currentPanel != null) {
            currentPanel.setVisible(true);
        }
    }

    private void showDownloadActivity() {
        if (downloadPanel != null) {
            downloadPanel.setVisible(true);
        }
        if (currentPanel != null) {
            currentPanel.setVisible(false);
        }
    }

    private void resizeDialog() {
        if (dialog == null) {
            return;
        }
        dialog.getContentPane().revalidate();
        dialog.pack();
        fitToUsableScreen(dialog);
    }

    private void resetCurrent(String text) {
        if (currentLabel == null || currentProgress == null) {
            return;
        }
        currentLabel.setText(text);
        currentProgress.setIndeterminate(false);
        currentProgress.setValue(0);
        currentProgress.setString("Waiting...");
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
        Long offset = downloadStartOffsets.get(item.membershipId);
        long transferred = downloadedBytes - (offset == null ? 0 : offset.longValue());
        if (transferred <= 0) {
            return "";
        }
        double seconds = (System.nanoTime() - started.longValue()) / 1_000_000_000.0;
        if (seconds < 0.2) {
            return "";
        }
        return " at " + formatBytes((long) (transferred / seconds)) + "/s";
    }

    private void logPeriodicProgress(BootstrapManifest.Package item, String text) {
        long now = System.nanoTime();
        Long last = lastProgressLogs.get(item.membershipId);
        if (last != null && now - last.longValue() >= 5_000_000_000L) {
            lastProgressLogs.put(item.membershipId, now);
            LoaderLog.info("Downloading " + displayName(item) + ": " + text);
        }
    }

    private static final class SlotRef {
        private final int index;
        private final long version;

        private SlotRef(int index, long version) {
            this.index = index;
            this.version = version;
        }
    }
}
