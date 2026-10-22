import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous, real-time ImageMagick adapter for FS Scanner.
 *
 * Executes single-pass native cropping, rotation, vertical stitching (-smush / -append),
 * deskewing, and PDF rendering outside the JVM heap to prevent OutOfMemoryErrors.
 */
public final class FSScannerMagic implements AutoCloseable {
    private static final Path DEFAULT_DIRECTORY = Paths.get("bin", "ImageMagick");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final Path executable;
    private final boolean modernMagick;
    private final Duration timeout;
    private final ExecutorService workers;
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FSScannerMagic() {
        this(resolveExecutable(), DEFAULT_TIMEOUT, Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())));
    }

    public FSScannerMagic(Path executable) {
        this(executable, DEFAULT_TIMEOUT, Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())));
    }

    public FSScannerMagic(Path executable, Duration timeout, int parallelJobs) {
        Objects.requireNonNull(executable, "executable");
        this.executable = executable.toAbsolutePath().normalize();
        this.modernMagick = this.executable.getFileName() != null
                && this.executable.getFileName().toString().toLowerCase().startsWith("magick");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Timeout must be positive");
        if (parallelJobs < 1) throw new IllegalArgumentException("parallelJobs must be at least 1");
        this.workers = Executors.newFixedThreadPool(parallelJobs, r -> {
            Thread t = new Thread(r, "FSScanner-ImageMagick");
            t.setDaemon(true);
            return t;
        });
    }

    public static Path resolveExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("fsscanner.imagemagick");
        if (override != null && !override.isBlank()) candidates.add(Paths.get(override));
        candidates.add(DEFAULT_DIRECTORY.resolve(windows ? "magick.exe" : "magick"));
        candidates.add(DEFAULT_DIRECTORY.resolve(windows ? "convert.exe" : "convert"));
        candidates.add(DEFAULT_DIRECTORY.resolve(windows ? "magick" : "magick.exe"));
        for (Path p : candidates) if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        return Paths.get(windows ? "magick.exe" : "magick");
    }

    public Path getExecutablePath() { return executable; }

    public CompletableFuture<String> versionAsync() {
        return runAsync(List.of("-version"), null).thenApply(ProcessResult::output);
    }

    public CompletableFuture<ProcessResult> convertAsync(Path input, Path output) {
        return convertAsync(input, output, List.of());
    }

    public CompletableFuture<ProcessResult> convertAsync(Path input, Path output, List<String> options) {
        validateInput(input);
        validateOutput(output);
        List<String> args = new ArrayList<>();
        args.add(input.toAbsolutePath().normalize().toString());
        args.addAll(safeOptions(options));
        args.add(output.toAbsolutePath().normalize().toString());
        return runAsync(args, output);
    }

    /**
     * Real-time single-pass native ImageMagick stitch for FST (top) and FSB (bottom) captures.
     * Performs cropping, rotation (including 180-degree bottom flip), and vertical join offset
     * using ImageMagick's native parenthesis grouping and -smush operator without loading
     * uncompressed rasters into JVM heap memory.
     */
    public CompletableFuture<ProcessResult> stitchVerticalRealTimeAsync(
            Path topInput,
            Path bottomInput,
            Path output,
            List<String> topOps,
            List<String> bottomOps,
            int joinOffsetPx,
            List<String> postMergeOps) {
        validateInput(topInput);
        validateInput(bottomInput);
        validateOutput(output);

        List<String> args = new ArrayList<>();
        args.add("(");
        args.add(topInput.toAbsolutePath().normalize().toString());
        args.addAll(safeOptions(topOps));
        args.add(")");

        args.add("(");
        args.add(bottomInput.toAbsolutePath().normalize().toString());
        args.addAll(safeOptions(bottomOps));
        args.add(")");

        args.add("-background");
        args.add("white");
        args.add("-gravity");
        args.add("North");
        args.add("-smush");
        args.add(Integer.toString(joinOffsetPx));
        args.add("+repage");
        args.addAll(safeOptions(postMergeOps));
        args.add(output.toAbsolutePath().normalize().toString());

        return runAsync(args, output);
    }

    public CompletableFuture<ProcessResult> compressAsync(Path input, Path output, int quality) {
        if (quality < 1 || quality > 100) throw new IllegalArgumentException("quality must be between 1 and 100");
        String format = extension(output);
        List<String> options = new ArrayList<>(List.of("-strip"));
        if (isLossyFormat(format)) options.addAll(List.of("-quality", Integer.toString(quality)));
        else if (format.equals("png")) options.addAll(List.of("-define", "png:compression-level=9", "-define", "png:compression-filter=5"));
        else options.addAll(List.of("-quality", Integer.toString(quality)));
        return convertAsync(input, output, options);
    }

    public CompletableFuture<ProcessResult> resizeAsync(Path input, Path output, String geometry, int quality) {
        Objects.requireNonNull(geometry, "geometry");
        if (!geometry.matches("[0-9]{1,5}x[0-9]{1,5}%?|[0-9]{1,5}%")) {
            throw new IllegalArgumentException("geometry must look like 1600x1600 or 50%");
        }
        if (quality < 1 || quality > 100) throw new IllegalArgumentException("quality must be between 1 and 100");
        return convertAsync(input, output, List.of("-resize", geometry, "-quality", Integer.toString(quality), "-strip"));
    }

    public CompletableFuture<ProcessResult> cropAsync(Path input, Path output, String geometry) {
        Objects.requireNonNull(geometry, "geometry");
        if (!geometry.matches("[0-9]{1,6}x[0-9]{1,6}\\+[0-9]{1,6}\\+[0-9]{1,6}")) {
            throw new IllegalArgumentException("geometry must look like 1800x2600+100+80");
        }
        return convertAsync(input, output, List.of("-crop", geometry, "+repage"));
    }

    public CompletableFuture<String> identifyAsync(Path input) {
        validateInput(input);
        List<String> args = modernMagick
                ? List.of("identify", "-format", "%m|%w|%h|%[colorspace]|%b\\n", input.toAbsolutePath().normalize().toString())
                : List.of("-format", "%m|%w|%h|%[colorspace]|%b\\n", input.toAbsolutePath().normalize().toString());
        return runAsync(args, null).thenApply(ProcessResult::output);
    }

    public CompletableFuture<ProcessResult> executeAsync(List<String> arguments) {
        return runAsync(safeOptions(arguments), null);
    }

    /** Immediately terminates any in-flight ImageMagick child processes. */
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

    private CompletableFuture<ProcessResult> runAsync(List<String> args, Path expectedOutput) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("FSScannerMagic is closed"));
        return CompletableFuture.supplyAsync(() -> {
            Path log = null;
            Process process = null;
            try {
                List<String> command = new ArrayList<>(args.size() + 1);
                command.add(executable.toString());
                command.addAll(args);

                log = Files.createTempFile("fsscanner-imagemagick-", ".log");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.redirectOutput(log.toFile());
                process = pb.start();
                activeProcesses.add(process);

                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroy();
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    throw new IOException("ImageMagick timed out after " + timeout.toMinutes() + " minute(s)");
                }
                String output = Files.readString(log, StandardCharsets.UTF_8);
                int exit = process.exitValue();
                if (exit != 0) throw new ImageMagickException("ImageMagick failed (exit " + exit + "): " + output.trim(), exit, output);
                if (expectedOutput != null && !Files.isRegularFile(expectedOutput.toAbsolutePath().normalize())) {
                    throw new IOException("ImageMagick reported success but output file was not created: " + expectedOutput);
                }
                return new ProcessResult(command, exit, output, expectedOutput == null ? null : expectedOutput.toAbsolutePath().normalize());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) process.destroyForcibly();
                throw new RuntimeException("ImageMagick operation was interrupted", e);
            } catch (IOException e) {
                if (process != null) process.destroyForcibly();
                throw new RuntimeException("Unable to run ImageMagick at '" + executable + "': " + e.getMessage(), e);
            } finally {
                if (process != null) activeProcesses.remove(process);
                if (log != null) try { Files.deleteIfExists(log); } catch (IOException ignored) { }
            }
        }, workers);
    }

    private static List<String> safeOptions(List<String> values) {
        Objects.requireNonNull(values, "arguments");
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid null/NUL argument");
            copy.add(value);
        }
        return copy;
    }

    private static void validateInput(Path input) {
        Objects.requireNonNull(input, "input");
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Input file does not exist: " + input);
    }

    private static void validateOutput(Path output) {
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null && !Files.isDirectory(parent)) throw new IllegalArgumentException("Output directory does not exist: " + parent);
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isLossyFormat(String format) {
        return Arrays.asList("jpg", "jpeg", "webp", "avif", "heic", "heif", "jxl").contains(format);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelActiveProcesses();
            workers.shutdownNow();
        }
    }

    public record ProcessResult(List<String> command, int exitCode, String output, Path outputFile) {
        public ProcessResult {
            command = List.copyOf(command);
            output = output == null ? "" : output;
        }
    }

    public static final class ImageMagickException extends RuntimeException {
        private final int exitCode;
        private final String processOutput;
        public ImageMagickException(String message, int exitCode, String processOutput) {
            super(message);
            this.exitCode = exitCode;
            this.processOutput = processOutput == null ? "" : processOutput;
        }
        public int getExitCode() { return exitCode; }
        public String getProcessOutput() { return processOutput; }
    }
}