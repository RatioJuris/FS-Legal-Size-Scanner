import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * Production-grade PDF/image testing window for FS Scanner.
 *
 * All image cropping, rotation, brightness, and seam-stitching calculations
 * execute asynchronously off the Swing EDT using hardware-accelerated Java2D ops.
 */
public final class FSScannerTest extends JDialog {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FSScanner-tester");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final JTextField pageWidth = new JTextField(Double.toString(FSScannerDefault.TEST_PAGE_WIDTH_MM), 7);
    private final JTextField pageHeight = new JTextField(Double.toString(FSScannerDefault.TEST_PAGE_HEIGHT_MM), 7);
    private final JTextField topX = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4), topY = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4);
    private final JTextField topW = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4), topH = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4);
    private final JTextField bottomX = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4), bottomY = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4);
    private final JTextField bottomW = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4), bottomH = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4);
    private final JTextField rotation = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4), joinOffset = new JTextField(Integer.toString(FSScannerDefault.TEST_COORDINATE_DEFAULT), 4);
    private final FSScannerData scanData;
    private final FSScannerCH config;
    private final FSScannerHW hardware = new FSScannerHW();
    private volatile String previewType = "FS";
    /** False during automatic pre-alignment; true only while the user is adjusting the live seam. */
    private volatile boolean alignmentMode = true;
    private final JSlider quality = new JSlider(10, 100, FSScannerDefault.QUALITY);
    private final JSlider scaling = new JSlider(25, 200, FSScannerDefault.SCALING);
    private final JSlider brightness = new JSlider(0, 100, FSScannerDefault.BRIGHTNESS);
    private final JSlider contrast = new JSlider(0, 100, FSScannerDefault.CONTRAST);
    private final JLabel qualityValue = new JLabel(FSScannerDefault.QUALITY + "%");
    private final JLabel scalingValue = new JLabel(FSScannerDefault.SCALING + "%");
    private final JLabel contrastValue = new JLabel(Integer.toString(FSScannerDefault.CONTRAST));
    private final JSlider dpi = new JSlider(75, 1200, FSScannerDefault.DPI);
    private final JCheckBox autoCrop = new JCheckBox("Enable automatic cropping", FSScannerDefault.TEST_AUTO_CROP);
    private final JCheckBox showSeamGuide = new JCheckBox("Show visual stitch seam line", true);
    private final JLabel brightnessValue = new JLabel(Integer.toString(FSScannerDefault.TEST_PREVIEW_BRIGHTNESS));
    private final JLabel dpiValue = new JLabel(FSScannerDefault.DPI + " DPI");
    private final JLabel previewStatus = new JLabel("Scan FST and FSB, then drag the two red crop guides on either raw scan to update the merged result.");
    private final FSScannerViewer preview = new FSScannerViewer(true, false, 215.9, 355.6);
    private final FSScannerViewer topPreview = new FSScannerViewer(true, true, 215.9, 297.0);
    private final FSScannerViewer bottomPreview = new FSScannerViewer(true, true, 215.9, 297.0);

    private volatile BufferedImage previewImage;
    private volatile BufferedImage previewBottomImage;
    private Future<?> activeLoadTask;
    private Future<?> activeRenderTask;

    public FSScannerTest(Frame owner) {
        this(owner, new FSScannerData(FSScannerPaths.workspaceDirectory().resolve("fs-data.bin")),
                new FSScannerCH(Paths.get("config.properties")));
    }

    public FSScannerTest(Frame owner, FSScannerData scanData) {
        this(owner, scanData, new FSScannerCH(Paths.get("config.properties")));
    }

    public FSScannerTest(Frame owner, FSScannerData scanData, FSScannerCH config) {
        super(owner, "FS Scanner — Device & Legal Page Seam Test", Dialog.ModalityType.APPLICATION_MODAL);
        this.scanData = java.util.Objects.requireNonNull(scanData, "scanData");
        this.config = java.util.Objects.requireNonNull(config, "config");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setAlwaysOnTop(false);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1500, 900);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setBounds(screen);
        setLocationRelativeTo(owner);

        setIconImages(loadWindowIcons());
        buildUI();

        javax.swing.event.DocumentListener refreshListener = new javax.swing.event.DocumentListener() {
            private void changed() { if (previewImage != null) schedulePreviewRefresh(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        for (JTextField field : java.util.List.of(topX, topY, topW, topH, bottomX, bottomY, bottomW, bottomH, rotation, joinOffset, pageHeight)) {
            field.getDocument().addDocumentListener(refreshListener);
        }
        autoCrop.addActionListener(e -> schedulePreviewRefresh());
        showSeamGuide.addActionListener(e -> schedulePreviewRefresh());
        loadTestSettings();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                closed.set(true);
                if (activeLoadTask != null) activeLoadTask.cancel(true);
                if (activeRenderTask != null) activeRenderTask.cancel(true);
                worker.shutdownNow();
                hardware.close();
            }
        });
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.setBackground(new Color(242, 245, 248));

        // No redundant heading or workflow banners: maximize the three preview canvases.

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        // Three peer panels in one horizontal row: FST | FSB | live result.
        // Keep the source previews separate so each scan's crop guides remain visible
        // beside the actual merged page; do not nest the two raw scans vertically.
        JPanel previews = new JPanel(new GridLayout(1, 3, 4, 0));
        previews.setOpaque(false);
        previews.add(previewCard("RAW SCAN TOP — FST", topPreview, "SCAN FST", false));
        previews.add(previewCard("RAW SCAN BOTTOM — FSB (180°)", bottomPreview, "SCAN FSB", true));
        previews.add(previewCard("REALTIME ADJUSTMENT — MERGED RESULT", preview, null, false));
        center.add(previews, BorderLayout.CENTER);

        previewStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        previewStatus.setForeground(new Color(80, 90, 100));
        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        status.add(previewStatus, BorderLayout.CENTER);
        center.add(status, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        // Scan buttons are under the source previews; Restore Defaults and Save Test
        // are under the Live Preview. Only output settings remain in the bottom strip.
        south.add(buildSettingsPanel());
        south.add(Box.createVerticalStrut(8));
        root.add(south, BorderLayout.SOUTH);

        preview.setAdjustmentHandler(delta -> schedulePreviewRefresh());
        topPreview.setGuideHandler((guide, y) -> {
            if (previewImage == null) return;
            int start = topPreview.getGuideStartY();
            int end = topPreview.getGuideEndY();
            topY.setText(Integer.toString(Math.max(0, start)));
            topH.setText(Integer.toString(Math.max(1, end - start)));
            topW.setText(Integer.toString(previewImage.getWidth()));
            previewStatus.setText("FST crop guides changed; rebuilding the actual merged page…");
            schedulePreviewRefresh();
        });
        bottomPreview.setGuideHandler((guide, y) -> {
            if (previewBottomImage == null) return;
            int start = bottomPreview.getGuideStartY();
            int end = bottomPreview.getGuideEndY();
            int h = Math.max(1, end - start);
            // Keep FSB crop coordinates in the same frame as the rotated preview.
            // Both preview and production rotate the full capture before applying this crop.
            bottomY.setText(Integer.toString(Math.max(0, start)));
            bottomH.setText(Integer.toString(h));
            bottomW.setText(Integer.toString(previewBottomImage.getWidth()));
            previewStatus.setText("FSB crop guides changed; rebuilding the actual merged page…");
            schedulePreviewRefresh();
        });
        setContentPane(root);
    }

    /** Switches between automatic mathematical pre-alignment and live seam adjustment. */
    private void setAlignmentMode(boolean enabled) {
        if (enabled && (previewImage == null || previewBottomImage == null)) {
            previewStatus.setText("Scan both FST and FSB first; pre-alignment needs both source images.");
            return;
        }
        alignmentMode = enabled;
        if (previewImage != null) {
            int initial = savedSeam("top", previewImage.getHeight(), 7.0 / 11.6929134);
            topPreview.setCropGuides(previewImage, parseInt(topY.getText(), 0), parseInt(topY.getText(), 0) + (parseInt(topH.getText(), 0) > 0 ? parseInt(topH.getText(), 0) : previewImage.getHeight()));
        }
        previewStatus.setText("Adjust either raw scan crop guide; the merged result updates automatically.");
        schedulePreviewRefresh();
    }

    private JPanel previewCard(String title, FSScannerViewer panel, String actionLabel, boolean bottom) {
        JPanel card = new JPanel(new BorderLayout(2, 2));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 212, 220)), new EmptyBorder(3, 3, 3, 3)));
        // Keep the canvas itself as large as possible; identify each preview by its
        // position (FST | FSB | result) and scan action rather than redundant headings.
        // Rulers are permanently rendered by FSScannerViewer.
        panel.setRulerDpi(dpi.getValue());
        panel.setMinimumSize(new Dimension(150, 180));
        panel.setPreferredSize(new Dimension(300, 360));
        JScrollPane viewport = new JScrollPane(panel);
        viewport.setWheelScrollingEnabled(true);
        card.add(viewport, BorderLayout.CENTER);

        JPanel action = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        action.setOpaque(false);
        if (actionLabel != null) {
            JButton scan = new JButton("⚡ " + actionLabel);
            scan.setPreferredSize(new Dimension(150, 30));
            scan.addActionListener(e -> captureTestAsync(bottom, scan));
            action.add(scan);
        } else {
            JButton defaults = new JButton("Restore Defaults");
            defaults.addActionListener(e -> restoreDefaultSettingsAsync());
            JButton save = new JButton("✓ SAVE TEST");
            save.setPreferredSize(new Dimension(130, 30));
            save.addActionListener(e -> saveSettingsAsync(save));
            action.add(defaults);
            action.add(save);
        }
        card.add(action, BorderLayout.SOUTH);
        return card;
    }

    private void adjustJoinOffset(int delta) {
        // Legacy callback retained for source compatibility. Alignment is now
        // controlled by the source-image laser endpoints, not a numeric join offset.
        previewStatus.setText("The merged result updates automatically when crop guides move.");
        schedulePreviewRefresh();
    }

    private JPanel buildSettingsPanel() {
        JPanel settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.setBackground(Color.WHITE);
        settings.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 212, 220)), new EmptyBorder(8, 10, 8, 10)));
        settings.add(sectionLabel("Scan & Output Settings"));
        settings.add(sliderRow("Brightness", brightness, brightnessValue, ""));
        settings.add(sliderRow("Contrast", contrast, contrastValue, ""));
        JPanel dpiRow = new JPanel(new BorderLayout(8, 0));
        dpiRow.setOpaque(false);
        dpiRow.add(new JLabel("Resolution"), BorderLayout.WEST);
        dpi.setOpaque(false);
        dpi.setMajorTickSpacing(225);
        dpi.setPaintTicks(true);
        dpiRow.add(dpi, BorderLayout.CENTER);
        dpiValue.setText(dpi.getValue() + " DPI");
        dpiRow.add(dpiValue, BorderLayout.EAST);
        dpi.addChangeListener(e -> {
            dpiValue.setText(dpi.getValue() + " DPI");
            topPreview.setRulerDpi(dpi.getValue());
            bottomPreview.setRulerDpi(dpi.getValue());
            preview.setRulerDpi(dpi.getValue());
        });
        settings.add(dpiRow);

        JPanel geometry = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        geometry.setOpaque(false);
        geometry.add(new JLabel("Target FS width: 215.9 mm (fixed)"));
        geometry.add(new JLabel("Page height and seam placement are calculated internally from the visual alignment."));
        settings.add(geometry);

        brightness.addChangeListener(e -> {
            brightnessValue.setText(Integer.toString(brightness.getValue()));
            if (previewImage != null) schedulePreviewRefresh();
        });
        contrast.addChangeListener(e -> {
            contrastValue.setText(Integer.toString(contrast.getValue()));
            if (previewImage != null) schedulePreviewRefresh();
        });
        return settings;
    }

    private JPanel sliderRow(String label, JSlider slider, JLabel value, String suffix) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(1, 0, 1, 0));
        row.add(new JLabel(label), BorderLayout.WEST);
        slider.setOpaque(false);
        row.add(slider, BorderLayout.CENTER);
        value.setText(slider.getValue() + suffix);
        row.add(value, BorderLayout.EAST);
        slider.addChangeListener(e -> value.setText(slider.getValue() + suffix));
        return row;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void captureTestAsync(boolean bottom, JButton button) {
        if (closed.get()) return;
        button.setEnabled(false);
        previewStatus.setText(bottom
                ? "Place the same page's lower section on the flatbed, rotated 180°, then scanning..."
                : "Place the upper section of the test page on the flatbed, then scanning...");
        worker.submit(() -> {
            Path testDir = FSScannerPaths.workspaceDirectory();
            Path output = testDir.resolve(bottom ? "FSScannerTest_FSB.png" : "FSScannerTest_FST.png");
            try {
                Files.createDirectories(testDir);
                Files.deleteIfExists(output);
                int selectedDpi = dpi.getValue();
                int qualitySetting = quality.getValue();
                int brightnessSetting = Math.max(-100, Math.min(100, (brightness.getValue() - 50) * 2));
                int contrastSetting = Math.max(-100, Math.min(100, (contrast.getValue() - 50) * 2));
                String colour = config.get("scan.colour", FSScannerDefault.COLOUR).toLowerCase(java.util.Locale.ROOT);
                String mode = colour.contains("gray") || colour.contains("grey") ? "gray"
                        : (colour.equals("bw") || colour.contains("black") ? "bw" : "color");
                FSScannerHW.ScanRequest request = new FSScannerHW.ScanRequest(
                        output, mode, selectedDpi, FSScannerDefault.GATEWAY_SOURCE, "png", qualitySetting, 1,
                        FSScannerDefault.GATEWAY_TIMEOUT_SECONDS, brightnessSetting, contrastSetting,
                        null, false, false,
                        new FSScannerHW.CropArea(0.0, 0.0,
                                FSScannerDefault.SCAN_WIDTH_MM, FSScannerDefault.SCAN_HEIGHT_MM));
                hardware.scanAsync(request).join();
                if (!Files.isRegularFile(output) || Files.size(output) == 0)
                    throw new IOException("Scanner completed but the expected test image was not created: " + output);
                BufferedImage image = ImageIO.read(output.toFile());
                if (image == null) throw new IOException("The captured test image could not be decoded.");
                int widthPx = image.getWidth(), heightPx = image.getHeight();
                SwingUtilities.invokeLater(() -> {
                    if (closed.get()) { image.flush(); return; }
                    if (bottom) {
                        if (previewBottomImage != null) previewBottomImage.flush();
                        previewBottomImage = image;
                        bottomPreview.setCropGuides(rotatePreview180(image), parseInt(bottomY.getText(), 0), parseInt(bottomY.getText(), 0) + (parseInt(bottomH.getText(), 0) > 0 ? parseInt(bottomH.getText(), 0) : defaultBottomGuideHeight(image)));
                        previewType = "FST";
                    } else {
                        if (previewImage != null) previewImage.flush();
                        previewImage = image;
                        topPreview.setCropGuides(image, parseInt(topY.getText(), 0), parseInt(topY.getText(), 0) + (parseInt(topH.getText(), 0) > 0 ? parseInt(topH.getText(), 0) : image.getHeight()));
                        previewBottomImage = null;
                        bottomPreview.setImage(null, -1);
                        previewType = "FST";
                    }
                    boolean measurementSaved = true;
                    try {
                        String side = bottom ? "bottom" : "top";
                        config.put("alignment.measurement." + side + ".width.px", Integer.toString(widthPx));
                        config.put("alignment.measurement." + side + ".height.px", Integer.toString(heightPx));
                        config.put("alignment.measurement." + side + ".dpi", Integer.toString(selectedDpi));
                        config.put("alignment.measurement." + side + ".file", output.toString());
                        config.put("alignment.measurement." + side + ".capturedAt", java.time.Instant.now().toString());
                        config.save();
                    } catch (IOException configError) {
                        measurementSaved = false;
                        previewStatus.setText("Captured " + output.getFileName() + " (" + widthPx + " × " + heightPx
                                + " px), but measurement metadata could not be saved: " + safeMessage(configError));
                    }
                    if (measurementSaved) {
                        if (bottom) {
                            previewStatus.setText("Captured bottom test: " + widthPx + " × " + heightPx + " px at " + selectedDpi + " DPI. Measurements saved to config.properties.");
                        } else {
                            previewStatus.setText("Captured top test: " + widthPx + " × " + heightPx + " px at " + selectedDpi + " DPI. Measurements saved. Flip the page 180° to capture the bottom section.");
                        }
                    }
                    schedulePreviewRefresh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed.get()) {
                        previewStatus.setText("Test scan failed: " + safeMessage(ex));
                        JOptionPane.showMessageDialog(this, "Test scan failed: " + safeMessage(ex),
                                "Test Scan", JOptionPane.ERROR_MESSAGE);
                    }
                });
            } finally {
                SwingUtilities.invokeLater(() -> button.setEnabled(!closed.get()));
            }
        });
    }

    private void chooseImageAsync() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a Top (_FST) or Single (_FS) image for test preview");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (PNG, JPEG, BMP, GIF, TIFF)", "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = chooser.getSelectedFile().toPath();
        previewStatus.setText("Loading preview image in background...");
        if (activeLoadTask != null) activeLoadTask.cancel(true);

        activeLoadTask = worker.submit(() -> {
            try {
                BufferedImage image = ImageIO.read(selected.toFile());
                if (image == null) throw new IOException("This image format could not be decoded.");
                SwingUtilities.invokeLater(() -> {
                    if (closed.get()) return;
                    previewImage = image;
                    topPreview.setCropGuides(image, parseInt(topY.getText(), 0), parseInt(topY.getText(), 0) + (parseInt(topH.getText(), 0) > 0 ? parseInt(topH.getText(), 0) : image.getHeight()));
                    previewType = FSScannerData.scanType(selected.getFileName().toString());
                    if (!"FST".equals(previewType) && !"FSB".equals(previewType)) previewType = "FS";
                    previewStatus.setText("Loaded: " + selected.getFileName()
                            + " (" + image.getWidth() + " × " + image.getHeight() + " px).");
                    schedulePreviewRefresh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed.get()) {
                        previewStatus.setText("Could not load image: " + safeMessage(ex));
                        JOptionPane.showMessageDialog(this, safeMessage(ex), "Preview", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        });
    }

    private void chooseBottomImageAsync() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose the _FSB bottom image for merge preview");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (PNG, JPEG, BMP, TIFF)", "png", "jpg", "jpeg", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath();
        previewStatus.setText("Loading bottom image in background...");
        if (activeLoadTask != null) activeLoadTask.cancel(true);
        activeLoadTask = worker.submit(() -> {
            try {
                BufferedImage image = ImageIO.read(selected.toFile());
                if (image == null) throw new IOException("This image format could not be decoded.");
                SwingUtilities.invokeLater(() -> {
                    if (closed.get()) return;
                    previewBottomImage = image;
                    bottomPreview.setCropGuides(rotatePreview180(image), parseInt(bottomY.getText(), 0), parseInt(bottomY.getText(), 0) + (parseInt(bottomH.getText(), 0) > 0 ? parseInt(bottomH.getText(), 0) : defaultBottomGuideHeight(image)));
                    previewType = "FST";
                    schedulePreviewRefresh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed.get()) previewStatus.setText("Could not load bottom image: " + safeMessage(ex));
                });
            }
        });
    }

    private int defaultBottomGuideHeight(BufferedImage image) {
        int dpiValue = dpi.getValue() > 0 ? dpi.getValue() : FSScannerDefault.DPI;
        return Math.max(1, Math.min(image.getHeight(), (int)Math.round((58.6 / 25.4) * dpiValue)));
    }

    private int savedSeam(String side, int imageHeight, double defaultFraction) {
        int fallback = Math.max(1, (int)Math.round(imageHeight * defaultFraction));
        int saved = parseInt(config.get("alignment." + side + ".seam.y", Integer.toString(fallback)), fallback);
        return Math.max(1, Math.min(imageHeight, saved));
    }

    private void loadTestSettings() {
        pageWidth.setText("215.9");
        pageHeight.setText(Double.toString(FSScannerDefault.TEST_PAGE_HEIGHT_MM));
        quality.setValue(parseInt(config.get("scan.quality", Integer.toString(FSScannerDefault.QUALITY)), FSScannerDefault.QUALITY));
        scaling.setValue(parseInt(config.get("scan.scaling", Integer.toString(FSScannerDefault.SCALING)), FSScannerDefault.SCALING));
        brightness.setValue(parseInt(config.get("scan.brightness", Integer.toString(FSScannerDefault.BRIGHTNESS)), FSScannerDefault.BRIGHTNESS));
        contrast.setValue(parseInt(config.get("scan.contrast", Integer.toString(FSScannerDefault.CONTRAST)), FSScannerDefault.CONTRAST));
        dpi.setValue(parseInt(config.get("alignment.dpi", Integer.toString(FSScannerDefault.DPI)), FSScannerDefault.DPI));
        autoCrop.setSelected(FSScannerDefault.TEST_AUTO_CROP);
        topX.setText("0"); topY.setText("0"); topW.setText("0"); topH.setText("0");
        bottomX.setText("0"); bottomY.setText("0"); bottomW.setText("0"); bottomH.setText("0");
        rotation.setText("0"); joinOffset.setText("0");
        brightnessValue.setText(Integer.toString(brightness.getValue()));
        qualityValue.setText(quality.getValue() + "%");
        scalingValue.setText(scaling.getValue() + "%");
        contrastValue.setText(Integer.toString(contrast.getValue()));
        dpiValue.setText(dpi.getValue() + " DPI");
        scanData.loadTestAsync().whenComplete((saved, error) -> SwingUtilities.invokeLater(() -> {
            if (closed.get() || error != null || saved == null) return;
            pageWidth.setText("215.9");
            pageHeight.setText(config.get("alignment.page.height.mm", saved.getProperty("page.height.mm", pageHeight.getText())));
            topX.setText(config.get("alignment.top.x", saved.getProperty("top.x", topX.getText())));
            topY.setText(config.get("alignment.top.y", saved.getProperty("top.y", topY.getText())));
            topW.setText(config.get("alignment.top.w", saved.getProperty("top.w", topW.getText())));
            topH.setText(config.get("alignment.top.h", saved.getProperty("top.h", topH.getText())));
            bottomX.setText(config.get("alignment.bottom.x", saved.getProperty("bottom.x", bottomX.getText())));
            bottomY.setText(config.get("alignment.bottom.y", saved.getProperty("bottom.y", bottomY.getText())));
            bottomW.setText(config.get("alignment.bottom.w", saved.getProperty("bottom.w", bottomW.getText())));
            bottomH.setText(config.get("alignment.bottom.h", saved.getProperty("bottom.h", bottomH.getText())));
            rotation.setText(config.get("alignment.rotation", saved.getProperty("rotation", rotation.getText())));
            joinOffset.setText(config.get("alignment.join.offset", saved.getProperty("join.offset", joinOffset.getText())));
            brightness.setValue(parseInt(config.get("scan.brightness", config.get("alignment.brightness", saved.getProperty("brightness", Integer.toString(brightness.getValue())))), brightness.getValue()));
            dpi.setValue(parseInt(config.get("alignment.dpi", saved.getProperty("dpi", Integer.toString(dpi.getValue()))), dpi.getValue()));
            autoCrop.setSelected(Boolean.parseBoolean(config.get("alignment.autoCrop", saved.getProperty("autoCrop", Boolean.toString(autoCrop.isSelected())))));
            schedulePreviewRefresh();
        }));
    }

    private void restoreDefaultSettingsAsync() {
        pageWidth.setText("215.9");
        pageHeight.setText(Double.toString(FSScannerDefault.TEST_PAGE_HEIGHT_MM));
        quality.setValue(FSScannerDefault.QUALITY);
        scaling.setValue(FSScannerDefault.SCALING);
        brightness.setValue(FSScannerDefault.BRIGHTNESS);
        contrast.setValue(FSScannerDefault.CONTRAST);
        dpi.setValue(FSScannerDefault.DPI);
        autoCrop.setSelected(FSScannerDefault.TEST_AUTO_CROP);
        topX.setText("0"); topY.setText("0"); topW.setText("0"); topH.setText("0");
        bottomX.setText("0"); bottomY.setText("0"); bottomW.setText("0"); bottomH.setText("0");
        rotation.setText("0"); joinOffset.setText("0");
        brightnessValue.setText(Integer.toString(FSScannerDefault.BRIGHTNESS));
        qualityValue.setText(FSScannerDefault.QUALITY + "%");
        scalingValue.setText(FSScannerDefault.SCALING + "%");
        contrastValue.setText(Integer.toString(FSScannerDefault.CONTRAST));
        dpiValue.setText(FSScannerDefault.DPI + " DPI");
        previewStatus.setText("Default test values restored for this preview.");
        schedulePreviewRefresh();
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private void saveSettingsAsync(JButton saveButton) {
        final Properties values = new Properties();
        try {
            double width = 215.9; // FS target width is fixed at 8.5 inches.
            double height = 355.6; // FS is fixed at 8.5 × 14 inches.
            values.setProperty("page.width.mm", Double.toString(width));
            values.setProperty("page.height.mm", Double.toString(height));
            putCrop(values, "top", topX, topY, topW, topH);
            putCrop(values, "bottom", bottomX, bottomY, bottomW, bottomH);
            int angle = Integer.parseInt(rotation.getText().trim());
            int offset = Integer.parseInt(joinOffset.getText().trim());
            if (angle < -180 || angle > 180) throw new IllegalArgumentException("Rotation must be between -180 and 180 degrees.");
            values.setProperty("rotation", Integer.toString(angle));
            values.setProperty("join.offset", Integer.toString(offset));
            values.setProperty("top.seam.y", Integer.toString(topPreview.getSeamY()));
            values.setProperty("bottom.seam.y", Integer.toString(bottomPreview.getSeamY()));
            values.setProperty("brightness", Integer.toString(brightness.getValue()));
            values.setProperty("dpi", Integer.toString(dpi.getValue()));
            values.setProperty("scan.quality", config.get("scan.quality", Integer.toString(FSScannerDefault.QUALITY)));
            values.setProperty("scan.scaling", config.get("scan.scaling", Integer.toString(FSScannerDefault.SCALING)));
            values.setProperty("scan.brightness", Integer.toString(brightness.getValue()));
            values.setProperty("scan.contrast", Integer.toString(contrast.getValue()));
            values.setProperty("autoCrop", Boolean.toString(autoCrop.isSelected()));
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Invalid test value: " + safeMessage(ex),
                    "Invalid test", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saveButton.setEnabled(false);
        previewStatus.setText("Saving test measurements and alignment settings to config.properties...");
        scanData.saveTestAsync(values).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            saveButton.setEnabled(true);
            if (error != null) {
                previewStatus.setText("Test save failed: " + safeMessage(error));
                JOptionPane.showMessageDialog(this, "Could not save test: " + safeMessage(error),
                        "Test", JOptionPane.ERROR_MESSAGE);
            } else {
                try {
                    config.put("scan.quality", values.getProperty("scan.quality"));
                    config.put("scan.scaling", values.getProperty("scan.scaling"));
                    config.put("scan.brightness", values.getProperty("scan.brightness"));
                    config.put("scan.contrast", values.getProperty("scan.contrast"));
                    config.setAlignment("page.width.mm", values.getProperty("page.width.mm"));
                    config.setAlignment("page.height.mm", values.getProperty("page.height.mm"));
                    for (String key : java.util.List.of("top.x", "top.y", "top.w", "top.h", "bottom.x", "bottom.y",
                            "bottom.w", "bottom.h", "rotation", "join.offset", "brightness", "dpi", "autoCrop")) {
                        config.setAlignment(key, values.getProperty(key));
                    }
                    config.save();
                    schedulePreviewRefresh();
                    previewStatus.setText("Test saved to config.properties and scan-data ledger.");
                } catch (IOException configError) {
                    schedulePreviewRefresh();
                    previewStatus.setText("Test saved to fs-data.bin, but config.properties cache failed: " + safeMessage(configError));
                }
            }
        }));
    }

    private static void putCrop(Properties p, String prefix, JTextField x, JTextField y, JTextField w, JTextField h) {
        int ix = Integer.parseInt(x.getText().trim()), iy = Integer.parseInt(y.getText().trim());
        int iw = Integer.parseInt(w.getText().trim()), ih = Integer.parseInt(h.getText().trim());
        if (ix < 0 || iy < 0 || iw < 0 || ih < 0 || ((iw == 0) != (ih == 0)))
            throw new IllegalArgumentException(prefix + " crop requires non-negative X/Y and either both W/H zero or both positive.");
        p.setProperty(prefix + ".x", Integer.toString(ix)); p.setProperty(prefix + ".y", Integer.toString(iy));
        p.setProperty(prefix + ".w", Integer.toString(iw)); p.setProperty(prefix + ".h", Integer.toString(ih));
    }

    private void schedulePreviewRefresh() {
        final BufferedImage topSrc = previewImage;
        final BufferedImage botSrc = previewBottomImage;
        if (topSrc == null) {
            preview.setImage(null, -1);
            return;
        }
        final int tx = parseInt(topX.getText(), 0), ty = parseInt(topY.getText(), 0), tw = parseInt(topW.getText(), 0), th = parseInt(topH.getText(), 0);
        final int bx = parseInt(bottomX.getText(), 0), by = parseInt(bottomY.getText(), 0), bw = parseInt(bottomW.getText(), 0), bh = parseInt(bottomH.getText(), 0);
        final int rot = parseInt(rotation.getText(), 0);
        final int selectedDpi = dpi.getValue();
        final int bright = Math.max(-100, Math.min(100, (brightness.getValue() - 50) * 2));
        final boolean doCrop = autoCrop.isSelected();
        final boolean drawSeam = alignmentMode && showSeamGuide.isSelected();
        final String pType = previewType;
        final String pwText = pageWidth.getText().trim();
        final String phText = pageHeight.getText().trim();

        if (activeRenderTask != null) activeRenderTask.cancel(true);
        activeRenderTask = worker.submit(() -> {
            try {
                BufferedImage top = testedImageOffEdt(topSrc, tx, ty, tw, th, rot, bright, doCrop, false);
                if (Thread.currentThread().isInterrupted()) return;

                if ("FST".equals(pType) && botSrc != null) {
                    BufferedImage bottom = testedImageOffEdt(botSrc, bx, by, bw, bh, rot, bright, doCrop, true);
                    if (Thread.currentThread().isInterrupted()) return;
                    // Use the same fixed-page seam composition as production; do not
                    // append both complete crops, which repeats the shared content.
                    BufferedImage combined = composeFsCrops(top, bottom, selectedDpi,
                            topPreview.getSeamY());
                    FittedPage fitted = new FittedPage(combined, -1);
                    String status = "Foolscap 215.9 × 355.6 mm at " + selectedDpi
                            + " DPI. Preview uses the production stitching geometry.";
                    SwingUtilities.invokeLater(() -> {
                        if (closed.get()) return;
                        preview.setImage(fitted.image, drawSeam ? fitted.seamY : -1);
                        previewStatus.setText(status);
                    });
                } else {
                    FittedPage fitted = fitToPageOffEdt(top, pwText, phText, -1);
                    SwingUtilities.invokeLater(() -> {
                        if (closed.get()) return;
                        preview.setImage(fitted.image, -1);
                        if ("FST".equals(pType)) {
                            previewStatus.setText("Top capture preview. Scan FSB to build the merged page.");
                        }
                    });
                }
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (closed.get()) return;
                    preview.setImage(topSrc, -1);
                    previewStatus.setText("Preview fallback (check crop bounds): " + safeMessage(ex));
                });
            }
        });
    }

    /** Compose the exact selected FST crop followed immediately by the selected FSB crop.
     * The merged preview remains at native crop-pixel dimensions. Final FS page sizing
     * and the user-selected output scaling are applied only during PDF conversion. */
    private static BufferedImage composeFsCrops(BufferedImage top, BufferedImage bottom,
                                                 int dpi, int seamY) {
        try {
            // Same measured-overlap stitching as production (seamY is ignored: automatic seam).
            return FSScannerStitcher.stitchAuto(top, bottom, dpi, 8.5, 14.0, FSScannerDefault.PAGE_SHIFT_LEFT_IN);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot merge the selected FST/FSB crops: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Reconstruct the fixed 8.5 x 14 inch page using one seam in output pixels.
     * FSB must already be rotated to reading orientation. The same stitcher is used
     * by production processing, and neither retained image section is stretched.
     */
    private static BufferedImage fixedFsPage(BufferedImage top, BufferedImage bottom,
                                             int dpi, int seamY) {
        try {
            return FSScannerStitcher.stitch(top, bottom, dpi, 8.5, 14.0, seamY);
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private static double clampFraction(double value) {
        if (!Double.isFinite(value)) return 0.5;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BufferedImage cropRange(BufferedImage source, int startY, int endY) {
        int start = clamp(startY, 0, source.getHeight() - 1);
        int end = clamp(endY, start + 1, source.getHeight());
        BufferedImage crop = new BufferedImage(source.getWidth(), end - start, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = crop.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, crop.getWidth(), crop.getHeight());
            g.drawImage(source, 0, 0, crop.getWidth(), crop.getHeight(), 0, start, source.getWidth(), end, null);
        } finally {
            g.dispose();
        }
        return crop;
    }

    private static BufferedImage scaleToSize(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, targetWidth, targetHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally { g.dispose(); }
        return scaled;
    }

    private static BufferedImage cropFixedHeightEndingAt(BufferedImage source, int wantedHeight, int endY) {
        int copiedHeight = Math.min(wantedHeight, source.getHeight());
        int start = Math.max(0, Math.min(source.getHeight() - copiedHeight, endY - copiedHeight));
        BufferedImage crop = new BufferedImage(source.getWidth(), wantedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = crop.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, crop.getWidth(), crop.getHeight());
            g.drawImage(source.getSubimage(0, start, source.getWidth(), copiedHeight), 0, 0, null);
        } finally { g.dispose(); }
        return crop;
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try { g.setColor(Color.WHITE); g.fillRect(0, 0, copy.getWidth(), copy.getHeight()); g.drawImage(source, 0, 0, null); }
        finally { g.dispose(); }
        return copy;
    }

    private static BufferedImage joinWithOffset(BufferedImage top, BufferedImage bottom, int bottomY) {
        int width = Math.max(top.getWidth(), bottom.getWidth());
        int height = Math.max(top.getHeight(), bottomY + bottom.getHeight());
        BufferedImage result = new BufferedImage(width, Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, result.getWidth(), result.getHeight());
            g.drawImage(top, (width - top.getWidth()) / 2, 0, null);
            g.drawImage(bottom, (width - bottom.getWidth()) / 2, bottomY, null);
        } finally { g.dispose(); }
        return result;
    }

    private static BufferedImage joinWithoutOverlap(BufferedImage top, BufferedImage bottom, int overlapRows, int adjustment) {
        int width = Math.max(top.getWidth(), bottom.getWidth());
        int bottomY = Math.max(0, top.getHeight() + adjustment);
        int tailHeight = Math.max(1, bottom.getHeight() - overlapRows);
        int height = Math.max(top.getHeight(), bottomY + tailHeight);
        BufferedImage result = new BufferedImage(width, Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, result.getWidth(), result.getHeight());
            g.drawImage(top, (width - top.getWidth()) / 2, 0, null);
            g.drawImage(bottom, (width - bottom.getWidth()) / 2, bottomY,
                    (width + bottom.getWidth()) / 2, bottomY + tailHeight,
                    0, overlapRows, bottom.getWidth(), bottom.getHeight(), null);
        } finally { g.dispose(); }
        return result;
    }

    /** Returns the best confident vertical overlap in pixels, or zero if uncertain. */
    private static int detectVerticalOverlap(BufferedImage top, BufferedImage bottom) {
        int max = Math.min(Math.min(top.getHeight(), bottom.getHeight()) / FSScannerDefault.STITCH_MAX_OVERLAP_DIVISOR,
                Math.min(top.getHeight(), bottom.getHeight()) - 2);
        int min = Math.min(FSScannerDefault.STITCH_MIN_OVERLAP_ROWS, max);
        if (max < 8 || min < 4) return 0;
        int bestRows = 0;
        double bestError = Double.POSITIVE_INFINITY;
        double secondError = Double.POSITIVE_INFINITY;
        int xStep = Math.max(1, Math.min(top.getWidth(), bottom.getWidth()) / 48);
        for (int overlap = min; overlap <= max; overlap += Math.max(1, (max - min) / 80)) {
            double error = 0;
            long samples = 0;
            int rowStep = Math.max(1, overlap / 48);
            int width = Math.min(top.getWidth(), bottom.getWidth());
            for (int dy = 0; dy < overlap; dy += rowStep) {
                int ty = top.getHeight() - overlap + dy;
                int by = dy;
                for (int x = 0; x < width; x += xStep) {
                    int a = gray(top.getRGB(x, ty));
                    int b = gray(bottom.getRGB(x, by));
                    error += Math.abs(a - b);
                    samples++;
                }
            }
            if (samples == 0) continue;
            error /= samples;
            if (error < bestError) { secondError = bestError; bestError = error; bestRows = overlap; }
            else if (error < secondError) secondError = error;
        }
        double confidence = 1.0 - bestError / 255.0;
        // Reject flat/ambiguous comparisons; only apply automatic crop on clear evidence.
        if (confidence < FSScannerDefault.STITCH_MIN_CONFIDENCE || bestRows == 0) return 0;
        if (Double.isFinite(secondError) && secondError - bestError < 1.5) return 0;
        return bestRows;
    }

    private static int gray(int rgb) {
        return (int) (0.299 * ((rgb >>> 16) & 255) + 0.587 * ((rgb >>> 8) & 255) + 0.114 * (rgb & 255));
    }

    private record FittedPage(BufferedImage image, int seamY) { }

    private static FittedPage fitToPageOffEdt(BufferedImage source, String widthText, String heightText, int sourceSeamY) {
        double widthMm, heightMm;
        try {
            widthMm = Double.parseDouble(widthText);
            heightMm = Double.parseDouble(heightText);
            if (!Double.isFinite(widthMm) || !Double.isFinite(heightMm) || widthMm <= 0 || heightMm <= 0)
                return new FittedPage(source, sourceSeamY);
        } catch (RuntimeException ex) {
            return new FittedPage(source, sourceSeamY);
        }
        double ratio = widthMm / heightMm;
        int pageW = ratio >= 1.0 ? 900 : Math.max(1, (int) Math.round(900 * ratio));
        int pageH = Math.max(1, (int) Math.round(pageW / ratio));
        double scale = Math.min((pageW - 24.0) / source.getWidth(), (pageH - 24.0) / source.getHeight());
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int offsetY = (pageH - h) / 2;

        BufferedImage page = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, pageW, pageH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, (pageW - w) / 2, offsetY, w, h, null);
        } finally {
            g.dispose();
        }
        int mappedSeamY = sourceSeamY >= 0 ? offsetY + (int) Math.round(sourceSeamY * scale) : -1;
        return new FittedPage(page, mappedSeamY);
    }

    private static BufferedImage testedImageOffEdt(
            BufferedImage original, int x, int y, int w, int h,
            int rotationDeg, int brightnessAmount, boolean doCrop, boolean reverseCapture) {
        BufferedImage src = original;
        // FSB crop guides are drawn on the 180-degree-rotated preview. Rotate the
        // full source first so the same Y coordinates select the same visible rows.
        if (reverseCapture) src = rotateImage(src, 180);
        if (doCrop && w > 0 && h > 0) {
            if (x < 0 || y < 0 || x + w > src.getWidth() || y + h > src.getHeight())
                throw new IllegalArgumentException("Crop rectangle exceeds source image bounds (" + src.getWidth() + "x" + src.getHeight() + ").");
            src = src.getSubimage(x, y, w, h);
        }
        int angle = rotationDeg;
        if (angle != 0) {
            double radians = Math.toRadians(angle);
            int ow = Math.max(1, (int) Math.ceil(Math.abs(src.getWidth() * Math.cos(radians)) + Math.abs(src.getHeight() * Math.sin(radians)) - 1e-9));
            int oh = Math.max(1, (int) Math.ceil(Math.abs(src.getHeight() * Math.cos(radians)) + Math.abs(src.getWidth() * Math.sin(radians)) - 1e-9));
            BufferedImage rotated = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rotated.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, ow, oh);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.translate((ow - src.getWidth()) / 2.0, (oh - src.getHeight()) / 2.0);
                g.rotate(radians, src.getWidth() / 2.0, src.getHeight() / 2.0);
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            src = rotated;
        }
        if (brightnessAmount != 0) {
            BufferedImage rgbCopy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbCopy.createGraphics();
            try {
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            float offset = Math.round(brightnessAmount * 2.55f);
            RescaleOp op = new RescaleOp(
                    new float[]{1.0f, 1.0f, 1.0f},
                    new float[]{offset, offset, offset},
                    null
            );
            src = op.filter(rgbCopy, null);
        }
        return src;
    }

    private static BufferedImage rotatePreview180(BufferedImage source) {
        return source == null ? null : rotateImage(source, 180);
    }

    private static BufferedImage rotateImage(BufferedImage source, int angleDegrees) {
        if (source == null) return null;
        int angle = Math.floorMod(angleDegrees, 360);
        if (angle == 0) return source;
        double radians = Math.toRadians(angle);
        int ow = Math.max(1, (int)Math.ceil(Math.abs(source.getWidth() * Math.cos(radians))
                + Math.abs(source.getHeight() * Math.sin(radians)) - 1e-9));
        int oh = Math.max(1, (int)Math.ceil(Math.abs(source.getHeight() * Math.cos(radians))
                + Math.abs(source.getWidth() * Math.sin(radians)) - 1e-9));
        BufferedImage rotated = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, ow, oh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.translate((ow - source.getWidth()) / 2.0, (oh - source.getHeight()) / 2.0);
            g.rotate(radians, source.getWidth() / 2.0, source.getHeight() / 2.0);
            g.drawImage(source, 0, 0, null);
        } finally { g.dispose(); }
        return rotated;
    }


		private java.util.List<Image> loadWindowIcons() {
			java.util.List<Image> icons = new java.util.ArrayList<>();

			Path png = FSScannerPaths.applicationDirectory()
					.resolve("res")
					.resolve("fs-scanner.png");

			if (Files.isRegularFile(png)) {
				try {
					BufferedImage image = ImageIO.read(png.toFile());

					if (image != null) {
						icons.add(image);
					}
				} catch (IOException ignored) {
					// Continue without a custom window icon if loading fails.
				}
			}

			return icons;
		}


    private String safeMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override
    public void dispose() {
        closed.set(true);
        if (activeLoadTask != null) activeLoadTask.cancel(true);
        if (activeRenderTask != null) activeRenderTask.cancel(true);
        worker.shutdownNow();
        super.dispose();
    }

}