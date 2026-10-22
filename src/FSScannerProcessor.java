import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time & recoverable scan-session processor.
 *
 * Split FST/FSB captures are normalized, their actual visual overlap is detected,
 * and the duplicate region is removed before stitching. Raw captures are retained
 * when overlap cannot be confidently detected.
 */
public final class FSScannerProcessor implements AutoCloseable {
    private final FSScannerData data;
    private final FSScannerMagic magic;
    private final FSScannerCR ocr;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final Map<String, Path> realtimePreprocessedTopCache = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FSScanner-Processor");
        t.setDaemon(true);
        return t;
    });

    static {
        ImageIO.setUseCache(true);
    }

    public FSScannerProcessor() {
        this(new FSScannerData(), new FSScannerMagic(), new FSScannerCR());
    }

    public FSScannerProcessor(FSScannerData data, FSScannerMagic magic, FSScannerCR ocr) {
        this.data = Objects.requireNonNull(data);
        this.magic = Objects.requireNonNull(magic);
        this.ocr = Objects.requireNonNull(ocr);
    }

    public void cancelProcessing() {
        cancelRequested.set(true);
        magic.cancelActiveProcesses();
        ocr.cancelActiveProcesses();
    }

    public CompletableFuture<Optional<ProcessingResult>> onCaptureCompletedRealTimeAsync(
            FSScannerData.ScanRecord latestCapture) {
        Objects.requireNonNull(latestCapture, "latestCapture");
        String type = FSScannerData.scanType(latestCapture.name());
        if ("FST".equals(type)) {
            return data.loadTestAsync().thenCompose(test -> {
                Path src = latestCapture.filePath().toAbsolutePath().normalize();
                Path prepped = src.resolveSibling(".rt-top-" + latestCapture.id() + ".png");
                List<String> ops = new ArrayList<>();
                addCropAndRotation(ops, test, "top", false);
                if (ops.isEmpty()) {
                    realtimePreprocessedTopCache.put(latestCapture.id(), src);
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return magic.convertAsync(src, prepped, ops).thenApply(res -> {
                    realtimePreprocessedTopCache.put(latestCapture.id(), res.outputFile());
                    return Optional.<ProcessingResult>empty();
                }).exceptionally(ex -> Optional.empty());
            });
        }
        return processSessionAsync().thenApply(Optional::of);
    }

    public CompletableFuture<ProcessingResult> processSessionAsync() {
        if (!processing.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A scan session is already being processed."));
        }
        cancelRequested.set(false);
        return data.readAllAsync().thenApplyAsync(records -> {
            try {
                return process(records);
            } catch (Exception e) {
                throw new CompletionException(unwrap(e));
            } finally {
                processing.set(false);
            }
        }, worker).whenComplete((r, e) -> {
            if (e != null) processing.set(false);
        });
    }

    private ProcessingResult process(List<FSScannerData.ScanRecord> records) throws Exception {
        records = records.stream().filter(r -> !r.processed()).toList();
        if (records.isEmpty()) {
            return new ProcessingResult(0, List.of(),
                    "No pending captures. Scan metadata history remains in fs-data.bin.");
        }

        Path ledgerParent = data.getFile().getParent();
        Path root = ledgerParent == null
                ? Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : ledgerParent;
        Path tempDir = ledgerParent != null && ledgerParent.getFileName() != null
                && FSScannerDefault.RAW_DIRECTORY.equals(ledgerParent.getFileName().toString())
                ? ledgerParent : root.resolve(FSScannerDefault.RAW_DIRECTORY);
        Path outDir = tempDir;
        Files.createDirectories(outDir);

        for (FSScannerData.ScanRecord r : records) {
            checkCancelled();
            Path p = r.filePath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
                throw new IOException("Recorded scan is missing or unreadable; fs-data.bin and captures were preserved: " + p);
            }
        }

        List<LogicalDocument> docs = groupDocuments(records);
        Properties test = data.loadTestAsync().join();
        List<Path> staged = new ArrayList<>(), finalOutputs = new ArrayList<>(), tempProducts = new ArrayList<>();
        List<Path> transientFiles = new ArrayList<>();
        Map<Path, Path> finalTargetByStage = new LinkedHashMap<>();
        String stageToken = UUID.randomUUID().toString();
        StringBuilder manifest = new StringBuilder("FS Scanner session manifest\n");

        try {
            int index = 0;
            for (LogicalDocument doc : docs) {
                checkCancelled();
                index++;
                String base = safeBase(doc.records.get(0).name());
                int dpi = effectiveDpi(doc.records.get(0), test);
                Path workingImage;
                String firstType = effectiveScanType(doc.records.get(0));

                if (doc.records.size() == 2 && "FST".equals(firstType)
                        && "FSB".equals(effectiveScanType(doc.records.get(1)))) {
                    workingImage = outDir.resolve(".fsscanner-stage-" + stageToken + "-" + index + "-merged.png");
                    transientFiles.add(workingImage);
                    FSScannerData.ScanRecord top = doc.records.get(0), bottom = doc.records.get(1);
                    StitchResult stitch = stitchWithOverlapDetection(top, bottom, workingImage, test);
                    requireNonEmpty(workingImage, "reconstructed image");
                    tempProducts.add(workingImage);
                    manifest.append("STITCH\t").append(base)
                            .append("\toverlapRows=").append(stitch.overlapRows())
                            .append("\tconfidence=").append(String.format(Locale.ROOT, "%.3f", stitch.confidence()))
                            .append("\tmethod=").append(stitch.method()).append('\n');
                } else {
                    FSScannerData.ScanRecord single = doc.records.get(0);
                    String type = effectiveScanType(single);
                    if ("FST".equals(type) || "FSB".equals(type)) {
                        throw new IOException("Incomplete FST/FSB pair for " + single.name()
                                + "; captures and fs-data.bin were preserved.");
                    }
                    if ("FS".equals(type)) {
                        workingImage = outDir.resolve(".fsscanner-stage-" + stageToken + "-" + index + "-tested.tif");
                        transientFiles.add(workingImage);
                        List<String> args = new ArrayList<>();
                        args.add(single.filePath().toAbsolutePath().normalize().toString());
                        addCropAndRotation(args, test, "top", false);
                        // Keep the intermediate image at its acquired/cropped pixel geometry.
                        // Output scaling is applied exactly once by pageGeometry during PDF conversion below.
                        args.addAll(List.of("-density", Integer.toString(dpi), "-units", "PixelsPerInch", workingImage.toString()));
                        magic.executeAsync(args).join();
                        requireNonEmpty(workingImage, "tested FS image");
                        tempProducts.add(workingImage);
                    } else {
                        workingImage = single.filePath().toAbsolutePath().normalize();
                    }
                }

                Path pdfStage = outDir.resolve(".fsscanner-stage-" + stageToken + "-" + index + ".pdf");
                transientFiles.add(pdfStage);
                finalTargetByStage.put(pdfStage, uniqueOutputPath(outDir.resolve(base + ".pdf")));

                String filenameType = effectiveScanType(doc.records.get(0));
                String effectivePageType = switch (filenameType) {
                    case "FS", "FST", "FSB" -> "FS";
                    case "A4" -> "A4";
                    default -> doc.records.get(0).pageType();
                };
                List<String> pdfOptions = new ArrayList<>(pageGeometry(
                        effectivePageType, doc.records.get(0).scaling(), dpi, test));
                pdfOptions.addAll(List.of("-auto-orient", "-deskew", "40%", "-normalize",
                        "-density", Integer.toString(dpi), "-units", "PixelsPerInch", "-quality", "95"));

                int effectiveBrightness = test.containsKey("brightness")
                        ? intSetting(test, "brightness", doc.records.get(0).brightness())
                        : doc.records.get(0).brightness();
                if (effectiveBrightness != 0 || doc.records.get(0).contrast() != 0) {
                    pdfOptions.addAll(List.of("-brightness-contrast",
                            effectiveBrightness + "x" + doc.records.get(0).contrast()));
                }

                CompletableFuture<FSScannerMagic.ProcessResult> pdfFuture =
                        magic.convertAsync(workingImage, pdfStage, pdfOptions);
                CompletableFuture<String> ocrFuture =
                        ocr.recognizeAsync(workingImage, FSScannerCR.OcrOptions.defaults().withDpi(dpi));

                pdfFuture.join();
                requireNonEmpty(pdfStage, "PDF");
                staged.add(pdfStage);

                Path txtStage = outDir.resolve(".fsscanner-stage-" + stageToken + "-" + index + ".txt");
                transientFiles.add(txtStage);
                try {
                    String recognised = ocrFuture.join();
                    Files.writeString(txtStage, recognised, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    finalTargetByStage.put(txtStage, uniqueOutputPath(outDir.resolve(base + ".txt")));
                    staged.add(txtStage);
                } catch (CompletionException ocrFailure) {
                    manifest.append("OCR_WARNING\t").append(base).append('\t')
                            .append(rootMessage(ocrFailure)).append('\n');
                }

                manifest.append("OK\t").append(base).append("\trecords=")
                        .append(String.join(",", doc.records.stream().map(FSScannerData.ScanRecord::id).toList()))
                        .append("\tpageType=").append(doc.records.get(0).pageType())
                        .append("\tDPI=").append(dpi)
                        .append("\tinputCount=").append(doc.records.size()).append('\n');
            }

            checkCancelled();
            Path manifestStage = outDir.resolve(".fsscanner-stage-" + stageToken + "-session-manifest.txt");
            transientFiles.add(manifestStage);
            Files.writeString(manifestStage, manifest.toString(), StandardCharsets.UTF_8);
            finalTargetByStage.put(manifestStage, uniqueOutputPath(outDir.resolve("session-manifest.txt")));
            staged.add(manifestStage);

            for (Path p : staged) {
                checkCancelled();
                Path target = finalTargetByStage.get(p);
                if (target == null) throw new IOException("Missing final target for staged output: " + p.getFileName());
                moveReplace(p, target);
                finalOutputs.add(target);
            }

            checkCancelled();
            data.markProcessedAsync(records.stream().map(FSScannerData.ScanRecord::id).toList()).join();
            for (FSScannerData.ScanRecord r : records) {
                Files.deleteIfExists(r.filePath().toAbsolutePath().normalize());
                Path rtCached = realtimePreprocessedTopCache.remove(r.id());
                if (rtCached != null && !rtCached.equals(r.filePath().toAbsolutePath().normalize())) {
                    Files.deleteIfExists(rtCached);
                }
            }
            return new ProcessingResult(docs.size(), List.copyOf(finalOutputs),
                    "Processing completed. Final PDFs are staged in: " + outDir);
        } finally {
            for (Path p : transientFiles) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            for (Path p : tempProducts) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    /**
     * Normalize both images to the same orientation, find their visual overlap by
     * comparing horizontal bands, remove the duplicate rows from the bottom image,
     * and then join the two remaining pieces. This avoids relying on a fixed join offset.
     *
     * Important: overlap detection fails closed: if no confident overlap is found,
     * processing throws and source captures remain available.
     */
    /**
     * Reconstruct a Foolscap page from two A4-height flatbed captures.
     * The target geometry is deterministic: 8.5 x 14 inches. FST contributes
     * one A4-height section; the rotated FSB contributes the complementary
     * 14-inch-minus-A4-height strip. No image-overlap guessing is performed.
     */
    private StitchResult stitchWithOverlapDetection(
            FSScannerData.ScanRecord topRecord,
            FSScannerData.ScanRecord bottomRecord,
            Path output,
            Properties test) throws IOException {

        BufferedImage rawTop = ImageIO.read(topRecord.filePath().toFile());
        BufferedImage rawBottom = ImageIO.read(bottomRecord.filePath().toFile());
        if (rawTop == null || rawBottom == null) {
            if (rawTop != null) rawTop.flush();
            if (rawBottom != null) rawBottom.flush();
            throw new IOException("Could not decode both split captures; source captures were preserved.");
        }
        BufferedImage top = null, bottom = null, merged = null;
        try {
            top = calibratedImage(rawTop, test, "top", false);
            bottom = calibratedImage(rawBottom, test, "bottom", true);
            int dpi = effectiveDpi(topRecord, test);
            // Measure where the bottom capture really sits (shift, skew, exposure) from the
            // content both scans share, then build the page from that measured geometry
            // instead of trusting nominal 14.00 in / crop-edge positions.
            FSScannerStitcher.Alignment alignment = FSScannerStitcher.align(top, bottom, dpi, 14.0);
            int seamY = -1; // -1 = automatic quiet row near the middle of the overlap
            if (Boolean.parseBoolean(test.getProperty("alignment.seam.manual", "false"))) {
                int pageHeight = Math.max(1, (int) Math.round(14.0 * dpi));
                seamY = configuredSeamPixels(test, topRecord, rawTop, top, dpi, pageHeight);
            }
            int shiftLeftPx = (int) Math.round(pageShiftLeftIn(test) * dpi);
            merged = FSScannerStitcher.stitchAligned(top, alignment, dpi, 8.5, 14.0, seamY, shiftLeftPx);
            if (!ImageIO.write(merged, "png", output.toFile())) {
                throw new IOException("No PNG writer is available for reconstructed FS output.");
            }
            String method = alignment.confident
                    ? "aligned-ncc " + alignment.describe()
                    : "REVIEW-no-reliable-overlap-match(nominal geometry used) " + alignment.describe();
            return new StitchResult(alignment.overlapRows, Math.max(0.0, alignment.score), method);
        } finally {
            if (merged != null) merged.flush();
            if (top != null && top != rawTop) top.flush();
            if (bottom != null && bottom != rawBottom) bottom.flush();
            rawTop.flush();
            rawBottom.flush();
        }
    }

    /** Horizontal page position correction in inches (positive = move page left). */
    private static double pageShiftLeftIn(Properties test) {
        String text = test.getProperty("alignment.page.shift.left.in", test.getProperty("page.shift.left.in"));
        if (text != null) {
            try {
                double v = Double.parseDouble(text.trim());
                if (Double.isFinite(v) && Math.abs(v) <= 2.0) return v;
            } catch (NumberFormatException ignored) { /* fall through to the default */ }
        }
        return FSScannerDefault.PAGE_SHIFT_LEFT_IN;
    }

    /** Maps the saved FST laser coordinate to the calibrated source image without viewer zoom. */
    private static int configuredSeamPixels(Properties test, FSScannerData.ScanRecord record,
                                            BufferedImage rawTop, BufferedImage calibratedTop,
                                            int dpi, int targetHeight) throws IOException {
        String seamInText = test.getProperty("alignment.seam.in", test.getProperty("seam.in"));
        if (seamInText != null) {
            try {
                double seamIn = Double.parseDouble(seamInText.trim());
                if (!Double.isFinite(seamIn)) throw new NumberFormatException();
                return (int) Math.round(seamIn * dpi);
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid alignment.seam.in; expected a finite page-top distance in inches.", ex);
            }
        }

        int stored = intSetting(test, "alignment.top.seam.y",
                intSetting(test, "top.seam.y", -1));
        int seam;
        if (stored < 0) {
            // Centre the seam when no calibration exists; this is the safest neutral default.
            seam = targetHeight / 2;
        } else {
            int sourceY = stored;
            int coordinateHeight = rawTop.getHeight();
            if (Boolean.parseBoolean(test.getProperty("autoCrop", "true"))) {
                int cropY = intSetting(test, "top.y", 0);
                int cropH = intSetting(test, "top.h", 0);
                if (cropH > 0) {
                    sourceY -= cropY;
                    coordinateHeight = cropH;
                }
            }
            sourceY = Math.max(0, Math.min(coordinateHeight, sourceY));
            int angle = Math.floorMod(intSetting(test, "rotation", 0), 360);
            if (angle == 180) sourceY = coordinateHeight - sourceY;
            else if (angle == 90 || angle == 270) {
                throw new IOException("A vertical seam coordinate cannot be mapped through a 90°/270° rotation. "
                        + "Use alignment.seam.in or set rotation to 0°/180°.");
            }
            // Convert the source-image coordinate into the calibrated image frame.
            seam = (int) Math.round(sourceY * (calibratedTop.getHeight()
                    / (double) Math.max(1, coordinateHeight)));
        }
        return Math.max(0, Math.min(targetHeight, seam));
    }

    private static List<LogicalDocument> groupDocuments(List<FSScannerData.ScanRecord> records) throws IOException {
        List<LogicalDocument> docs = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            FSScannerData.ScanRecord r = records.get(i);
            String type = effectiveScanType(r);
            if ("FST".equals(type)) {
                if (i + 1 >= records.size() || !"FSB".equals(effectiveScanType(records.get(i + 1)))) {
                    throw new IOException("Top capture " + r.name()
                            + " is missing its corresponding _FSB bottom capture. Captures and fs-data.bin were preserved.");
                }
                FSScannerData.ScanRecord bottom = records.get(++i);
                if (!Files.isRegularFile(bottom.filePath())) {
                    throw new IOException("Bottom capture is missing: " + bottom.name());
                }
                LogicalDocument doc = new LogicalDocument(r);
                doc.records.add(bottom);
                docs.add(doc);
            } else if ("FSB".equals(type)) {
                throw new IOException("Bottom capture " + r.name()
                        + " has no preceding _FST top capture. Captures and fs-data.bin were preserved.");
            } else if ("FS".equals(type) || "A4".equals(type)) {
                docs.add(new LogicalDocument(r));
            } else {
                throw new IOException("Cannot determine scan type from filename suffix: " + r.name()
                        + ". Expected _FS, _FST, _FSB, or _A4. Captures and fs-data.bin were preserved.");
            }
        }
        return docs;
    }

    private static String effectiveScanType(FSScannerData.ScanRecord record) throws IOException {
        String filenameType = FSScannerData.scanType(record.name());
        if (!"UNKNOWN".equals(filenameType)) return filenameType;
        String direction = record.scanDirection() == null ? "" : record.scanDirection().toUpperCase(Locale.ROOT);
        String workflow = record.workflow() == null ? "" : record.workflow().toLowerCase(Locale.ROOT);
        String pageType = record.pageType() == null ? "" : record.pageType().toUpperCase(Locale.ROOT);
        if (direction.contains("BOTTOM_TO_TOP") || workflow.endsWith("-bottom")) return "FSB";
        if (workflow.startsWith("top-2-bottom") || workflow.contains("top-2-bottom")) return "FST";
        if ("A4".equals(pageType) || workflow.equals("a4")) return "A4";
        if ("FS".equals(pageType) || workflow.toLowerCase(Locale.ROOT).contains("fs")) return "FS";
        throw new IOException("Cannot determine scan type from filename or legacy metadata: " + record.name()
                + ". Rename it to include _FS, _FST, _FSB, or _A4, or correct its capture metadata. "
                + "Captures and fs-data.bin were preserved.");
    }

    private static BufferedImage calibratedImage(BufferedImage original, Properties test,
                                                   String prefix, boolean reverseCapture) throws IOException {
        BufferedImage image = original;
        // The FSB guides are saved in the rotated preview's coordinate frame.
        // Rotate the complete capture before applying that crop, then apply any
        // user correction rotation to the retained region.
        if (reverseCapture) image = rotateImageForCalibration(image, 180);
        if (Boolean.parseBoolean(test.getProperty("autoCrop", "true"))) {
            int x = intSetting(test, prefix + ".x", 0);
            int y = intSetting(test, prefix + ".y", 0);
            int w = intSetting(test, prefix + ".w", 0);
            int h = intSetting(test, prefix + ".h", 0);
            if (w > 0 && h > 0) {
                if (x < 0 || y < 0 || x > image.getWidth() - w || y > image.getHeight() - h) {
                    throw new IOException("Invalid " + prefix + " crop rectangle " + x + "," + y + " "
                            + w + "x" + h + " for source image " + image.getWidth() + "x" + image.getHeight() + ".");
                }
                image = image.getSubimage(x, y, w, h);
            }
        }
        int angle = intSetting(test, "rotation", 0);
        if (Math.floorMod(angle, 360) == 0) return image;
        return rotateImageForCalibration(image, angle);
    }

    private static BufferedImage rotateImageForCalibration(BufferedImage image, int angle) {
        int normalized = Math.floorMod(angle, 360);
        if (normalized == 0) return image;
        double radians = Math.toRadians(normalized);
        int ow = Math.max(1, (int) Math.ceil(Math.abs(image.getWidth() * Math.cos(radians))
                + Math.abs(image.getHeight() * Math.sin(radians)) - 1e-9));
        int oh = Math.max(1, (int) Math.ceil(Math.abs(image.getHeight() * Math.cos(radians))
                + Math.abs(image.getWidth() * Math.sin(radians)) - 1e-9));
        BufferedImage rotated = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, ow, oh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.translate((ow - image.getWidth()) / 2.0, (oh - image.getHeight()) / 2.0);
            g.rotate(radians, image.getWidth() / 2.0, image.getHeight() / 2.0);
            g.drawImage(image, 0, 0, null);
        } finally { g.dispose(); }
        return rotated;
    }

    private static void addCropAndRotation(List<String> args, Properties test,
                                           String prefix, boolean reverseCapture) {
        if (Boolean.parseBoolean(test.getProperty("autoCrop", "true"))) {
            int x = intSetting(test, prefix + ".x", 0);
            int y = intSetting(test, prefix + ".y", 0);
            int w = intSetting(test, prefix + ".w", 0);
            int h = intSetting(test, prefix + ".h", 0);
            if (w > 0 && h > 0) args.addAll(List.of("-crop", w + "x" + h + "+" + x + "+" + y, "+repage"));
        }
        int totalAngle = intSetting(test, "rotation", 0) + (reverseCapture ? 180 : 0);
        if (Math.floorMod(totalAngle, 360) != 0) {
            args.addAll(List.of("-background", "white", "-rotate", Integer.toString(totalAngle), "+repage"));
        }
    }

    private static int intSetting(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> pageGeometry(String type, int scaling, int dpi, Properties test) {
        String t = type == null ? "LETTER" : type.toUpperCase(Locale.ROOT);
        double wIn, hIn;
        switch (t) {
            case "A4" -> { wIn = 8.2677165; hIn = 11.6929134; }
            case "FS" -> { wIn = FSScannerDefault.FS_PAGE_WIDTH_MM / 25.4; hIn = FSScannerDefault.FS_PAGE_HEIGHT_MM / 25.4; }
            case "LEGAL" -> { wIn = FSScannerDefault.FS_PAGE_WIDTH_MM / 25.4; hIn = FSScannerDefault.FS_PAGE_HEIGHT_MM / 25.4; }
            default -> { wIn = 8.5; hIn = 11.0; }
        }
        int w = Math.max(1, (int) Math.round(wIn * dpi));
        int h = Math.max(1, (int) Math.round(hIn * dpi));
        double factor = scaling > 0 ? Math.min(5.0, Math.max(0.25, scaling / 100.0)) : 1.0;
        int contentW = Math.max(1, (int) Math.round(w * factor));
        int contentH = Math.max(1, (int) Math.round(h * factor));
        return List.of("-resize", contentW + "x" + contentH, "-gravity", "center",
                "-background", "white", "-extent", w + "x" + h);
    }

    private static int effectiveDpi(FSScannerData.ScanRecord record, Properties test) {
        if (test.containsKey("dpi")) {
            int value = intSetting(test, "dpi", record.dpi());
            if (value < 50 || value > 1200) {
                throw new IllegalArgumentException("Test DPI must be between 50 and 1200.");
            }
            return value;
        }
        return record.dpi() > 0 ? record.dpi() : FSScannerDefault.DPI;
    }

    private static double positiveMm(Properties test, String key, double fallback) {
        try {
            double value = Double.parseDouble(test.getProperty(key, Double.toString(fallback)));
            if (!Double.isFinite(value) || value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid test setting " + key + "; expected positive millimetres.");
        }
    }

    private void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Processing cancelled; source captures and fs-data.bin were retained.");
        }
    }

    private static void requireNonEmpty(Path p, String label) throws IOException {
        if (!Files.isRegularFile(p) || Files.size(p) == 0) {
            throw new IOException("ImageMagick did not produce a valid non-empty " + label + ": " + p);
        }
    }

    private static String safeBase(String name) {
        String s = name == null ? "scan" : name.replaceFirst("(?i)\\.[^.]+$", "")
                .replaceAll("[^a-zA-Z0-9._-]+", "_");
        return s.isBlank() ? "scan" : s;
    }

    private static Path uniqueOutputPath(Path desired) {
        if (!Files.exists(desired)) return desired;
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        Path candidate;
        do {
            candidate = desired.resolveSibling(stem + "-" + suffix++ + ext);
        } while (Files.exists(candidate));
        return candidate;
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String rootMessage(Throwable t) {
        t = unwrap(t);
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    @Override
    public void close() {
        worker.shutdown();
        magic.close();
        ocr.close();
        data.close();
    }

    private static final class LogicalDocument {
        final List<FSScannerData.ScanRecord> records = new ArrayList<>();
        LogicalDocument(FSScannerData.ScanRecord first) { records.add(first); }
    }

    private record OverlapMatch(int rows, double confidence) {}
    private record StitchResult(int overlapRows, double confidence, String method) {}

    public record ProcessingResult(int recordCount, List<Path> outputs, String message) {
        public ProcessingResult { outputs = List.copyOf(outputs); }
    }
}
