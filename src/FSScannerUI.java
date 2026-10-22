import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main production UI (Parent Frame) for FS Scanner.
 *
 * Orchestrates two-pass FS legal page scanning (_FST top + _FSB bottom) and single-pass
 * scanning (_FS / _A4) with immediate real-time ImageMagick processing off the Swing EDT.
 */
public final class FSScannerUI extends JFrame {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path rawDir = Paths.get(FSScannerDefault.RAW_DIRECTORY).toAbsolutePath().normalize();
    private final FSScannerCH config = new FSScannerCH(Paths.get("config.properties"));
    private final FSScannerData scanData = new FSScannerData(rawDir.resolve("fs-data.bin"));
    private final FSScannerHW hardware = new FSScannerHW();
    private final FSScannerMagic magic = new FSScannerMagic();
    private final FSScannerCR ocr = new FSScannerCR();
    private final FSScannerProcessor processor = new FSScannerProcessor(scanData, magic, ocr);
    private final FSScannerGarbage fileManager = new FSScannerGarbage();
    private final FSScannerBulkProc bulkProcessor = new FSScannerBulkProc(magic);

    private final ExecutorService uiWorker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "FSScanner-UIWorker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean awaitingBottomHalf = new AtomicBoolean(false);
    private volatile Path latestGeneratedPdf;

    // Child UI Panels
    private final FSScannerUINorth northPanel = new FSScannerUINorth();
    private final FSScannerUIWest westPanel = new FSScannerUIWest();
    private final FSScannerUICentre centrePanel = new FSScannerUICentre();
    private final FSScannerUIEastt eastPanel = new FSScannerUIEastt();
    private final FSScannerUISouth southPanel = new FSScannerUISouth();

