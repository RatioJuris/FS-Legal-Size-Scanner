import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.time.Year;

/**
 * FS Scanner splash screen.
 *
 * Pre-caches fonts and strokes so the 30 FPS animation timer allocates minimal objects on the EDT.
 */
public final class FSScanner extends JFrame {
    private static final String APP_NAME = "FS Scanner";
    private static final String EDITION = "365 Premiere";
    private static final String COPYRIGHT = "Copyright © " + Year.now().getValue()
            + " RatioJuris. All Rights Reserved";
    private static final String PNG_ICON_PATH = "res/fs-scanner.png";

    private static final int CARD_W = 620;
    private static final int CARD_H = 340;
    private static final int MARGIN = 30;

    private static final Font TITLE_FONT = new Font("Segoe UI Light", Font.PLAIN, 46);
    private static final Font BADGE_FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font COPYRIGHT_FONT = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Stroke BORDER_STROKE = new BasicStroke(1.4f);

    private final JLabel status = new JLabel("Preparing FS Scanner...");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel brandMark = new JLabel();
    private final long startedAt = System.nanoTime();
    private final Timer timer;
    private boolean launched;

    public FSScanner() {
        super(APP_NAME);
        ImageIcon appIcon = new ImageIcon(PNG_ICON_PATH);
        if (appIcon.getIconWidth() > 0 && appIcon.getIconHeight() > 0) {
            setIconImage(appIcon.getImage());
            Image scaled = appIcon.getImage().getScaledInstance(38, 38, Image.SCALE_SMOOTH);
            brandMark.setIcon(new ImageIcon(scaled));
        }
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setSize(CARD_W + MARGIN * 2, CARD_H + MARGIN * 2);
        setLocationRelativeTo(null);

        GlassPanel panel = new GlassPanel();
        setContentPane(panel);

        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setForeground(new Color(255, 255, 255, 225));
        status.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        status.setOpaque(false);
        panel.add(status);

        brandMark.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(brandMark);

        progress.setOpaque(false);
        progress.setBorderPainted(false);
        progress.setStringPainted(false);
        progress.setForeground(new Color(55, 235, 205));
        progress.setBackground(new Color(255, 255, 255, 50));
        panel.add(progress);

        timer = new Timer(35, e -> updateSplash());
        timer.start();
    }

