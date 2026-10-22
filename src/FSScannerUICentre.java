import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Production workspace preview, using the same reusable canvas as test calibration. */
public final class FSScannerUICentre extends JPanel {
    private final FSScannerViewer previewCanvas = new FSScannerViewer(false, false, 215.9, 355.6);

    public FSScannerUICentre() {
        super(new BorderLayout(0, 6));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(4, 4, 4, 4)));
        previewCanvas.setToolTipText("Mouse wheel with Ctrl to zoom; right-click to zoom out; left-click to zoom in.");
        add(new JScrollPane(previewCanvas), BorderLayout.CENTER);
    }

    public void setPreviewImage(BufferedImage image, String caption) {
        previewCanvas.setImage(image, -1);
        previewCanvas.setToolTipText(caption == null || caption.isBlank()
                ? "Production scan preview" : caption);
    }
}