    public FSScannerUI() {
        super("FS Scanner — 365 Premiere");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 740));
        setSize(1260, 820);
        setExtendedState(6);
        setLocationRelativeTo(null);
        loadWindowIcon();

        buildLayout();
        bindEvents();
        loadInitialState();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                shutdownResources();
            }
        });
    }

    private void loadWindowIcon() {
        Path iconPath = Paths.get("res", "fs-scanner.png");
        if (Files.isRegularFile(iconPath)) {
            ImageIcon icon = new ImageIcon(iconPath.toString());
            if (icon.getIconWidth() > 0) setIconImage(icon.getImage());
        }
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(new Color(241, 245, 249));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        // Right split: Center Live Preview + East Session Ledger
        JSplitPane centerEastSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, centrePanel, eastPanel);
        centerEastSplit.setResizeWeight(0.54);
        centerEastSplit.setContinuousLayout(true);
        centerEastSplit.setBorder(null);

        // Master split: Prominent Left Control Sidebar + Workspace Split
        JSplitPane mainWorkspaceSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, westPanel, centerEastSplit);
        mainWorkspaceSplit.setDividerLocation(FSScannerUIWest.SIDEBAR_WIDTH);
        mainWorkspaceSplit.setResizeWeight(0.0); // Keeps sidebar anchored while workspace expands
        mainWorkspaceSplit.setContinuousLayout(true);
        mainWorkspaceSplit.setBorder(null);

        root.add(northPanel, BorderLayout.NORTH);
        root.add(mainWorkspaceSplit, BorderLayout.CENTER);
        root.add(southPanel, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void bindEvents() {
        // North toolbar events
        northPanel.openFolderBtn.addActionListener(e -> openWorkspaceDirectory());
        northPanel.testBtn.addActionListener(e -> {
            FSScannerTest dialog = new FSScannerTest(this, scanData, config);
            dialog.setVisible(true);
        });

        // West sidebar control events
        westPanel.workflowCombo.addActionListener(e -> {
            updateWorkflowGuidance();
            persistCurrentUiSettings();
        });
        westPanel.dpiCombo.addActionListener(e -> persistCurrentUiSettings());
        westPanel.colourCombo.addActionListener(e -> persistCurrentUiSettings());
        westPanel.qualitySlider.addChangeListener(e -> persistOutputSettings());
        westPanel.scalingSlider.addChangeListener(e -> persistOutputSettings());
        westPanel.scannerCombo.addActionListener(e -> persistSelectedScanner());
        westPanel.refreshScannersButton.addActionListener(e -> discoverScannersAsync(true));
        westPanel.setDefaultScannerButton.addActionListener(e -> setDefaultScanner());

        // East ledger table events
        eastPanel.deleteRowBtn.addActionListener(e -> deleteSelectedCapture());
        eastPanel.clearLedgerBtn.addActionListener(e -> clearSessionLedger());
        eastPanel.sessionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) previewSelectedTableRow();
        });

        // South action button events
        southPanel.primaryScanBtn.addActionListener(e -> {
            String mode = (String) westPanel.workflowCombo.getSelectedItem();
            if ("Top-2-Bottom".equals(mode)) {
                startCaptureAsync("FST");
            } else if ("FS Single Pass".equals(mode)) {
                startCaptureAsync("FS");
            } else {
                startCaptureAsync("A4");
            }
        });

        southPanel.scanBottomBtn.addActionListener(e -> startCaptureAsync("FSB"));
        southPanel.cancelBtn.addActionListener(e -> cancelActiveOperations());
        southPanel.exportPdfBtn.addActionListener(e -> exportLatestPdf());
    }

    private void loadInitialState() {
        try {
            Files.createDirectories(rawDir);
        } catch (IOException ignored) { }

        int savedDpi = parseInt(config.get("scan.dpi", Integer.toString(FSScannerDefault.DPI)), FSScannerDefault.DPI);
        westPanel.dpiCombo.setSelectedItem(savedDpi);
        westPanel.colourCombo.setSelectedItem(config.get("scan.colour", FSScannerDefault.COLOUR));
        westPanel.qualitySlider.setValue(parseInt(config.get("scan.quality", Integer.toString(FSScannerDefault.QUALITY)), FSScannerDefault.QUALITY));
        westPanel.scalingSlider.setValue(parseInt(config.get("scan.scaling", Integer.toString(FSScannerDefault.SCALING)), FSScannerDefault.SCALING));

        String wf = config.get("scan.workflow.profile", "Top-2-Bottom");
        westPanel.workflowCombo.setSelectedItem(wf);
        updateWorkflowGuidance();

        List<FSScannerHW.DeviceInfo> cachedScanners = config.cachedScanners();
        westPanel.populateScannerCombo(cachedScanners, config.get("device.default.id", ""));
        if (cachedScanners.isEmpty()) {
            discoverScannersAsync(false);
        }



        scanData.recoverOrphanCapturesAsync(rawDir)
                .thenCompose(recovered -> scanData.readAllAsync())
                .whenComplete((records, err) -> SwingUtilities.invokeLater(() -> {
                    if (records != null) {
                        eastPanel.tableModel.setRecords(records);
                        syncStepStateFromLedger(records);
                    }
                }));
    }

    private void syncStepStateFromLedger(List<FSScannerData.ScanRecord> records) {
        List<FSScannerData.ScanRecord> pending = records.stream().filter(r -> !r.processed()).toList();
        if (!pending.isEmpty()) {
            FSScannerData.ScanRecord last = pending.get(pending.size() - 1);
            boolean topWaiting = "FST".equals(FSScannerData.scanType(last.name()));
            awaitingBottomHalf.set(topWaiting);
            updateWorkflowGuidance();
        }
    }

    private void updateWorkflowGuidance() {
        String mode = (String) westPanel.workflowCombo.getSelectedItem();
        if ("Top-2-Bottom".equals(mode)) {
            southPanel.primaryScanBtn.setText("1. Scan Top Half (_FST)");
            southPanel.scanBottomBtn.setVisible(true);
            if (awaitingBottomHalf.get()) {
                northPanel.setGuidance(
                        "STEP 2 OF 2 — FLIP PAGE & SCAN BOTTOM",
                        "Top half (_FST) captured! Rotate the FS Legal sheet 180° on the flatbed glass and click '2. Scan Bottom Half (_FSB) & Stitch'.");
                southPanel.scanBottomBtn.setEnabled(!busy.get());
            } else {
                northPanel.setGuidance(
                        "STEP 1 OF 2 — PLACE TOP HALF",
                        "Align the top half of the FS (Legal) page on the scanner glass and click '1. Scan Top Half (_FST)'.");
                southPanel.scanBottomBtn.setEnabled(false);
            }
        } else if ("FS Single Pass".equals(mode)) {
            southPanel.primaryScanBtn.setText("Scan Full FS Page (_FS)");
            southPanel.scanBottomBtn.setVisible(false);
            northPanel.setGuidance(
                    "SINGLE-PASS FS MODE",
                    "Scans and immediately applies real-time ImageMagick FS geometry testing and deskewing.");
        } else {
            southPanel.primaryScanBtn.setText("Scan Standard A4 (_A4)");
            southPanel.scanBottomBtn.setVisible(false);
            northPanel.setGuidance(
                    "STANDARD A4 MODE",
                    "Scans a standard A4 sheet and immediately generates a deskewed, searchable PDF in real time.");
        }
    }

    private void startCaptureAsync(String roleSuffix) {
        if (!busy.compareAndSet(false, true)) return;

        int dpi = (Integer) Objects.requireNonNullElse(westPanel.dpiCombo.getSelectedItem(), FSScannerDefault.DPI);
        String colourUi = (String) Objects.requireNonNullElse(westPanel.colourCombo.getSelectedItem(), FSScannerDefault.COLOUR);
        String gatewayMode = switch (colourUi.toLowerCase(Locale.ROOT)) {
            case "grayscale", "gray" -> "gray";
            case "black & white", "bw" -> "bw";
            default -> "color";
        };
        int quality = westPanel.qualitySlider.getValue();
        int scaling = westPanel.scalingSlider.getValue();
        int brightnessUi = parseInt(config.get("scan.brightness", Integer.toString(FSScannerDefault.BRIGHTNESS)), FSScannerDefault.BRIGHTNESS);
        int contrastUi = parseInt(config.get("scan.contrast", Integer.toString(FSScannerDefault.CONTRAST)), FSScannerDefault.CONTRAST);
        int mappedBrightness = Math.max(-100, Math.min(100, (brightnessUi - 50) * 2));
        int mappedContrast = Math.max(-100, Math.min(100, (contrastUi - 50) * 2));

        String workflow = "FSB".equals(roleSuffix)
                ? "Top-2-Bottom-bottom"
                : (String) Objects.requireNonNullElse(westPanel.workflowCombo.getSelectedItem(), "Top-2-Bottom");

        String timestamp = LocalDateTime.now().format(FILE_TS);
        String fileName = "SCAN_" + timestamp + "_" + roleSuffix + ".png";
        Path outputPath = rawDir.resolve(fileName);

        setUiBusyState(true, "Acquiring " + roleSuffix + " scan from hardware at " + dpi + " DPI...");

        FSScannerHW.ScanRequest request = new FSScannerHW.ScanRequest(
                outputPath,
                gatewayMode,
                dpi,
                FSScannerDefault.GATEWAY_SOURCE,
                "png",
                quality,
                1,
                FSScannerDefault.GATEWAY_TIMEOUT_SECONDS,
                mappedBrightness,
                mappedContrast,
                null,
                false,
                false,
                new FSScannerHW.CropArea(0.0, 0.0,
                        FSScannerDefault.SCAN_WIDTH_MM, FSScannerDefault.SCAN_HEIGHT_MM)
        );

        scanData.saveSelectionAsync(workflow, dpi, colourUi, quality, scaling, mappedBrightness, mappedContrast)
                .thenCompose(v -> hardware.scanAsync(request))
                .thenCompose(gwResult -> {
                    Path actualFile = resolveCapturedFile(outputPath, roleSuffix);
                    long bytes = fileSizeSafe(actualFile);
                    return scanData.recordAsync(
                            actualFile,
                            actualFile.getFileName().toString(),
                            bytes,
                            colourUi,
                            dpi,
                            quality,
                            scaling,
                            mappedBrightness,
                            mappedContrast,
                            workflow
                    );
                })
                .whenComplete((record, error) -> SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        setUiBusyState(false, "Scan failed: " + rootMessage(error));
                        JOptionPane.showMessageDialog(this,
                                "Hardware scan could not complete:\n" + rootMessage(error),
                                "Scan Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    loadPreviewImageAsync(record.filePath());
                    refreshLedgerTableAsync();

                    if ("FST".equals(roleSuffix)) {
                        awaitingBottomHalf.set(true);
                        setUiBusyState(false, "Top half (_FST) captured. Flip page 180° and click 'Scan Bottom Half (_FSB) & Stitch'.");
                        updateWorkflowGuidance();
                    } else {
                        awaitingBottomHalf.set(false);
                        busy.set(false);
                        southPanel.exportPdfBtn.setEnabled(true);
                        setUiBusyState(false, "Captures complete. Processing document in background...");
                        updateWorkflowGuidance();
                        triggerRealTimeProcessingAsync("Background document processing");
                    }
                }));
    }

    private void triggerRealTimeProcessingAsync(String reason) {
        if (!busy.compareAndSet(false, true)) return;
        setUiBusyState(true, reason + " in progress...");

        processor.processSessionAsync().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            setUiBusyState(false, error != null
                    ? "Real-time processing error: " + rootMessage(error)
                    : result.message());
            refreshLedgerTableAsync();

            if (error != null) {
                JOptionPane.showMessageDialog(this,
                        "Real-time ImageMagick processing failed:\n" + rootMessage(error),
                        "Processing Error", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (result != null && !result.outputs().isEmpty()) {
                for (Path out : result.outputs()) {
                    if (out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        latestGeneratedPdf = out;
                        southPanel.exportPdfBtn.setEnabled(true);
                    }
                }
            }
            updateWorkflowGuidance();
        }));
    }

    private Path resolveCapturedFile(Path requestedOutput, String roleSuffix) {
        if (Files.isRegularFile(requestedOutput)) return requestedOutput;
        try (var stream = Files.list(rawDir)) {
            Optional<Path> newest = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".tif");
                    })
                    .max(Comparator.comparingLong(FSScannerUI::lastModifiedSafe));
            if (newest.isPresent()) {
                Path found = newest.get();
                if ("UNKNOWN".equals(FSScannerData.scanType(found.getFileName().toString()))) {
                    Path normalized = rawDir.resolve("SCAN_" + LocalDateTime.now().format(FILE_TS) + "_" + roleSuffix + ".png");
                    Files.move(found, normalized, StandardCopyOption.REPLACE_EXISTING);
                    return normalized;
                }
                return found;
            }
        } catch (IOException ignored) { }
        return requestedOutput;
    }

    private void discoverScannersAsync(boolean showDialogOnEmpty) {
        southPanel.statusLabel.setText("Discovering connected scanners via ScannerAPIGateway...");
        southPanel.progressBar.setIndeterminate(true);
        hardware.listDevicesAsync().whenComplete((devices, err) -> SwingUtilities.invokeLater(() -> {
            southPanel.progressBar.setIndeterminate(false);
            if (err != null) {
                southPanel.statusLabel.setText("Scanner discovery failed: " + rootMessage(err));
                return;
            }
            List<FSScannerHW.DeviceInfo> safe = devices == null ? List.of() : devices;
            westPanel.populateScannerCombo(safe, config.get("device.default.id", ""));
            uiWorker.submit(() -> {
                try { config.cacheScanners(safe); } catch (IOException ignored) { }
            });
            southPanel.statusLabel.setText(safe.isEmpty()
                    ? "No scanners reported by gateway (using auto-discovery)."
                    : "Discovered " + safe.size() + " scanner device(s).");
            if (showDialogOnEmpty && safe.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "No scanner hardware was reported by ScannerAPIGateway.\nEnsure your scanner is powered on and connected via USB/Network.",
                        "Scanner Discovery", JOptionPane.INFORMATION_MESSAGE);
            }
        }));
    }

    private void persistSelectedScanner() {
        FSScannerUIWest.DeviceItem selected = (FSScannerUIWest.DeviceItem) westPanel.scannerCombo.getSelectedItem();
        if (selected == null) return;
        config.put("device.selected.id", selected.id());
        try { config.save(); } catch (IOException ex) {
            southPanel.statusLabel.setText("Could not save selected scanner: " + ex.getMessage());
        }
    }

    private void setDefaultScanner() {
        FSScannerUIWest.DeviceItem selected = (FSScannerUIWest.DeviceItem) westPanel.scannerCombo.getSelectedItem();
        if (selected == null || selected.id().isBlank()) {
            JOptionPane.showMessageDialog(this, "Select a discovered scanner first.", "Set Default Scanner", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        config.put("device.default.id", selected.id());
        config.put("device.default", selected.label().replace(" (Default)", ""));
        config.put("device.selected.id", selected.id());
        try {
            config.save();
            westPanel.markDefaultScanner(selected.id());
            southPanel.statusLabel.setText("Default scanner saved: " + selected.label().replace(" (Default)", ""));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save default scanner: " + ex.getMessage(), "Scanner Settings", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void persistOutputSettings() {
        Properties p = new Properties();
        p.setProperty("scan.quality", Integer.toString(westPanel.qualitySlider.getValue()));
        p.setProperty("scan.scaling", Integer.toString(westPanel.scalingSlider.getValue()));
        uiWorker.submit(() -> {
            try { config.saveFrom(p); } catch (IOException ignored) { }
        });
    }

    private void persistCurrentUiSettings() {
        Properties p = new Properties();
        Object dpiObj = westPanel.dpiCombo.getSelectedItem();
        if (dpiObj != null) p.setProperty("scan.dpi", dpiObj.toString());
        Object colObj = westPanel.colourCombo.getSelectedItem();
        if (colObj != null) p.setProperty("scan.colour", colObj.toString());
        Object wfObj = westPanel.workflowCombo.getSelectedItem();
        if (wfObj != null) p.setProperty("scan.workflow.profile", wfObj.toString());

        uiWorker.submit(() -> {
            try { config.saveFrom(p); } catch (IOException ignored) { }
        });
    }

    private void loadPreviewImageAsync(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        uiWorker.submit(() -> {
            try {
                BufferedImage img = ImageIO.read(file.toFile());
                if (img != null) {
                    SwingUtilities.invokeLater(() ->
                            centrePanel.setPreviewImage(img, file.getFileName().toString()));
                }
            } catch (IOException ignored) { }
        });
    }

    private void previewSelectedTableRow() {
        FSScannerData.ScanRecord rec = eastPanel.getSelectedRecord();
        if (rec != null && Files.isRegularFile(rec.filePath())) {
            loadPreviewImageAsync(rec.filePath());
        }
    }

    private void refreshLedgerTableAsync() {
        scanData.readAllAsync().whenComplete((records, err) -> SwingUtilities.invokeLater(() -> {
            if (records != null) {
                eastPanel.tableModel.setRecords(records);
                syncStepStateFromLedger(records);
            }
        }));
    }

    private void deleteSelectedCapture() {
        FSScannerData.ScanRecord rec = eastPanel.getSelectedRecord();
        if (rec == null) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete the selected capture and remove its record?\n" + rec.name(),
                "Delete Capture", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;
        setUiBusyState(true, "Deleting selected capture...");
        scanData.deleteRecordAsync(rec.id()).whenComplete((deleted, err) -> SwingUtilities.invokeLater(() -> {
            setUiBusyState(false, err == null ? "Capture deleted." : "Delete failed: " + rootMessage(err));
            refreshLedgerTableAsync();
            if (err != null) JOptionPane.showMessageDialog(this,
                    "Could not delete the selected capture:\n" + rootMessage(err),
                    "Delete Error", JOptionPane.ERROR_MESSAGE);
            else if (!Boolean.TRUE.equals(deleted)) JOptionPane.showMessageDialog(this,
                    "The selected record no longer exists.", "Delete Capture", JOptionPane.INFORMATION_MESSAGE);
        }));
    }

    private void clearSessionLedger() {
        if (JOptionPane.showConfirmDialog(this,
                "Clear all session records from fs-data.bin?",
                "Clear Session", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        awaitingBottomHalf.set(false);
        scanData.clearAsync().whenComplete((v, err) -> SwingUtilities.invokeLater(() -> {
            centrePanel.setPreviewImage(null, "");
            refreshLedgerTableAsync();
            updateWorkflowGuidance();
            southPanel.statusLabel.setText("Session ledger cleared.");
        }));
    }

    private void exportLatestPdf() {
        setUiBusyState(true, "Preparing bulk PDF...");
        processor.processSessionAsync().thenCompose(ignored -> bulkProcessor.mergeDirectoryAsync(rawDir, westPanel.qualitySlider.getValue()))
                .whenComplete((bulkPdf, mergeError) -> SwingUtilities.invokeLater(() -> {
            if (mergeError != null) {
                setUiBusyState(false, "Bulk PDF unavailable: " + rootMessage(mergeError));
                JOptionPane.showMessageDialog(this, "Could not create the bulk PDF:\n" + rootMessage(mergeError),
                        "Bulk PDF", JOptionPane.WARNING_MESSAGE);
                return;
            }
            latestGeneratedPdf = bulkPdf;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Bulk PDF");
            chooser.setSelectedFile(bulkPdf.getFileName().toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                setUiBusyState(false, "Bulk PDF ready in workspace: " + bulkPdf.getFileName());
                return;
            }
            Path target = chooser.getSelectedFile().toPath();
            if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                target = target.resolveSibling(target.getFileName().toString() + ".pdf");
            Path finalTarget = target;
            fileManager.copyAsync(bulkPdf, finalTarget, true).whenComplete((res, err) -> SwingUtilities.invokeLater(() -> {
                setUiBusyState(false, err == null ? "Saved bulk PDF to: " + finalTarget : "Save failed: " + rootMessage(err));
                if (err != null) JOptionPane.showMessageDialog(this, "Save failed: " + rootMessage(err),
                        "Save Bulk PDF", JOptionPane.ERROR_MESSAGE);
            }));
        }));
    }

    private void openWorkspaceDirectory() {
        try {
            Files.createDirectories(rawDir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(rawDir.toFile());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Unable to open folder: " + ex.getMessage(),
                    "Workspace", JOptionPane.WARNING_MESSAGE);
        }
    }



    private void cancelActiveOperations() {
        hardware.cancelActiveScan();
        processor.cancelProcessing();
        setUiBusyState(false, "Operation cancelled by user.");
    }

    private void setUiBusyState(boolean isBusy, String statusText) {
        busy.set(isBusy);
        southPanel.setBusyState(isBusy, awaitingBottomHalf.get(), statusText);
    }

    private void shutdownResources() {
        uiWorker.shutdownNow();
        hardware.close();
        processor.close();
        fileManager.close();
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static long fileSizeSafe(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    private static long lastModifiedSafe(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}