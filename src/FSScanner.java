
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;

/**
 * FS Scanner splash screen.
 *
 * Inspired by the clean, product-focused loading screens of professional
 * graphics software. The application icon is retained as the window icon
 * but is not displayed inside the splash screen.
 */
public final class FSScanner extends JFrame {

    private static final String APP_NAME = "FS Scanner";
    private static final String EDITION = "365 Premiere";
    private static final String COPYRIGHT =
            "Copyright \u00A9 " + Year.now().getValue()
                    + " RatioJuris. All Rights Reserved";

    private static final String PNG_ICON_PATH = "res/fs-scanner.png";

    private static final int WIDTH = 620;
    private static final int HEIGHT = 340;

    private static final Color BACKGROUND = new Color(38, 38, 40);
    private static final Color PANEL_BACKGROUND = new Color(48, 48, 51);
    private static final Color TEXT_PRIMARY = new Color(245, 245, 247);
    private static final Color TEXT_SECONDARY = new Color(170, 170, 175);

    private static final Color[] PROGRESS_COLORS = {
            new Color(0, 190, 230),
            new Color(0, 205, 170),
            new Color(145, 205, 65),
            new Color(255, 190, 55),
            new Color(245, 105, 75),
            new Color(190, 95, 220)
    };

    private static final Font TITLE_FONT =
            new Font("Segoe UI", Font.BOLD, 35);

    private static final Font EDITION_FONT =
            new Font("Segoe UI", Font.PLAIN, 13);

    private static final Font STATUS_FONT =
            new Font("Segoe UI", Font.PLAIN, 12);

    private static final Font COPYRIGHT_FONT =
            new Font("Segoe UI", Font.PLAIN, 10);

    private final JLabel status =
            new JLabel("Preparing FS Scanner...", SwingConstants.CENTER);

    private final SplashPanel splashPanel = new SplashPanel();

    private final long startedAt = System.nanoTime();

    private final Timer timer;

    private boolean launched;

    public FSScanner() {
        super(APP_NAME);

        loadApplicationIcon();

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setUndecorated(true);
        setBackground(BACKGROUND);
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);

        setContentPane(splashPanel);

        status.setForeground(TEXT_SECONDARY);
        status.setFont(STATUS_FONT);
        status.setOpaque(false);
        splashPanel.add(status);

