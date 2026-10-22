import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Versioned binary scan-session ledger stored in fs-data.bin. */
public final class FSScannerData implements AutoCloseable {
    private static final int MAGIC = 0x46534344; // FSCD
    private static final int VERSION = 5;
    private final Path file;
    private String selectedWorkflow = "";
    private int selectedDpi = FSScannerDefault.DPI, selectedQuality = FSScannerDefault.QUALITY,
            selectedScaling = FSScannerDefault.SCALING, selectedBrightness = FSScannerDefault.BRIGHTNESS,
            selectedContrast = FSScannerDefault.CONTRAST;
    private String selectedColour = FSScannerDefault.COLOUR;
    private final Properties test = new Properties();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "FSScanner-Data"); t.setDaemon(true); return t; });

    public FSScannerData() { this(Paths.get("fs-data.bin")); }
    public FSScannerData(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        try {
            Path parent = this.file.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(this.file)) writeNow(List.of());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise scan ledger at " + this.file + ": " + e.getMessage(), e);
        }
    }
    public Path getFile() { return file; }

    public CompletableFuture<Void> saveSelectionAsync(String workflow, int dpi, String colour, int quality,
            int scaling, int brightness, int contrast) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<ScanRecord> rows = readNow();
                selectedWorkflow = workflow == null ? FSScannerDefault.DEFAULT_WORKFLOW : workflow;
                selectedDpi = dpi; selectedColour = colour == null ? FSScannerDefault.COLOUR : colour;
                selectedQuality = quality; selectedScaling = scaling; selectedBrightness = brightness; selectedContrast = contrast;
                writeNow(rows);
            } catch (IOException e) { throw new CompletionException(e); }
        }, io);
    }

    public CompletableFuture<ScanRecord> recordAsync(Path image, String displayName, long sizeBytes,
            String colour, int dpi, int quality, int scaling, int brightness, int contrast, String workflow) {
        String profile = workflow == null ? FSScannerDefault.DEFAULT_WORKFLOW : workflow;
        String filenameType = scanType(image == null ? null : image.getFileName().toString());
        boolean bottom = "FSB".equals(filenameType);
        boolean top = "FST".equals(filenameType);
        String pageType = pageTypeForScanType(filenameType, profile);
        ScanRecord record = new ScanRecord(UUID.randomUUID().toString(), displayName == null ? image.getFileName().toString() : displayName,
                image.toAbsolutePath().normalize().toString(), sizeBytes, colour == null ? FSScannerDefault.COLOUR : colour,
                dpi, quality, scaling, brightness, contrast, profile, Instant.now().toEpochMilli(),
                top, bottom, bottom ? "BOTTOM_TO_TOP" : "TOP_TO_BOTTOM", pageType, false);
        return CompletableFuture.supplyAsync(() -> { try { List<ScanRecord> all = readNow(); all.add(record); writeNow(all); return record; }
            catch (IOException e) { throw new CompletionException(e); } }, io);
    }

    public CompletableFuture<Void> setLastTargetPageTypeAsync(String pageType) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<ScanRecord> all = readNow();
                if (!all.isEmpty()) {
                    int i = all.size() - 1; ScanRecord r = all.get(i);
                    if (r.hasTop() && !r.hasBottom()) all.set(i, r.withPageType(normalizePageType(pageType)));
                    writeNow(all);
                }
            } catch (IOException e) { throw new CompletionException(e); }
        }, io);
    }

    public CompletableFuture<Integer> recoverOrphanCapturesAsync(Path rawDirectory) {
        Objects.requireNonNull(rawDirectory, "rawDirectory");
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ScanRecord> all = readNow();
                Set<Path> known = new HashSet<>();
                for (ScanRecord r : all) {
                    try { known.add(r.filePath().toAbsolutePath().normalize()); }
                    catch (Exception ignored) { }
                }
                if (!Files.isDirectory(rawDirectory)) return 0;
                List<Path> candidates;
                try (var walk = Files.walk(rawDirectory)) {
                    candidates = walk.filter(Files::isRegularFile)
                            .filter(FSScannerData::isImageCapture)
                            .map(p -> p.toAbsolutePath().normalize())
                            .filter(p -> !known.contains(p))
                            .sorted(Comparator.comparingLong(FSScannerData::lastModified))
                            .toList();
                }
                int recovered = 0;
                for (Path image : candidates) {
                    String profile = selectedWorkflow == null || selectedWorkflow.isBlank()
                            ? FSScannerDefault.DEFAULT_WORKFLOW : selectedWorkflow;
                    String filenameType = scanType(image.getFileName().toString());
                    boolean bottom = "FSB".equals(filenameType);
                    boolean top = "FST".equals(filenameType);
                    ScanRecord r = new ScanRecord(UUID.randomUUID().toString(),
                            image.getFileName().toString(), image.toString(), Files.size(image),
                            selectedColour == null ? FSScannerDefault.COLOUR : selectedColour,
                            selectedDpi, selectedQuality, selectedScaling, selectedBrightness,
                            selectedContrast, profile, Files.getLastModifiedTime(image).toMillis(),
                            top, bottom, bottom ? "BOTTOM_TO_TOP" : "TOP_TO_BOTTOM",
                            pageTypeForScanType(filenameType, profile), false);
                    all.add(r);
                    known.add(image);
                    recovered++;
                }
                if (recovered > 0) writeNow(all);
                return recovered;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, io);
    }

    private static boolean isImageCapture(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".bmp") || n.endsWith(".tif") || n.endsWith(".tiff")
                || n.endsWith(".gif");
    }

    private static long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException ignored) { return Long.MAX_VALUE; }
    }

    public CompletableFuture<Void> saveTestAsync(Properties values) {
        Properties snapshot = new Properties();
        if (values != null) snapshot.putAll(values);
        return CompletableFuture.runAsync(() -> {
            try {
                List<ScanRecord> rows = readNow();
                test.clear();
                test.putAll(snapshot);
                writeNow(rows);
            } catch (IOException e) { throw new CompletionException(e); }
        }, io);
    }

    public CompletableFuture<Properties> loadTestAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                readNow();
                Properties copy = new Properties();
                copy.putAll(test);
                return copy;
            } catch (IOException e) { throw new CompletionException(e); }
        }, io);
    }

    public CompletableFuture<List<ScanRecord>> readAllAsync() {
        return CompletableFuture.supplyAsync(() -> { try { return List.copyOf(readNow()); } catch (IOException e) { throw new CompletionException(e); } }, io);
    }

    public CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(() -> { try { writeNow(List.of()); } catch (IOException e) { throw new CompletionException(e); } }, io);
    }

    /** Deletes a capture and its ledger row together so the GUI cannot leave a ghost record. */
    public CompletableFuture<Boolean> deleteRecordAsync(String recordId) {
        Objects.requireNonNull(recordId, "recordId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ScanRecord> all = readNow();
                int index = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (recordId.equals(all.get(i).id())) { index = i; break; }
                }
                if (index < 0) return false;
                ScanRecord record = all.get(index);
                Path capture = record.filePath().toAbsolutePath().normalize();
                Files.deleteIfExists(capture);
                all.remove(index);
                writeNow(all);
                return true;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, io);
    }

    public CompletableFuture<Void> markProcessedAsync(Collection<String> ids) {
        Set<String> completed = new HashSet<>(Objects.requireNonNull(ids, "ids"));
        return CompletableFuture.runAsync(() -> {
            try {
                List<ScanRecord> all = readNow();
                for (int i = 0; i < all.size(); i++) {
                    ScanRecord r = all.get(i);
                    if (completed.contains(r.id()) && !r.processed()) all.set(i, r.withProcessed(true));
                }
                writeNow(all);
            } catch (IOException e) { throw new CompletionException(e); }
        }, io);
    }

    public static String scanType(String filename) {
        if (filename == null) return "UNKNOWN";
        String name = Paths.get(filename).getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)_((?:fs_top|fs_bottom|a4fs|a4|FST|FSB|FS|A4))$").matcher(stem);
        if (!m.find()) return "UNKNOWN";
        return switch (m.group(1).toUpperCase(Locale.ROOT)) {
            case "FS_TOP", "FST" -> "FST";
            case "FS_BOTTOM", "FSB" -> "FSB";
            case "A4FS", "FS" -> "FS";
            case "A4" -> "A4";
            default -> "UNKNOWN";
        };
    }

    private static String pageTypeForScanType(String type, String profile) {
        return switch (type == null ? "UNKNOWN" : type) {
            case "FS", "FST", "FSB" -> "FS";
            case "A4" -> "A4";
            default -> targetPageType(profile);
        };
    }

    private static String targetPageType(String profile) {
        if (profile != null && profile.toLowerCase(Locale.ROOT).contains("fs")) return "FS";
        if (profile != null && profile.toLowerCase(Locale.ROOT).replace("-bottom", "").equals("a4")) return "A4";
        return "LETTER";
    }

    private static String normalizePageType(String type) {
        if (type == null) return "LETTER";
        String s = type.trim().toUpperCase(Locale.ROOT);
        return switch (s) { case "FS", "LEGAL", "A4", "LETTER" -> s; default -> "LETTER"; };
    }

    private List<ScanRecord> readNow() throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) return new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("fs-data.bin has an invalid FS Scanner signature");
            int version = in.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported fs-data.bin version: " + version);
            if (version >= 3) {
                selectedWorkflow = in.readUTF(); selectedDpi = in.readInt(); selectedColour = in.readUTF();
                selectedQuality = in.readInt(); selectedScaling = in.readInt(); selectedBrightness = in.readInt(); selectedContrast = in.readInt();
            }
            if (version >= 5) {
                int testCount = in.readInt();
                if (testCount < 0 || testCount > 1000) throw new IOException("Invalid test testing count.");
                test.clear();
                for (int i = 0; i < testCount; i++) test.setProperty(in.readUTF(), in.readUTF());
            } else test.clear();
            int count = in.readInt(); if (count < 0 || count > 1_000_000) throw new IOException("Invalid scan record count: " + count);
            List<ScanRecord> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), name = in.readUTF(), path = in.readUTF(); long size = in.readLong(); String colour = in.readUTF();
                int dpi = in.readInt(), quality = in.readInt(), scaling = in.readInt(), brightness = in.readInt(), contrast = in.readInt();
                String workflow = in.readUTF(); long timestamp = in.readLong();
                if (version == 1) {
                    String type = targetPageType(workflow);
                    rows.add(new ScanRecord(id, name, path, size, colour, dpi, quality, scaling, brightness, contrast, workflow, timestamp,
                            true, false, "TOP_TO_BOTTOM", type, false));
                } else {
                    rows.add(new ScanRecord(id, name, path, size, colour, dpi, quality, scaling, brightness, contrast, workflow, timestamp,
                            in.readBoolean(), in.readBoolean(), in.readUTF(), in.readUTF(), version >= 4 && in.readBoolean()));
                }
            }
            return rows;
        } catch (EOFException e) { throw new IOException("fs-data.bin is truncated or corrupt", e); }
    }

    private void writeNow(List<ScanRecord> records) throws IOException {
        Path parent = file.getParent(); if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent == null ? Paths.get(".").toAbsolutePath() : parent, "fsscanner-data-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.writeUTF(selectedWorkflow == null ? "" : selectedWorkflow); out.writeInt(selectedDpi);
                out.writeUTF(selectedColour == null ? FSScannerDefault.COLOUR : selectedColour); out.writeInt(selectedQuality);
                out.writeInt(selectedScaling); out.writeInt(selectedBrightness); out.writeInt(selectedContrast);
                out.writeInt(test.size());
                for (String key : new TreeSet<>(test.stringPropertyNames())) {
                    out.writeUTF(key); out.writeUTF(test.getProperty(key, ""));
                }
                out.writeInt(records.size());
                for (ScanRecord r : records) { out.writeUTF(r.id()); out.writeUTF(r.name()); out.writeUTF(r.path()); out.writeLong(r.sizeBytes());
                    out.writeUTF(r.colour()); out.writeInt(r.dpi()); out.writeInt(r.quality()); out.writeInt(r.scaling()); out.writeInt(r.brightness());
                    out.writeInt(r.contrast()); out.writeUTF(r.workflow()); out.writeLong(r.timestampEpochMillis()); out.writeBoolean(r.hasTop());
                    out.writeBoolean(r.hasBottom()); out.writeUTF(r.scanDirection()); out.writeUTF(r.pageType()); out.writeBoolean(r.processed()); }
            }
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }

    @Override public void close() { io.shutdown(); }

    public record ScanRecord(String id, String name, String path, long sizeBytes, String colour, int dpi, int quality,
            int scaling, int brightness, int contrast, String workflow, long timestampEpochMillis,
            boolean hasTop, boolean hasBottom, String scanDirection, String pageType, boolean processed) {
        public Path filePath() { return Paths.get(path); }
        public Instant timestamp() { return Instant.ofEpochMilli(timestampEpochMillis); }
        public ScanRecord withPageType(String type) { return new ScanRecord(id, name, path, sizeBytes, colour, dpi, quality, scaling,
                brightness, contrast, workflow, timestampEpochMillis, hasTop, hasBottom, scanDirection, type, processed); }
        public ScanRecord withProcessed(boolean value) { return new ScanRecord(id, name, path, sizeBytes, colour, dpi, quality, scaling, brightness, contrast, workflow, timestampEpochMillis, hasTop, hasBottom, scanDirection, pageType, value); }
    }
}