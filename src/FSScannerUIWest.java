import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.util.List;

/**
 * Child component for BorderLayout.WEST:
 * Prominent, non-overlapping hardware & real-time ImageMagick control sidebar.
 */
public final class FSScannerUIWest extends JScrollPane {

    public static final int SIDEBAR_WIDTH = 385;

    public record DeviceItem(String id, String label) {
        @Override public String toString() { return label; }
    }

    final DefaultComboBoxModel<DeviceItem> scannerModel = new DefaultComboBoxModel<>();
    final JComboBox<DeviceItem> scannerCombo = new JComboBox<>(scannerModel);

    final JComboBox<String> workflowCombo = new JComboBox<>(new String[]{
            "Top-2-Bottom", "FS Single Pass", "A4 Standard"
    });
    final JComboBox<String> colourCombo = new JComboBox<>(new String[]{
            "Colour", "Grayscale", "Black & White"
    });
    final JComboBox<Integer> dpiCombo = new JComboBox<>(new Integer[]{
            150, 200, 300, 400, 600, 1200
    });
    final JSlider qualitySlider = new JSlider(10, 100, FSScannerDefault.QUALITY);
    final JSlider scalingSlider = new JSlider(25, 200, FSScannerDefault.SCALING);
    final JLabel qualityValue = new JLabel(FSScannerDefault.QUALITY + "%");
    final JLabel scalingValue = new JLabel(FSScannerDefault.SCALING + "%");

    final JButton refreshScannersButton = new JButton("Refresh Scanners");
    final JButton setDefaultScannerButton = new JButton("Set Default Scanner");

    public FSScannerUIWest() {
        ViewportLockedPanel sidebar = new ViewportLockedPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(Color.WHITE);
        sidebar.setBorder(new EmptyBorder(16, 16, 16, 16));

        // Prevent long hardware names from blowing out layout width
        scannerCombo.setPrototypeDisplayValue(new DeviceItem("", "Auto-Select Default Flatbed Scanner"));

        scannerCombo.addActionListener(e -> {
            DeviceItem item = (DeviceItem) scannerCombo.getSelectedItem();
            scannerCombo.setToolTipText(item != null ? item.label() : null);
        });

        // --- Section 1: Hardware ---
        sidebar.add(sectionTitle("Hardware"));
        sidebar.add(Box.createVerticalStrut(6));

        sidebar.add(labeledComboBlock("Scanners & Printers:", scannerCombo));
        sidebar.add(Box.createVerticalStrut(6));
        JPanel scannerActions = new JPanel(new GridLayout(1, 2, 6, 0));
        scannerActions.setOpaque(false);
        scannerActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        scannerActions.add(refreshScannersButton);
        scannerActions.add(setDefaultScannerButton);
        sidebar.add(scannerActions);
        sidebar.add(Box.createVerticalStrut(18));

        // --- Section 2: FS Legal Scan Workflow ---
        sidebar.add(sectionTitle("FS Legal Scan Workflow"));
        sidebar.add(Box.createVerticalStrut(6));

        sidebar.add(labeledComboBlock("Capture Mode:", workflowCombo));
        sidebar.add(Box.createVerticalStrut(10));

        sidebar.add(labeledComboBlock("Resolution:", dpiCombo));
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(labeledComboBlock("Colour Mode:", colourCombo));
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(sectionTitle("PDF Output"));
        sidebar.add(Box.createVerticalStrut(6));
        sidebar.add(sliderBlock("Compression Quality", qualitySlider, qualityValue));
        sidebar.add(sliderBlock("Output Scaling", scalingSlider, scalingValue));
        qualitySlider.addChangeListener(e -> qualityValue.setText(qualitySlider.getValue() + "%"));
        scalingSlider.addChangeListener(e -> scalingValue.setText(scalingSlider.getValue() + "%"));
        sidebar.add(Box.createVerticalStrut(10));

        sidebar.add(Box.createVerticalGlue());

        setViewportView(sidebar);
        setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        getVerticalScrollBar().setUnitIncrement(16);
        setBorder(new LineBorder(new Color(226, 232, 240), 1, true));

        Dimension sidebarSize = new Dimension(SIDEBAR_WIDTH, 0);
        setPreferredSize(sidebarSize);
        setMinimumSize(new Dimension(350, 0));
    }

    public void populateScannerCombo(List<FSScannerHW.DeviceInfo> devices, String defaultId) {
        scannerModel.removeAllElements();
        scannerModel.addElement(new DeviceItem("", "Auto-Select Default Flatbed Scanner"));
        DeviceItem toSelect = scannerModel.getElementAt(0);
        if (devices != null) {
            for (FSScannerHW.DeviceInfo d : devices) {
                boolean isDefault = d.id().equals(defaultId);
                DeviceItem item = new DeviceItem(d.id(), d.displayName() + (isDefault ? " (Default)" : ""));
                scannerModel.addElement(item);
                if (isDefault) toSelect = item;
            }
        }
        scannerCombo.setSelectedItem(toSelect);
        scannerCombo.setToolTipText(toSelect.label());
    }


    public void markDefaultScanner(String defaultId) {
        DeviceItem selected = null;
        for (int i = 0; i < scannerModel.getSize(); i++) {
            DeviceItem old = scannerModel.getElementAt(i);
            if (old.id().isBlank()) continue;
            String plain = old.label().replace(" (Default)", "");
            DeviceItem updated = new DeviceItem(old.id(), plain + (old.id().equals(defaultId) ? " (Default)" : ""));
            scannerModel.removeElementAt(i);
            scannerModel.insertElementAt(updated, i);
            if (old.id().equals(defaultId)) selected = updated;
        }
        if (selected != null) scannerCombo.setSelectedItem(selected);
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setForeground(new Color(15, 23, 42));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(226, 232, 240)),
                new EmptyBorder(0, 0, 6, 0)));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height + 8));
        return label;
    }

    private static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 12));
        label.setForeground(new Color(51, 65, 85));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel labeledComboBlock(String labelText, JComboBox<?> combo) {
        JPanel block = new JPanel(new BorderLayout(0, 5));
        block.setOpaque(false);
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        combo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        Dimension pref = combo.getPreferredSize();
        combo.setPreferredSize(new Dimension(pref.width, Math.max(32, pref.height)));

        block.add(fieldLabel(labelText), BorderLayout.NORTH);
        block.add(combo, BorderLayout.CENTER);
        lockPreferredHeight(block);
        return block;
    }

    private static JPanel sliderBlock(String title, JSlider slider, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(new EmptyBorder(2, 0, 4, 0));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(fieldLabel(title), BorderLayout.WEST);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        valueLabel.setForeground(new Color(3, 105, 161));
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        top.add(valueLabel, BorderLayout.EAST);

        slider.setOpaque(false);
        slider.setFocusable(false);

        panel.add(top, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        lockPreferredHeight(panel);
        return panel;
    }

    /**
     * Prevents BoxLayout from vertically squishing or stretching a panel beyond its natural height.
     */
    private static void lockPreferredHeight(JComponent comp) {
        Dimension pref = comp.getPreferredSize();
        comp.setMinimumSize(new Dimension(100, pref.height));
        comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    /**
     * Scrollable container that locks its width to the JScrollPane viewport so child components
     * never overflow horizontally or clip text.
     */
    private static final class ViewportLockedPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
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