        timer = new Timer(35, event -> updateSplash());
        timer.start();
    }

    /**
     * Loads the application icon without displaying it in the splash content.
     */
    private void loadApplicationIcon() {
        Path iconPath = FSScannerPaths.applicationDirectory()
                .resolve(PNG_ICON_PATH)
                .normalize();

        if (!Files.isRegularFile(iconPath)) {
            return;
        }

        ImageIcon icon = new ImageIcon(iconPath.toString());

        if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
            setIconImage(icon.getImage());
        }
    }

    private void updateSplash() {
        double elapsed =
                (System.nanoTime() - startedAt) / 1_000_000_000.0;

        int value = Math.min(100, (int) (elapsed / 2.8 * 100));

        splashPanel.setProgress(value);

        if (elapsed < 0.8) {
            status.setText("Preparing FS Scanner...");
        } else if (elapsed < 1.8) {
            status.setText("Preparing workspace...");
        } else {
            status.setText("Starting FS Scanner...");
        }

        if (value >= 100 && !launched) {
            launched = true;
            timer.stop();
            dispose();

            SwingUtilities.invokeLater(() -> {
                FSScannerUI ui = new FSScannerUI();
                ui.setVisible(true);
            });
        }
    }

    private final class SplashPanel extends JPanel {

        private static final int BAR_WIDTH = 360;
        private static final int BAR_HEIGHT = 5;

        private int progress;

        private final Timer animation =
                new Timer(33, event -> repaint());

        SplashPanel() {
            setLayout(null);
            setOpaque(true);
            setBackground(BACKGROUND);

            MouseAdapter dragHandler = new MouseAdapter() {
                private Point dragOrigin;

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    dragOrigin = event.getPoint();
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent event) {
                    dragOrigin = null;
                }

                @Override
                public void mouseDragged(java.awt.event.MouseEvent event) {
                    if (dragOrigin != null) {
                        Point screenPoint = event.getLocationOnScreen();

                        FSScanner.this.setLocation(
                                screenPoint.x - dragOrigin.x,
                                screenPoint.y - dragOrigin.y
                        );
                    }
                }
            };

            addMouseListener(dragHandler);
            addMouseMotionListener(dragHandler);

            animation.start();
        }

        void setProgress(int value) {
            progress = Math.max(0, Math.min(100, value));
            repaint();
        }

        @Override
        public void removeNotify() {
            animation.stop();
            super.removeNotify();
        }

        @Override
        public void doLayout() {
            int centerX = getWidth() / 2;

            status.setBounds(
                    centerX - 180,
                    222,
                    360,
                    24
            );
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            Graphics2D g = (Graphics2D) graphics.create();

            try {
                applyRenderingHints(g);

                int width = getWidth();
                int height = getHeight();
                double time =
                        (System.nanoTime() - startedAt) / 1_000_000_000.0;

                // Main charcoal surface.
                g.setColor(BACKGROUND);
                g.fillRect(0, 0, width, height);

                // Subtle inset panel.
                int panelX = 1;
                int panelY = 1;
                int panelWidth = width - 2;
                int panelHeight = height - 2;

                g.setColor(PANEL_BACKGROUND);
                g.fill(new RoundRectangle2D.Double(
                        panelX,
                        panelY,
                        panelWidth,
                        panelHeight,
                        10,
                        10
                ));

                // Fine top accent line.
                GradientPaint accent = new GradientPaint(
                        0, 0, PROGRESS_COLORS[0],
                        width, 0, PROGRESS_COLORS[4]
                );

                g.setPaint(accent);
                g.fillRect(0, 0, width, 3);

                // Product title.
                g.setFont(TITLE_FONT);
                g.setColor(TEXT_PRIMARY);

                FontMetrics titleMetrics = g.getFontMetrics();
                int titleX =
                        (width - titleMetrics.stringWidth(APP_NAME)) / 2;

                g.drawString(APP_NAME, titleX, 139);

                // Edition label.
                g.setFont(EDITION_FONT);
                g.setColor(TEXT_SECONDARY);

                FontMetrics editionMetrics = g.getFontMetrics();
                int editionX =
                        (width - editionMetrics.stringWidth(EDITION)) / 2;

                g.drawString(EDITION, editionX, 165);

                // Progress track.
                int barX = (width - BAR_WIDTH) / 2;
                int barY = 263;

                g.setColor(new Color(255, 255, 255, 22));
                g.fillRoundRect(
                        barX,
                        barY,
                        BAR_WIDTH,
                        BAR_HEIGHT,
                        BAR_HEIGHT,
                        BAR_HEIGHT
                );

                // Animated, multicolour progress fill.
                int filledWidth = BAR_WIDTH * progress / 100;

                if (filledWidth > 0) {
                    paintProgressBar(
                            g,
                            barX,
                            barY,
                            filledWidth,
                            BAR_HEIGHT,
                            time
                    );
                }

                // Copyright.
                g.setFont(COPYRIGHT_FONT);
                g.setColor(new Color(180, 180, 185, 165));

                FontMetrics copyrightMetrics = g.getFontMetrics();
                int copyrightX =
                        (width - copyrightMetrics.stringWidth(COPYRIGHT)) / 2;

                g.drawString(
                        COPYRIGHT,
                        copyrightX,
                        height - 19
                );

                // Fine outline.
                g.setColor(new Color(255, 255, 255, 20));
                g.drawRoundRect(
                        0,
                        0,
                        width - 1,
                        height - 1,
                        10,
                        10
                );

            } finally {
                g.dispose();
            }
        }

        private void paintProgressBar(
                Graphics2D g,
                int x,
                int y,
                int width,
                int height,
                double time) {

            Graphics2D progressGraphics =
                    (Graphics2D) g.create();

            try {
                RoundRectangle2D shape =
                        new RoundRectangle2D.Double(
                                x,
                                y,
                                width,
                                height,
                                height,
                                height
                        );

                progressGraphics.clip(shape);

                float cycle = (float) ((time * 0.16) % 1.0);

                float[] fractions = {
                        0.0f,
                        0.2f,
                        0.4f,
                        0.6f,
                        0.8f,
                        1.0f
                };

                Color[] colors = new Color[PROGRESS_COLORS.length];

                for (int i = 0; i < colors.length; i++) {
                    colors[i] = PROGRESS_COLORS[
                            (i + (int) (cycle * colors.length))
                                    % PROGRESS_COLORS.length
                    ];
                }

                progressGraphics.setPaint(
                        new LinearGradientPaint(
                                x,
                                y,
                                x + BAR_WIDTH,
                                y,
                                fractions,
                                colors
                        )
                );

                progressGraphics.fillRect(
                        x,
                        y,
                        width,
                        height
                );

            } finally {
                progressGraphics.dispose();
            }
        }

        private void applyRenderingHints(Graphics2D g) {
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            g.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );

            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );

            g.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE
            );
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FSScanner splash = new FSScanner();
            splash.setVisible(true);
        });
    }
}
