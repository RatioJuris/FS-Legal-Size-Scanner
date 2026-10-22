import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Reusable image viewer for test calibration and production previews.
 * Upgraded with auto-theme detection (Dark/Light), click-zoom controls,
 * contextual cursors, smoothed schematic rendering, and a live precision HUD readout on grab/move.
 * All guide coordinates remain strictly in source-image pixels regardless of display zoom.
 */
public final class FSScannerViewer extends JPanel {
    private static final Stroke SEAM_STROKE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke ACTIVE_SEAM_STROKE = new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke DASHED_STROKE = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4, 4}, 0);
    private static final Stroke BORDER_STROKE = new BasicStroke(1.0f);

    // Theme Palette Definitions
    public record ThemePalette(
        Color background,
        Color rulerBg,
        Color rulerText,
        Color rulerGrid,
        Color canvasMatte,
        Color canvasBorder,
        Color canvasShadow,
        Color emptyTextColor,
        Color neonGuide,
        Color neonSeam,
        Color badgeBg,
        Color badgeText
    ) {
        public static ThemePalette dark() {
            return new ThemePalette(
                new Color(15, 18, 22),          // BG
                new Color(22, 27, 34),          // Ruler BG
                new Color(139, 148, 158),       // Ruler Text
                new Color(48, 54, 61),          // Ruler Grid & Ticks
                new Color(24, 28, 34),          // Canvas Matte
                new Color(56, 63, 72),          // Canvas Border
                new Color(0, 0, 0, 70),         // Canvas Shadow
                new Color(110, 118, 129),       // Empty Text
                new Color(0, 229, 154),         // Guide Highlight
                new Color(255, 69, 105),        // Seam Highlight
                new Color(13, 17, 23, 235),     // HUD Badge BG
                new Color(240, 246, 252)        // HUD Badge Text
            );
        }

        public static ThemePalette light() {
            return new ThemePalette(
                new Color(246, 248, 250),       // BG
                new Color(238, 242, 246),       // Ruler BG
                new Color(87, 96, 106),         // Ruler Text
                new Color(208, 215, 222),       // Ruler Grid & Ticks
                new Color(255, 255, 255),       // Canvas Matte
                new Color(209, 217, 224),       // Canvas Border
                new Color(0, 0, 0, 20),         // Canvas Shadow
                new Color(140, 149, 159),       // Empty Text
                new Color(9, 156, 103),         // Guide Highlight
                new Color(225, 29, 72),         // Seam Highlight
                new Color(255, 255, 255, 240),  // HUD Badge BG
                new Color(31, 35, 40)           // HUD Badge Text
            );
        }
    }

    private ThemePalette palette;
    private BufferedImage image;
    private int seamY = -1;
    private int guideStartY = -1;
    private int guideEndY = -1;

    // Interaction states: -1 = none, 0 = start guide, 1 = end guide
    private int draggingGuide = -1;
    private int hoveredGuide = -1;
    private boolean isDraggingSeam = false;
    private boolean isHoveringSeam = false;

    private java.util.function.IntConsumer adjustmentHandler = delta -> { };
    private java.util.function.BiConsumer<Integer, Integer> guideHandler = (guide, y) -> { };
    private final boolean cropGuides;
    private int dragY = -1;
    private double displayScale = 1.0;
    private double zoom = 1.0;
    private final boolean clickZoom;
    private int rulerDpi = 300;
    private final double canvasWidthMm;
    private final double canvasHeightMm;

    private static final int RULER_LEFT = 40;
    private static final int RULER_TOP = 28;
    private static final int HIT_TOLERANCE = 12;

    public FSScannerViewer(boolean clickZoom, boolean cropGuides, double canvasWidthMm, double canvasHeightMm) {
        this.clickZoom = clickZoom;
        this.cropGuides = cropGuides;
        this.canvasWidthMm = canvasWidthMm;
        this.canvasHeightMm = canvasHeightMm;

        refreshTheme();
        setPreferredSize(new Dimension(640, 540));
        setToolTipText("Drag crop guides to adjust image region. Left-click to zoom in, Right-click to zoom out. Ctrl+Wheel zooms.");

        initMouseHandlers();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        refreshTheme();
    }

    public void refreshTheme() {
        this.palette = detectSystemDarkMode() ? ThemePalette.dark() : ThemePalette.light();
        setBackground(this.palette.background());
        repaint();
    }

    private static boolean detectSystemDarkMode() {
        Color panelBg = UIManager.getColor("Panel.background");
        if (panelBg != null) {
            double luminance = (0.299 * panelBg.getRed() + 0.587 * panelBg.getGreen() + 0.114 * panelBg.getBlue()) / 255.0;
            if (luminance < 0.5) return true;
        }
        Object darkProp = Toolkit.getDefaultToolkit().getDesktopProperty("win.darkMode");
        if (darkProp instanceof Boolean isDark && isDark) {
            return true;
        }
        return false;
    }

    private void initMouseHandlers() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (image == null) return;

                Rectangle bounds = getImageDrawnBounds();
                int lineY = bounds.y + (int) Math.round(seamY * displayScale);
                int guide1Y = bounds.y + (int) Math.round(guideStartY * displayScale);
                int guide2Y = bounds.y + (int) Math.round(guideEndY * displayScale);

                boolean inGuideX = e.getX() >= bounds.x - 8 && e.getX() <= bounds.x + bounds.width + 8;
                boolean onSeam = !cropGuides && seamY >= 0 && Math.abs(e.getY() - lineY) <= HIT_TOLERANCE && inGuideX;
                boolean onGuide1 = cropGuides && guideStartY >= 0 && Math.abs(e.getY() - guide1Y) <= HIT_TOLERANCE && inGuideX;
                boolean onGuide2 = cropGuides && guideEndY >= 0 && Math.abs(e.getY() - guide2Y) <= HIT_TOLERANCE && inGuideX;

                // Right-click zoom out
                if (SwingUtilities.isRightMouseButton(e)) {
                    zoom = Math.max(0.25, zoom / 1.25);
                    updateZoomPreferredSize();
                    repaint();
                    return;
                }

                // Left-click guide grab or zoom in
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (onGuide1 || onGuide2) {
                        draggingGuide = onGuide1 && onGuide2
                                ? (Math.abs(e.getY() - guide1Y) <= Math.abs(e.getY() - guide2Y) ? 0 : 1)
                                : (onGuide1 ? 0 : 1);
                        dragY = e.getY();
                        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                        repaint(); // Immediately trigger precision readout on grab
                    } else if (onSeam) {
                        draggingGuide = -1;
                        isDraggingSeam = true;
                        dragY = e.getY();
                        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                        repaint(); // Immediately trigger precision readout on grab
                    } else if (FSScannerViewer.this.clickZoom) {
                        zoom = Math.min(6.0, zoom * 1.25);
                        updateZoomPreferredSize();
                        repaint();
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragY = -1;
                draggingGuide = -1;
                isDraggingSeam = false;
                updateContextualCursor(e.getX(), e.getY());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoveredGuide != -1 || isHoveringSeam) {
                    hoveredGuide = -1;
                    isHoveringSeam = false;
                    repaint();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragY < 0 || image == null || displayScale <= 0) return;
                int screenDelta = e.getY() - dragY;
                if (Math.abs(screenDelta) >= 1) {
                    int imageDelta = (int) Math.round(screenDelta / displayScale);
                    if (imageDelta != 0) {
                        if (cropGuides && draggingGuide >= 0) {
                            if (draggingGuide == 0) {
                                guideStartY = Math.max(0, Math.min(guideEndY - 1, guideStartY + imageDelta));
                            } else {
                                guideEndY = Math.max(guideStartY + 1, Math.min(image.getHeight(), guideEndY + imageDelta));
                            }
                            guideHandler.accept(draggingGuide, draggingGuide == 0 ? guideStartY : guideEndY);
                        } else if (isDraggingSeam || !cropGuides) {
                            seamY = Math.max(0, Math.min(image.getHeight(), seamY + imageDelta));
                            adjustmentHandler.accept(imageDelta);
                        }
                        dragY = e.getY();
                        repaint();
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateContextualCursor(e.getX(), e.getY());
            }
        });

        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                zoom = Math.max(0.25, Math.min(6.0, zoom * (e.getWheelRotation() < 0 ? 1.15 : 1.0 / 1.15)));
                updateZoomPreferredSize();
                repaint();
            }
        });
    }

    private void updateContextualCursor(int mouseX, int mouseY) {
        if (image == null) {
            setCursor(Cursor.getDefaultCursor());
            return;
        }

        Rectangle bounds = getImageDrawnBounds();
        int lineY = bounds.y + (int) Math.round(seamY * displayScale);
        int guide1Y = bounds.y + (int) Math.round(guideStartY * displayScale);
        int guide2Y = bounds.y + (int) Math.round(guideEndY * displayScale);

        boolean inGuideX = mouseX >= bounds.x - 8 && mouseX <= bounds.x + bounds.width + 8;
        boolean onGuide1 = cropGuides && guideStartY >= 0 && Math.abs(mouseY - guide1Y) <= HIT_TOLERANCE && inGuideX;
        boolean onGuide2 = cropGuides && guideEndY >= 0 && Math.abs(mouseY - guide2Y) <= HIT_TOLERANCE && inGuideX;
        boolean onSeam = !cropGuides && seamY >= 0 && Math.abs(mouseY - lineY) <= HIT_TOLERANCE && inGuideX;

        int prevHoverGuide = hoveredGuide;
        boolean prevHoverSeam = isHoveringSeam;

        if (onGuide1 || onGuide2) {
            hoveredGuide = onGuide1 && onGuide2
                    ? (Math.abs(mouseY - guide1Y) <= Math.abs(mouseY - guide2Y) ? 0 : 1)
                    : (onGuide1 ? 0 : 1);
            isHoveringSeam = false;
            setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        } else if (onSeam) {
            hoveredGuide = -1;
            isHoveringSeam = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        } else {
            hoveredGuide = -1;
            isHoveringSeam = false;
            if (clickZoom && bounds.contains(mouseX, mouseY)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
                setCursor(Cursor.getDefaultCursor());
            }
        }

        if (prevHoverGuide != hoveredGuide || prevHoverSeam != isHoveringSeam) {
            repaint();
        }
    }

    private Rectangle getImageDrawnBounds() {
        int sourceW = canvasPixelWidth();
        int sourceH = canvasPixelHeight();
        int w = Math.max(1, (int) Math.round(sourceW * displayScale));
        int h = Math.max(1, (int) Math.round(sourceH * displayScale));
        int x = RULER_LEFT + Math.max(0, (getWidth() - RULER_LEFT - w) / 2);
        int y = RULER_TOP + Math.max(0, (getHeight() - RULER_TOP - h) / 2);
        return new Rectangle(x, y, w, h);
    }

    // --- Public API (100% Backward Compatible) ---

    public void setRulerDpi(int dpi) {
        rulerDpi = Math.max(50, Math.min(1200, dpi));
        repaint();
    }

    public void zoomBy(double factor) {
        zoom = Math.max(0.25, Math.min(6.0, zoom * factor));
        updateZoomPreferredSize();
        repaint();
    }

    public void setZoom(double value) {
        zoom = Math.max(0.25, Math.min(6.0, value));
        updateZoomPreferredSize();
        repaint();
    }

    public void setAdjustmentHandler(java.util.function.IntConsumer handler) {
        adjustmentHandler = handler == null ? delta -> { } : handler;
    }

    public void setGuideHandler(java.util.function.BiConsumer<Integer, Integer> handler) {
        guideHandler = handler == null ? (guide, y) -> { } : handler;
    }

    public void setCropGuides(BufferedImage image, int startY, int endY) {
        this.image = image;
        if (image == null) {
            guideStartY = guideEndY = -1;
        } else {
            guideStartY = Math.max(0, Math.min(image.getHeight() - 1, startY));
            guideEndY = Math.max(guideStartY + 1, Math.min(image.getHeight(), endY));
        }
        revalidate();
        repaint();
    }

    public int getGuideStartY() { return guideStartY; }
    public int getGuideEndY() { return guideEndY; }

    public void setSeamY(int y) {
        seamY = image == null || y < 0 ? -1 : Math.max(0, Math.min(image.getHeight(), y));
        repaint();
    }

    public int getImageHeight() { return image == null ? 0 : image.getHeight(); }
    public int getSeamY() { return seamY; }

    public void setImage(BufferedImage image, int seamY) {
        this.image = image;
        this.seamY = seamY;
        revalidate();
        repaint();
    }

    // --- Internal Sizing & Coordinate Math ---

    private int canvasPixelWidth() {
        return image != null ? image.getWidth() : Math.max(1, (int) Math.round(canvasWidthMm * rulerDpi / 25.4));
    }

    private int canvasPixelHeight() {
        return image != null ? image.getHeight() : Math.max(1, (int) Math.round(canvasHeightMm * rulerDpi / 25.4));
    }

    private double pixelToMmY(int pixelY) {
        if (image != null && image.getHeight() > 0 && canvasHeightMm > 0) {
            return (pixelY * canvasHeightMm) / image.getHeight();
        }
        return (pixelY * 25.4) / Math.max(1, rulerDpi);
    }

    private double baseFitScale() {
        int availableW = Math.max(180, getWidth() - RULER_LEFT);
        int availableH = Math.max(220, getHeight() - RULER_TOP);
        Container parent = getParent();
        if (parent instanceof JViewport viewport) {
            Dimension extent = viewport.getExtentSize();
            if (extent.width > 30 && extent.height > 30) {
                availableW = Math.max(100, extent.width - RULER_LEFT);
                availableH = Math.max(100, extent.height - RULER_TOP);
            }
        }
        double fit = Math.min((availableW - 24.0) / canvasPixelWidth(), (availableH - 24.0) / canvasPixelHeight());
        return Math.max(0.01, Math.min(1.0, fit));
    }

    private void updateZoomPreferredSize() {
        double scale = baseFitScale() * zoom;
        setPreferredSize(new Dimension(
                Math.max(220, (int) Math.ceil(canvasPixelWidth() * scale + RULER_LEFT + 24)),
                Math.max(240, (int) Math.ceil(canvasPixelHeight() * scale + RULER_TOP + 24))
        ));
        revalidate();
    }

    private static Font getMonospaceFont(int style, int size) {
        Font font = new Font("JetBrains Mono", style, size);
        if ("Dialog".equalsIgnoreCase(font.getFamily())) {
            font = new Font(Font.MONOSPACED, style, size);
        }
        return font;
    }

    // --- Painting & Precision HUD Readouts ---

    private void paintSmoothGuide(Graphics2D g, int x, int y, int width, Color color, boolean active) {
        g.setColor(color);
        g.setStroke(active ? ACTIVE_SEAM_STROKE : SEAM_STROKE);
        g.drawLine(x, y, x + width, y);

        // Central pill handle expands slightly when grabbed/hovered
        int handleW = active ? 24 : 18;
        int handleH = active ? 8 : 6;
        int hx = x + (width - handleW) / 2;
        int hy = y - handleH / 2;

        g.fillRoundRect(hx, hy, handleW, handleH, 4, 4);
        g.setColor(palette.background());
        g.setStroke(BORDER_STROKE);
        g.drawRoundRect(hx, hy, handleW, handleH, 4, 4);
    }

    /**
     * Renders a floating precision readout pill on the active/grabbed line
     * showing exact source pixel Y, physical millimeter value, and crop span.
     */
    private void paintPrecisionReadout(Graphics2D g, Rectangle canvasBounds, int screenY, String label, int pixelY, Color accentColor) {
        double mmY = pixelToMmY(pixelY);
        String text;

        if (cropGuides && guideStartY >= 0 && guideEndY >= 0) {
            int spanPx = Math.max(0, guideEndY - guideStartY);
            double spanMm = pixelToMmY(spanPx);
            text = String.format(Locale.US, "%s: %d px (%.2f mm)  |  \u0394: %d px (%.1f mm)",
                    label, pixelY, mmY, spanPx, spanMm);
        } else {
            text = String.format(Locale.US, "%s: %d px  \u2022  %.2f mm", label, pixelY, mmY);
        }

        g.setFont(getMonospaceFont(Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int padX = 10;
        int boxW = textW + padX * 2;
        int boxH = 22;

        // Center horizontally on the canvas, clamped inside visible viewport
        int boxX = canvasBounds.x + (canvasBounds.width - boxW) / 2;
        boxX = Math.max(RULER_LEFT + 6, Math.min(getWidth() - boxW - 6, boxX));

        // Position directly above the grab handle; flip below if too close to top ruler
        int boxY = screenY - boxH - 8;
        if (boxY < RULER_TOP + 4) {
            boxY = screenY + 8;
        }

        // Drop shadow
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(boxX + 1, boxY + 2, boxW, boxH, 6, 6);

        // High-contrast HUD pill body
        g.setColor(palette.badgeBg());
        g.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);

        // Colored accent border matching the active guide
        g.setColor(accentColor);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(boxX, boxY, boxW, boxH, 6, 6);

        // Crisp monospace readout text
        g.setColor(palette.badgeText());
        int textY = boxY + ((boxH - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, boxX + padX, textY);

        // Also highlight the exact Y position on the left vertical ruler
        int rulerTagH = 14;
        int rulerTagY = Math.max(RULER_TOP, Math.min(getHeight() - rulerTagH, screenY - rulerTagH / 2));
        g.setColor(accentColor);
        g.fillRect(RULER_LEFT - 4, rulerTagY, 4, rulerTagH);
    }

    private void paintRulers(Graphics2D g, int x, int y, int w, int h) {
        g.setFont(getMonospaceFont(Font.PLAIN, 9));
        FontMetrics fm = g.getFontMetrics();

        // Fill structural ruler containers
        g.setColor(palette.rulerBg());
        g.fillRect(RULER_LEFT, 0, Math.max(0, getWidth() - RULER_LEFT), RULER_TOP);
        g.fillRect(0, RULER_TOP, RULER_LEFT, Math.max(0, getHeight() - RULER_TOP));
        g.fillRect(0, 0, RULER_LEFT, RULER_TOP);

        // Dynamic Fine Component Borders
        g.setColor(palette.rulerGrid());
        g.drawLine(RULER_LEFT - 1, 0, RULER_LEFT - 1, getHeight());
        g.drawLine(0, RULER_TOP - 1, getWidth(), RULER_TOP - 1);

        double pxPerMmX = w / Math.max(1.0, canvasWidthMm);
        double pxPerMmY = h / Math.max(1.0, canvasHeightMm);
        if (pxPerMmX <= 0 || pxPerMmY <= 0) return;

        int labelStepX = readableLabelStep(pxPerMmX, fm.stringWidth("216") + 12);
        int labelStepY = readableLabelStep(pxPerMmY, fm.getHeight() + 6);
        int maxX = (int) Math.ceil(canvasWidthMm);
        int maxY = (int) Math.ceil(canvasHeightMm);

        // Horizontal Ruler Elements
        for (int mm = 0; mm <= maxX; mm++) {
            int tx = x + (int) Math.round(mm * pxPerMmX);
            if (tx > x + w) break;
            if (tx < RULER_LEFT) continue;
            int tick = mm % 10 == 0 ? 8 : mm % 5 == 0 ? 5 : 2;
            g.setColor(palette.rulerGrid());
            g.drawLine(tx, RULER_TOP - tick, tx, RULER_TOP - 1);
            if (mm % labelStepX == 0) {
                g.setColor(palette.rulerText());
                String label = Integer.toString(mm);
                int labelX = tx - fm.stringWidth(label) / 2;
                if (labelX >= RULER_LEFT && labelX + fm.stringWidth(label) <= getWidth()) {
                    g.drawString(label, labelX, 10 + fm.getAscent() / 2);
                }
            }
        }

        // Vertical Ruler Elements
        for (int mm = 0; mm <= maxY; mm++) {
            int ty = y + (int) Math.round(mm * pxPerMmY);
            if (ty > y + h) break;
            if (ty < RULER_TOP) continue;
            int tick = mm % 10 == 0 ? 8 : mm % 5 == 0 ? 5 : 2;
            g.setColor(palette.rulerGrid());
            g.drawLine(RULER_LEFT - tick, ty, RULER_LEFT - 1, ty);
            if (mm % labelStepY == 0) {
                g.setColor(palette.rulerText());
                String label = Integer.toString(mm);
                int labelY = ty + fm.getAscent() / 2;
                if (labelY - fm.getAscent() >= RULER_TOP && labelY <= getHeight()) {
                    g.drawString(label, RULER_LEFT - fm.stringWidth(label) - 12, labelY);
                }
            }
        }

        // Corner Metric Tag Unit
        g.setColor(palette.rulerText());
        g.setFont(getMonospaceFont(Font.BOLD, 9));
        g.drawString("MM", 8, 12 + fm.getAscent() / 2);
    }

    private static int readableLabelStep(double pxPerMm, int requiredPixels) {
        int raw = Math.max(1, (int) Math.ceil(requiredPixels / Math.max(0.01, pxPerMm)));
        if (raw <= 1) return 1;
        int magnitude = 1;
        while (magnitude * 10 < raw) magnitude *= 10;
        for (int multiplier : new int[]{1, 2, 5, 10}) {
            int candidate = multiplier * magnitude;
            if (candidate >= raw) return candidate;
        }
        return magnitude * 10;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (palette == null) refreshTheme();

        Graphics2D g = (Graphics2D) g0.create();

        // High fidelity anti-aliasing & sub-pixel stroke rendering
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double fitScale = baseFitScale();
        displayScale = Math.max(0.01, fitScale * zoom);

        Rectangle b = getImageDrawnBounds();

        // Soft elevation shadow
        g.setColor(palette.canvasShadow());
        g.fill(new RoundRectangle2D.Float(b.x + 2, b.y + 3, b.width + 2, b.height + 2, 6, 6));

        // Render Canvas Matte Background
        g.setColor(palette.canvasMatte());
        g.fillRect(b.x, b.y, b.width, b.height);

        // Slim, elegant layout frame bounds
        g.setColor(palette.canvasBorder());
        g.setStroke(BORDER_STROKE);
        g.drawRect(b.x - 1, b.y - 1, b.width + 1, b.height + 1);

        if (image != null) {
            g.drawImage(image, b.x, b.y, b.width, b.height, null);

            // 1. Draw Seam Line & Check Active Readout
            if (seamY >= 0 && seamY <= image.getHeight()) {
                int drawnSeamY = b.y + (int) Math.round(seamY * displayScale);
                boolean seamActive = isDraggingSeam || isHoveringSeam;
                paintSmoothGuide(g, b.x, drawnSeamY, b.width, palette.neonSeam(), seamActive);
            }

            // 2. Draw Crop Guides
            if (cropGuides && guideStartY >= 0 && guideEndY >= 0) {
                int g1 = b.y + (int) Math.round(guideStartY * displayScale);
                int g2 = b.y + (int) Math.round(guideEndY * displayScale);
                boolean g1Active = (draggingGuide == 0) || (draggingGuide == -1 && hoveredGuide == 0);
                boolean g2Active = (draggingGuide == 1) || (draggingGuide == -1 && hoveredGuide == 1);

                paintSmoothGuide(g, b.x, g1, b.width, palette.neonGuide(), g1Active);
                paintSmoothGuide(g, b.x, g2, b.width, palette.neonGuide(), g2Active);
            }
        } else {
            // Decorative structural crosshairs for raw schematic layout design logic
            g.setStroke(DASHED_STROKE);
            g.setColor(palette.rulerGrid());
            g.drawLine(b.x, b.y + b.height / 2, b.x + b.width, b.y + b.height / 2);
            g.drawLine(b.x + b.width / 2, b.y, b.x + b.width / 2, b.y + b.height);

            // Empty State Graphic placeholder
            g.setColor(palette.emptyTextColor());
            g.setFont(getMonospaceFont(Font.PLAIN, 11));
            String text = "[ NO PRODUCTION IMAGE LOADING ]";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, b.x + Math.max(4, (b.width - fm.stringWidth(text)) / 2), b.y + b.height / 2 - 6);
        }

        // Draw structural ruler indicators on top of layout frames
        paintRulers(g, b.x, b.y, b.width, b.height);

        // Render Precision HUD Badge on top of everything when grabbing, dragging, or hovering a line
        if (image != null) {
            int activeGuide = draggingGuide != -1 ? draggingGuide : hoveredGuide;
            if (cropGuides && activeGuide == 0 && guideStartY >= 0) {
                int g1 = b.y + (int) Math.round(guideStartY * displayScale);
                paintPrecisionReadout(g, b, g1, "START Y", guideStartY, palette.neonGuide());
            } else if (cropGuides && activeGuide == 1 && guideEndY >= 0) {
                int g2 = b.y + (int) Math.round(guideEndY * displayScale);
                paintPrecisionReadout(g, b, g2, "END Y", guideEndY, palette.neonGuide());
            } else if (!cropGuides && (isDraggingSeam || isHoveringSeam) && seamY >= 0) {
                int drawnSeamY = b.y + (int) Math.round(seamY * displayScale);
                paintPrecisionReadout(g, b, drawnSeamY, "SEAM Y", seamY, palette.neonSeam());
            }
        }

        g.dispose();
    }
}