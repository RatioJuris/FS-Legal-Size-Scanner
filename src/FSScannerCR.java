import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous OCR API bridge for FS Scanner using the bundled Tesseract CLI.
 * Default Windows path: bin\\tesseract\\tesseract.exe
 */
public final class FSScannerCR implements AutoCloseable {
    private static final Path DEFAULT_EXE =
        FSScannerPaths.bundled("tesseract", "tesseract.exe");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Path executable;
    private final Duration timeout;
    private final ExecutorService workers;
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FSScannerCR() {
        this(resolveExecutable(), DEFAULT_TIMEOUT, Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
    }

    public FSScannerCR(Path executable) { this(executable, DEFAULT_TIMEOUT, 2); }

    public FSScannerCR(Path executable, Duration timeout, int parallelJobs) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (parallelJobs < 1) throw new IllegalArgumentException("parallelJobs must be at least 1");
        this.workers = Executors.newFixedThreadPool(parallelJobs, r -> {
            Thread t = new Thread(r, "FSScanner-OCR"); t.setDaemon(true); return t;
        });
    }

    public static Path resolveExecutable() {
        String override = System.getProperty("fsscanner.tesseract");
        if (override != null && !override.isBlank()) return Paths.get(override).toAbsolutePath().normalize();
        Path bundled = DEFAULT_EXE.toAbsolutePath().normalize();
        if (Files.isRegularFile(bundled)) return bundled;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path extensionless = FSScannerPaths.bundled(
        "tesseract",
        os.contains("win") ? "tesseract.exe" : "tesseract"
		);
		return extensionless.toAbsolutePath().normalize();
    }

    public Path getExecutablePath() { return executable; }

    public CompletableFuture<String> versionAsync() {
        return submit(() -> run(List.of("--version"), null).output());
    }

    public CompletableFuture<String> listLanguagesAsync() {
        return submit(() -> run(List.of("--list-langs"), null).output());
    }

    public CompletableFuture<String> recognizeAsync(Path input, OcrOptions options) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(options, "options");
        return submit(() -> {
            Path source = requireInput(input);
            Path base = Files.createTempFile("fsscanner-ocr-", "");
            Files.deleteIfExists(base);
            Path txt = Path.of(base.toString() + ".txt");
            try {
                List<String> args = baseArguments(source, base, options);
                args.add("txt");
                run(args, null);
                if (!Files.isRegularFile(txt)) throw new IOException("Tesseract completed without producing a text output file.");
                return Files.readString(txt, StandardCharsets.UTF_8);
            } finally { Files.deleteIfExists(txt); }
        });
    }

    public CompletableFuture<OcrResult> recognizeToFileAsync(Path input, Path output, OcrOptions options, OutputFormat format) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options"); Objects.requireNonNull(format, "format");
        return submit(() -> {
            Path source = requireInput(input);
            Path target = output.toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            String fileName = target.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            String expectedSuffix = "." + format.extension;
            Path base = target;
            if (lower.endsWith(expectedSuffix)) base = target.resolveSibling(fileName.substring(0, fileName.length() - expectedSuffix.length()));
            List<String> args = baseArguments(source, base, options);
            args.add(format.configName);
            ProcessResult process = run(args, null);
            Path actual = Path.of(base.toString() + expectedSuffix);
            if (!Files.isRegularFile(actual)) throw new IOException("Tesseract exited successfully but output was not found: " + actual + "\n" + process.output());
            return new OcrResult(source, actual, format, process.exitCode(), Files.size(actual), process.output());
        });
    }

    public void cancelActiveProcesses() {
        for (Process p : activeProcesses) {
            if (p != null && p.isAlive()) {
                p.destroy();
                try {
                    if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
        }
    }

    private List<String> baseArguments(Path input, Path outputBase, OcrOptions options) {
        List<String> args = new ArrayList<>();
        args.add(input.toString()); args.add(outputBase.toString());
        args.add("-l"); args.add(options.language());
        args.add("--psm"); args.add(Integer.toString(options.pageSegmentationMode()));
        if (options.engineMode() != null) { args.add("--oem"); args.add(Integer.toString(options.engineMode())); }
        if (options.dpi() != null) { args.add("--dpi"); args.add(Integer.toString(options.dpi())); }
        for (String config : options.extraConfigFiles()) args.add(config);
        return args;
    }

    private Path requireInput(Path input) throws IOException {
        Path p = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IOException("Input file does not exist or is not a regular file: " + p);
        if (!Files.isReadable(p)) throw new IOException("Input file is not readable: " + p);
        return p;
    }

    private ProcessResult run(List<String> args, Path workingDirectory) throws IOException, InterruptedException {
        if (!Files.isRegularFile(executable)) throw new IOException("Tesseract executable not found: " + executable);
        Path log = Files.createTempFile("fsscanner-tesseract-", ".log");
        Process process = null;
        try {
            List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (workingDirectory != null) builder.directory(workingDirectory.toFile());
            process = builder.start();
            activeProcesses.add(process);
            boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) { process.destroyForcibly(); process.waitFor(); throw new IOException("Tesseract timed out after " + timeout); }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            int exit = process.exitValue();
            if (exit != 0) throw new IOException("Tesseract failed with exit code " + exit + ":\n" + output);
            return new ProcessResult(exit, output);
        } finally {
            if (process != null) {
                activeProcesses.remove(process);
                if (process.isAlive()) process.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }

    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("FSScannerCR is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try { return action.get(); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, workers);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelActiveProcesses();
            workers.shutdownNow();
        }
    }

    public enum OutputFormat {
        TEXT("txt", "txt"), PDF("pdf", "pdf"), HOCR("hocr", "hocr"), TSV("tsv", "tsv");
        private final String extension, configName;
        OutputFormat(String extension, String configName) { this.extension = extension; this.configName = configName; }
        public String extension() { return extension; }
    }

    public record OcrOptions(String language, int pageSegmentationMode, Integer engineMode, Integer dpi,
                             List<String> extraConfigFiles) {
        public OcrOptions {
            language = (language == null || language.isBlank()) ? "eng" : language.trim();
            if (pageSegmentationMode < 0 || pageSegmentationMode > 13) throw new IllegalArgumentException("PSM must be between 0 and 13");
            if (engineMode != null && (engineMode < 0 || engineMode > 3)) throw new IllegalArgumentException("OEM must be between 0 and 3");
            if (dpi != null && (dpi < 70 || dpi > 2400)) throw new IllegalArgumentException("DPI must be between 70 and 2400");
            extraConfigFiles = extraConfigFiles == null ? List.of() : List.copyOf(extraConfigFiles);
            for (String s : extraConfigFiles) if (s == null || s.isBlank()) throw new IllegalArgumentException("Extra config filenames must not be blank");
        }
        public static OcrOptions defaults() { return new OcrOptions("eng", 3, null, null, List.of()); }
        public OcrOptions withLanguage(String value) { return new OcrOptions(value, pageSegmentationMode, engineMode, dpi, extraConfigFiles); }
        public OcrOptions withDpi(Integer value) { return new OcrOptions(language, pageSegmentationMode, engineMode, value, extraConfigFiles); }
    }

    public record OcrResult(Path inputFile, Path outputFile, OutputFormat format, int exitCode, long outputBytes, String diagnosticOutput) { }
    private record ProcessResult(int exitCode, String output) { }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
}