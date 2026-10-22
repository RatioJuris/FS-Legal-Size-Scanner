import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * Child component for BorderLayout.SOUTH:
 * Renders the real-time status label, progress bar, and primary scan/export buttons.
 */
public final class FSScannerUISouth extends JPanel {

    final JLabel statusLabel = new JLabel("Ready.");
    final JProgressBar progressBar = new JProgressBar();

    final JButton primaryScanBtn = new JButton("Scan Top Half (_FST)");
    final JButton scanBottomBtn = new JButton("Scan Bottom Half (_FSB) & Stitch");
    final JButton exportPdfBtn = new JButton("Save Bulk PDF...");
    final JButton cancelBtn = new JButton("Cancel");

    public FSScannerUISouth() {
        super(new BorderLayout(14, 0));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(10, 14, 10, 14)));

        JPanel statusBox = new JPanel(new BorderLayout(8, 4));
        statusBox.setOpaque(false);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        statusLabel.setForeground(new Color(30, 41, 59));
        progressBar.setPreferredSize(new Dimension(220, 18));
        progressBar.setStringPainted(true);
        statusBox.add(statusLabel, BorderLayout.CENTER);
        statusBox.add(progressBar, BorderLayout.SOUTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        primaryScanBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        primaryScanBtn.setPreferredSize(new Dimension(205, 36));

        scanBottomBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        scanBottomBtn.setPreferredSize(new Dimension(245, 36));
        scanBottomBtn.setEnabled(false);

        exportPdfBtn.setPreferredSize(new Dimension(165, 36));
        exportPdfBtn.setEnabled(false);
        cancelBtn.setPreferredSize(new Dimension(95, 36));
        cancelBtn.setEnabled(false);

        actions.add(primaryScanBtn);
        actions.add(scanBottomBtn);
        actions.add(exportPdfBtn);
        actions.add(cancelBtn);

        add(statusBox, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
    }

    public void setBusyState(boolean isBusy, boolean awaitingBottomHalf, String statusText) {
        statusLabel.setText(statusText);
        progressBar.setIndeterminate(isBusy);
        progressBar.setString(isBusy ? "Processing in real time..." : "Idle");
        primaryScanBtn.setEnabled(!isBusy);
        scanBottomBtn.setEnabled(!isBusy && awaitingBottomHalf);
        cancelBtn.setEnabled(isBusy);
    }
}