    private void updateSplash() {
        double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        int value = Math.min(100, (int) (elapsed / 2.8 * 100));
        progress.setValue(value);

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

    private final class GlassPanel extends JPanel {
        private final long animationStart = System.nanoTime();
        private final BufferedImage grain = createGrain();
        private final Timer animation = new Timer(33, e -> repaint());

        GlassPanel() {
            setLayout(null);
            setOpaque(false);
            MouseAdapter drag = new MouseAdapter() {
                private Point origin;

                @Override
                public void mousePressed(MouseEvent e) {
                    origin = e.getPoint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    origin = null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (origin != null) {
                        Point p = e.getLocationOnScreen();
                        FSScanner.this.setLocation(p.x - origin.x, p.y - origin.y);
                    }
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
            animation.start();
        }

        @Override
        public void removeNotify() {
            animation.stop();
            super.removeNotify();
        }

        @Override
        public void doLayout() {
            status.setBounds(MARGIN + 40, MARGIN + 196, CARD_W - 80, 22);
            progress.setBounds(MARGIN + 70, MARGIN + 226, CARD_W - 140, 26);
            brandMark.setBounds((getWidth() - 38) / 2, MARGIN + 44, 38, 38);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setComposite(AlphaComposite.SrcOver);

            double t = (System.nanoTime() - animationStart) / 1_000_000_000.0;
            int x = MARGIN, y = MARGIN;
            RoundRectangle2D card = new RoundRectangle2D.Double(x, y, CARD_W, CARD_H, 38, 38);

            g.setColor(new Color(0, 0, 0, 65));
            g.fill(new RoundRectangle2D.Double(x + 2, y + 8, CARD_W, CARD_H, 38, 38));

            Graphics2D c = (Graphics2D) g.create();
            c.clip(card);
            c.setPaint(new GradientPaint(x, y, new Color(17, 37, 49), x + CARD_W, y + CARD_H,
                    new Color(7, 14, 27)));
            c.fill(card);

            paintOrb(c, (float) (x + CARD_W * (0.25 + 0.06 * Math.sin(t * 0.45))),
                    (float) (y + CARD_H * (0.43 + 0.08 * Math.cos(t * 0.32))),
                    300, new Color(34, 238, 207, 190));
            paintOrb(c, (float) (x + CARD_W * (0.82 + 0.04 * Math.cos(t * 0.31))),
                    (float) (y + CARD_H * (0.70 + 0.06 * Math.sin(t * 0.40))),
                    320, new Color(75, 103, 255, 205));
            paintOrb(c, (float) (x + CARD_W * 0.62), (float) (y + CARD_H * 0.10),
                    190, new Color(255, 173, 91, 125));

            c.setColor(new Color(255, 255, 255, 28));
            c.fill(card);
            c.setPaint(new TexturePaint(grain,
                    new Rectangle2D.Double(x, y, grain.getWidth(), grain.getHeight())));
            c.fill(card);

            double phase = (t % 5.5) / 5.5;
            float beamX = (float) (x - 100 + (CARD_W + 200) * phase);
            Graphics2D beam = (Graphics2D) c.create();
            beam.translate(beamX, y);
            beam.shear(-0.28, 0);
            beam.setPaint(new LinearGradientPaint(-65, 0, 65, 0,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(255, 255, 255, 0), new Color(240, 255, 252, 42),
                            new Color(255, 255, 255, 0)}));
            beam.fillRect(-65, 0, 130, CARD_H);
            beam.dispose();
            c.dispose();

            g.setStroke(BORDER_STROKE);
            g.setPaint(new LinearGradientPaint(x, y, x + CARD_W, y + CARD_H,
                    new float[]{0f, 0.35f, 0.7f, 1f},
                    new Color[]{new Color(255, 255, 255, 210), new Color(255, 255, 255, 55),
                            new Color(255, 255, 255, 35), new Color(255, 255, 255, 135)}));
            g.draw(new RoundRectangle2D.Double(x + .7, y + .7, CARD_W - 1.4,
                    CARD_H - 1.4, 38, 38));

            FontMetrics fm = g.getFontMetrics(TITLE_FONT);
            g.setFont(TITLE_FONT);
            g.setColor(new Color(0, 10, 16, 80));
            g.drawString(APP_NAME, (getWidth() - fm.stringWidth(APP_NAME)) / 2, y + 110);
            g.setPaint(new GradientPaint(0, y + 70, Color.WHITE, 0, y + 112,
                    new Color(198, 235, 239)));
            g.drawString(APP_NAME, (getWidth() - fm.stringWidth(APP_NAME)) / 2, y + 108);

            g.setFont(BADGE_FONT);
            FontMetrics bfm = g.getFontMetrics();
            int badgeW = bfm.stringWidth(EDITION) + 32;
            int badgeX = (getWidth() - badgeW) / 2;
            int badgeY = y + 128;
            g.setColor(new Color(255, 255, 255, 25));
            g.fillRoundRect(badgeX, badgeY, badgeW, 27, 27, 27);
            g.setColor(new Color(255, 255, 255, 100));
            g.drawRoundRect(badgeX, badgeY, badgeW, 27, 27, 27);
            g.setColor(new Color(210, 255, 249, 238));
            g.drawString(EDITION, badgeX + 16, badgeY + 18);

            g.setFont(COPYRIGHT_FONT);
            g.setColor(new Color(255, 255, 255, 125));
            FontMetrics cfm = g.getFontMetrics();
            g.drawString(COPYRIGHT, (getWidth() - cfm.stringWidth(COPYRIGHT)) / 2,
                    y + CARD_H - 23);
            g.dispose();
        }

        private void paintOrb(Graphics2D g, float cx, float cy, float radius, Color color) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), radius,
                    new float[]{0f, 0.52f, 1f},
                    new Color[]{color, new Color(color.getRed(), color.getGreen(),
                            color.getBlue(), 65), new Color(color.getRed(), color.getGreen(),
                            color.getBlue(), 0)}));
            g.fillRect(MARGIN, MARGIN, CARD_W, CARD_H);
        }

        private BufferedImage createGrain() {
            BufferedImage image = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
            java.util.Random random = new java.util.Random(365);
            for (int yy = 0; yy < image.getHeight(); yy++) {
                for (int xx = 0; xx < image.getWidth(); xx++) {
                    int alpha = random.nextInt(17);
                    image.setRGB(xx, yy, (alpha << 24) | 0xFFFFFF);
                }
            }
            return image;
        }
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FSScanner().setVisible(true));
    }
}