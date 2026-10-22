/** Central defaults and supported command-line parameters for FS Scanner integrations. */
public final class FSScannerDefault {
    private FSScannerDefault() { }
    // Main UI defaults
    public static final int DPI = 200;
    public static final String COLOUR = "Colour";
    public static final int QUALITY = 80;
    public static final int SCALING = 100;
    public static final int BRIGHTNESS = 57;
    public static final int CONTRAST = 57;
    public static final String DEVICE = "";
    public static final String OUTPUT_FORMAT = ""; // Empty means let the gateway choose its native output format/extension.
    public static final String RAW_DIRECTORY = ".tmp"; // Captures and working files live directly in this directory; no per-scan folders.
    public static final String PDF_DIRECTORY = ".tmp"; // Processed PDFs remain directly in .tmp unless explicitly exported.
    public static final String PAGE_TYPE = "LETTER";
    /** Fixed hardware acquisition area for every main and test scan. */
    public static final double SCAN_WIDTH_MM = 215.9;
    public static final double SCAN_HEIGHT_MM = 297.0;
    /** Final Foolscap PDF page geometry; scaling is applied only during PDF conversion. */
    public static final double FS_PAGE_WIDTH_MM = 215.9;
    public static final double FS_PAGE_HEIGHT_MM = 355.6;
    public static final double TEST_PAGE_WIDTH_MM = FS_PAGE_WIDTH_MM;
    public static final double TEST_PAGE_HEIGHT_MM = FS_PAGE_HEIGHT_MM;
    public static final boolean TEST_AUTO_CROP = true;
    /** Moves the merged Foolscap page left by this many inches (negative = right). Override per install with
     *  the test property alignment.page.shift.left.in. */
    public static final double PAGE_SHIFT_LEFT_IN = 0.3;
    /** Canonical initial alignment values; config.properties overrides these when present. */
    public static final int ALIGNMENT_TOP_X = 0;
    public static final int ALIGNMENT_TOP_Y = 0;
    public static final int ALIGNMENT_TOP_W = 0;
    public static final int ALIGNMENT_TOP_H = 0;
    public static final int ALIGNMENT_BOTTOM_X = 0;
    public static final int ALIGNMENT_BOTTOM_Y = 0;
    public static final int ALIGNMENT_BOTTOM_W = 0;
    public static final int ALIGNMENT_BOTTOM_H = 0;
    public static final int ALIGNMENT_ROTATION = 0;
    public static final int ALIGNMENT_JOIN_OFFSET = 0;
    public static final int ALIGNMENT_BRIGHTNESS = 57;
    public static final int ALIGNMENT_DPI = 200;
    public static final double ALIGNMENT_PAGE_WIDTH_MM = 215.9;
    public static final double ALIGNMENT_PAGE_HEIGHT_MM = 355.6;
    public static final int GATEWAY_TIMEOUT_SECONDS = 180;
    public static final String GATEWAY_SOURCE = "auto";
    public static final String GATEWAY_MODE = "color";
    public static final String GATEWAY_FORMAT = ""; // Omit --format to preserve gateway-selected format.
    public static final int STITCH_MIN_OVERLAP_ROWS = 24;
    public static final int STITCH_MAX_OVERLAP_DIVISOR = 2;
    public static final double STITCH_MIN_CONFIDENCE = 0.72;
    public static final double STITCH_DUPLICATE_FRAME_SIMILARITY = 0.90;
    public static final int TEST_COORDINATE_DEFAULT = 0;
    public static final int TEST_ROTATION_DEFAULT = 0;
    public static final int TEST_JOIN_OFFSET_DEFAULT = 0;
    public static final int TEST_PREVIEW_BRIGHTNESS = 0;
    public static final String DEFAULT_WORKFLOW = "Top-2-Bottom";
    public static final String BULK_PDF_PREFIX = "FSScanner-Bulk-";
    public static final int TESSERACT_PSM = 3;
    public static final String TESSERACT_LANGUAGE = "eng";
    public static final String IMAGEMAGICK_QUALITY_PARAMETER = "-quality";

    public static final String[] GATEWAY_SUPPORTED_PARAMETERS = {
        "--list", "scan", "--out", "--mode", "--dpi", "--source", "--format",
        "--quality", "--pages", "--timeout", "--brightness", "--contrast",
        "--threshold", "--blank-skip", "--verbose", "--area"
    };

    public static final String[] IMAGEMAGICK_SUPPORTED_PARAMETERS = {
        "-resize", "-crop", "-rotate", "-append", "+append", "-smush", "-quality",
        "-density", "-units", "-colorspace", "-brightness-contrast", "-auto-orient",
        "-deskew", "-normalize", "-gravity", "-background", "-extent",
        "-trim", "+repage", "-strip", "-format", "-compress", "-define"
    };

    public static final String[] TESSERACT_SUPPORTED_PARAMETERS = {
        "--version", "--list-langs", "-l", "--psm", "--oem", "--dpi", "txt", "pdf", "hocr", "tsv"
    };
}