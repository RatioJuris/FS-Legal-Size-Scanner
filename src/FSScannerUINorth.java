import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * Child component for BorderLayout.NORTH:
 * Renders the application header toolbar and the guided step-by-step banner.
 */
public final class FSScannerUINorth extends JPanel {

    final JLabel stepBadge = new JLabel("STEP 1 OF 2 — PLACE TOP HALF");
    final JLabel workflowInstruction = new JLabel(
            "Align the top edge of the FS (Legal) page on the scanner glass and click 'Scan Top Half (_FST)'.");

    final JButton testBtn = new JButton("Test Scans");
    final JButton openFolderBtn = new JButton("Open .tmp Workspace");

    public FSScannerUINorth() {
        super(new BorderLayout(12, 8));
        setOpaque(false);

        // Top Bar: App title + quick utility buttons
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(new Color(15, 23, 42));
        header.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("FS Scanner — Legal Page Flatbed Stitching Suite");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(Color.WHITE);
        header.add(title, BorderLayout.WEST);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        tools.add(testBtn);
        tools.add(openFolderBtn);
        header.add(tools, BorderLayout.EAST);

        // Guided Step-by-Step Banner for Legal/FS Scanning
        JPanel guideBar = new JPanel(new BorderLayout(12, 0));
        guideBar.setBackground(new Color(224, 242, 254));
        guideBar.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(125, 211, 252), 1, true),
                new EmptyBorder(9, 14, 9, 14)));

        stepBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        stepBadge.setForeground(new Color(3, 105, 161));
        workflowInstruction.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        workflowInstruction.setForeground(new Color(12, 74, 110));

        guideBar.add(stepBadge, BorderLayout.WEST);
        guideBar.add(workflowInstruction, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(guideBar, BorderLayout.SOUTH);
    }

    public void setGuidance(String badgeText, String instructionText) {
        stepBadge.setText(badgeText);
        workflowInstruction.setText(instructionText);
    }
}