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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous hardware/scanner API bridge for FS Scanner.
 */
public final class FSScannerHW implements AutoCloseable {
    private static final Path DEFAULT_EXE =
        FSScannerPaths.bundled(
                "ScannerAPIGateway",
                "ScannerAPIGateway.exe"
        );
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final Path executable;
    private final Duration timeout;
    private final ExecutorService workers;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Process activeProcess;

    public FSScannerHW() {
        this(resolveExecutable(), DEFAULT_TIMEOUT, 2);
    }

    public FSScannerHW(Path executable) {
        this(executable, DEFAULT_TIMEOUT, 2);
    }

    public FSScannerHW(Path executable, Duration timeout, int parallelJobs) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (parallelJobs < 1) throw new IllegalArgumentException("parallelJobs must be at least 1");
        this.workers = Executors.newFixedThreadPool(parallelJobs, r -> {
            Thread t = new Thread(r, "FSScanner-HardwareGateway");
            t.setDaemon(true);
            return t;
        });
    }

    public static Path resolveExecutable() {
        String override = System.getProperty("fsscanner.gateway");
        if (override != null && !override.isBlank()) return Paths.get(override).toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return DEFAULT_EXE.toAbsolutePath().normalize();
        Path noExtension = Paths.get("bin", "ScannerAPIGateway", "ScannerAPIGateway");
        if (Files.isRegularFile(noExtension)) return noExtension.toAbsolutePath().normalize();
        return DEFAULT_EXE.toAbsolutePath().normalize();
    }

    public Path getExecutablePath() { return executable; }

    public CompletableFuture<GatewayResult> listAsync() {
        return runAsync(List.of("--list"));
    }

    public CompletableFuture<List<DeviceInfo>> listDevicesAsync() {
        return listAsync().thenApply(result -> parseDevices(result.output()));
    }

    private static List<DeviceInfo> parseDevices(String json) {
        List<DeviceInfo> devices = new ArrayList<>();
        if (json == null || json.isBlank()) return List.of();
        java.util.regex.Matcher objects = java.util.regex.Pattern
                .compile("\\{[^{}]*\\}").matcher(json);
        while (objects.find()) {
            String object = objects.group();
            String id = jsonString(object, "id");
            if (id == null || id.isBlank()) continue;
            DeviceInfo device = new DeviceInfo(id, jsonString(object, "name"),
                    jsonString(object, "description"), jsonString(object, "manufacturer"),
                    jsonString(object, "kind"), jsonString(object, "backend"),
                    jsonString(object, "model"));
            if (devices.stream().noneMatch(existing -> existing.id().equals(device.id())))
                devices.add(device);
        }
        return List.copyOf(devices);
    }

    private static String jsonString(String object, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\\\\\])*)\\\"",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(object);
        if (!m.find()) return null;
        return m.group(1).replace("\\\\\"", "\"").replace("\\\\\\\\", "\\\\").trim();
    }

    public record DeviceInfo(String id, String name, String description,
                             String manufacturer, String kind, String backend, String model) {
        public String displayName() {
            if (name != null && !name.isBlank()) return name.trim();
            if (manufacturer != null && !manufacturer.isBlank() && model != null && !model.isBlank())
                return manufacturer.trim() + " " + model.trim();
            if (model != null && !model.isBlank()) return model.trim();
            if (manufacturer != null && !manufacturer.isBlank()) return manufacturer.trim();
            return description == null || description.isBlank() ? "Scanner" : description.trim();
        }
    }

    public CompletableFuture<GatewayResult> versionAsync() {
        return runAsync(List.of("version"));
    }

    public CompletableFuture<GatewayResult> helpAsync() {
        return runAsync(List.of("help"));
    }

    public CompletableFuture<GatewayResult> scanAsync(ScanRequest request) {
        Objects.requireNonNull(request, "request");
        request.validate();
        List<String> args = new ArrayList<>();
        args.add("scan");
        args.add("--out"); args.add(request.output().toAbsolutePath().normalize().toString());
        args.add("--mode"); args.add(request.mode());
        args.add("--dpi"); args.add(Integer.toString(request.dpi()));
        args.add("--source"); args.add(request.source());
        if (request.format() != null) { args.add("--format"); args.add(request.format()); }
        if (request.quality() != null) { args.add("--quality"); args.add(request.quality().toString()); }
        if (request.pages() != null) { args.add("--pages"); args.add(request.pages().toString()); }
        if (request.timeoutSeconds() != null) { args.add("--timeout"); args.add(request.timeoutSeconds().toString()); }
        if (request.brightness() != null) { args.add("--brightness"); args.add(request.brightness().toString()); }
        if (request.contrast() != null) { args.add("--contrast"); args.add(request.contrast().toString()); }
        if (request.threshold() != null) { args.add("--threshold"); args.add(request.threshold().toString()); }
        if (request.blankSkip()) args.add("--blank-skip");
        if (request.verbose()) args.add("--verbose");
        if (request.cropArea() != null) {
            args.add("--area"); args.add(request.cropArea().leftMm() + "," + request.cropArea().topMm()
                    + "," + request.cropArea().widthMm() + "," + request.cropArea().heightMm());
        }
        return runAsync(args);
    }

    public CompletableFuture<GatewayResult> executeAsync(List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        List<String> copy = new ArrayList<>();
        for (String arg : arguments) {
            if (arg == null || arg.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid null/NUL argument");
            copy.add(arg);
        }
        return runAsync(copy);
    }

    private CompletableFuture<GatewayResult> runAsync(List<String> args) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("FSScannerHW is closed"));
        return CompletableFuture.supplyAsync(() -> {
            Path log = null;
            Process process = null;
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(args);
            try {
                if (!Files.isRegularFile(executable) && (executable.isAbsolute() || executable.toString().contains("/") || executable.toString().contains("\\"))) {
                    throw new IOException("Scanner API Gateway executable not found: " + executable);
                }
                log = Files.createTempFile("fsscanner-gateway-", ".log");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.redirectOutput(log.toFile());
                process = pb.start();
                activeProcess = process;
                boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) {
                    process.destroy();
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    throw new GatewayException("Scanner API Gateway timed out after " + timeout.toSeconds() + " seconds", -1, "");
                }
                String output = Files.readString(log, StandardCharsets.UTF_8);
                int exit = process.exitValue();
                GatewayResult result = new GatewayResult(command, exit, output);
                if (exit != 0) throw new GatewayException("Scanner API Gateway failed (exit " + exit + "): " + output.trim(), exit, output);
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) process.destroyForcibly();
                throw new GatewayException("Scanner operation interrupted", -1, e.toString());
            } catch (IOException e) {
                if (process != null) process.destroyForcibly();
                throw new GatewayException("Unable to run Scanner API Gateway at '" + executable + "': " + e.getMessage(), -1, e.toString());
            } finally {
                if (activeProcess == process) activeProcess = null;
                if (log != null) try { Files.deleteIfExists(log); } catch (IOException ignored) { }
            }
        }, workers);
    }

    public void cancelActiveScan() {
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) workers.shutdownNow();
    }

    public record GatewayResult(List<String> command, int exitCode, String output) {
        public GatewayResult {
            command = List.copyOf(command);
            output = output == null ? "" : output;
        }
        public String jsonOrText() { return output; }
    }

    public record CropArea(double leftMm, double topMm, double widthMm, double heightMm) {
        public CropArea {
            if (!Double.isFinite(leftMm) || !Double.isFinite(topMm) || !Double.isFinite(widthMm) || !Double.isFinite(heightMm)
                    || leftMm < 0 || topMm < 0 || widthMm <= 0 || heightMm <= 0) {
                throw new IllegalArgumentException("Crop area must use non-negative left/top and positive width/height in mm");
            }
        }
    }

    public record ScanRequest(
            Path output,
            String mode,
            int dpi,
            String source,
            String format,
            Integer quality,
            Integer pages,
            Integer timeoutSeconds,
            Integer brightness,
            Integer contrast,
            Integer threshold,
            boolean blankSkip,
            boolean verbose,
            CropArea cropArea) {

        public ScanRequest {
            Objects.requireNonNull(output, "output");
            mode = mode == null ? "color" : mode.toLowerCase(Locale.ROOT);
            source = source == null ? "auto" : source.toLowerCase(Locale.ROOT);
            format = format == null || format.isBlank() ? null : format.toLowerCase(Locale.ROOT);
        }

        public static ScanRequest defaults(Path output) {
            return new ScanRequest(output, "color", 300, "auto", null, null, null, null,
                    null, null, null, false, false, null);
        }

        private void validate() {
            if (!List.of("color", "gray", "bw").contains(mode)) throw new IllegalArgumentException("mode must be color, gray or bw");
            if (dpi < 75 || dpi > 1200) throw new IllegalArgumentException("dpi must be between 75 and 1200");
            if (!List.of("auto", "flatbed", "adf", "duplex").contains(source)) throw new IllegalArgumentException("source must be auto, flatbed, adf or duplex");
            if (format != null && !List.of("png", "jpeg", "jpg", "bmp", "tiff", "tif").contains(format))
                throw new IllegalArgumentException("unsupported gateway output format: " + format);
            if (quality != null && (quality < 1 || quality > 100)) throw new IllegalArgumentException("quality must be 1..100");
            if (pages != null && pages < 1) throw new IllegalArgumentException("pages must be at least 1");
            if (timeoutSeconds != null && timeoutSeconds < 1) throw new IllegalArgumentException("timeoutSeconds must be positive");
            if (brightness != null && (brightness < -100 || brightness > 100)) throw new IllegalArgumentException("brightness must be -100..100");
            if (contrast != null && (contrast < -100 || contrast > 100)) throw new IllegalArgumentException("contrast must be -100..100");
            if (threshold != null && (threshold < 0 || threshold > 100)) throw new IllegalArgumentException("threshold must be 0..100");
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null && !Files.isDirectory(parent)) throw new IllegalArgumentException("Output directory does not exist: " + parent);
        }
    }

    public static final class GatewayException extends RuntimeException {
        private final int exitCode;
        private final String processOutput;
        public GatewayException(String message, int exitCode, String processOutput) {
            super(message);
            this.exitCode = exitCode;
            this.processOutput = processOutput == null ? "" : processOutput;
        }
        public int getExitCode() { return exitCode; }
        public String getProcessOutput() { return processOutput; }
    }
}