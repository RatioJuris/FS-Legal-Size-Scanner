import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Bulk PDF assembler. It combines the individual PDFs produced by the existing
 * processor into one ordered multipage PDF. Single-file printing remains separate.
 */
public final class FSScannerBulkProc {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private final FSScannerMagic magic;

    public FSScannerBulkProc(FSScannerMagic magic) {
        this.magic = Objects.requireNonNull(magic, "magic");
    }

    /**
     * Merge all staged per-document PDFs in a directory. The generated bulk PDF is
     * excluded from future inputs. Source PDFs are removed only after successful merge.
     */
    public CompletableFuture<Path> mergeDirectoryAsync(Path directory) {
        return mergeDirectoryAsync(directory, FSScannerDefault.QUALITY);
    }

    /** Merge using the user's configured PDF image quality (1..100). */
    public CompletableFuture<Path> mergeDirectoryAsync(Path directory, int quality) {
        Objects.requireNonNull(directory, "directory");
        if (quality < 1 || quality > 100) throw new IllegalArgumentException("PDF quality must be 1..100.");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path dir = directory.toAbsolutePath().normalize();
                Files.createDirectories(dir);
                List<Path> individualPdfs;
                List<Path> existingBulkPdfs;
                try (Stream<Path> files = Files.list(dir)) {
                    List<Path> pdfs = files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                            .filter(p -> !p.getFileName().toString().startsWith(".fsscanner-stage-"))
                            .toList();
                    existingBulkPdfs = pdfs.stream()
                            .filter(p -> p.getFileName().toString().startsWith(FSScannerDefault.BULK_PDF_PREFIX))
                            .sorted(Comparator.<Path, String>comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).reversed())
                            .toList();
                    individualPdfs = pdfs.stream()
                            .filter(p -> !p.getFileName().toString().startsWith(FSScannerDefault.BULK_PDF_PREFIX))
                            .sorted(Comparator.<Path, String>comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                            .toList();
                }
                if (individualPdfs.isEmpty()) {
                    if (!existingBulkPdfs.isEmpty()) return existingBulkPdfs.get(0);
                    throw new IOException("No PDFs are available for bulk merging.");
                }
                List<Path> inputs = new ArrayList<>();
                // Preserve pages already bulk-merged earlier, then append newly processed PDFs.
                if (!existingBulkPdfs.isEmpty()) inputs.add(existingBulkPdfs.get(0));
                inputs.addAll(individualPdfs);
                for (Path input : inputs) {
                    if (!Files.isRegularFile(input) || Files.size(input) == 0)
                        throw new IOException("A source PDF is missing or empty: " + input.getFileName());
                }
                Path output = dir.resolve(FSScannerDefault.BULK_PDF_PREFIX
                        + LocalDateTime.now().format(STAMP) + ".pdf");
                List<String> args = new ArrayList<>();
                for (Path input : inputs) args.add(input.toAbsolutePath().normalize().toString());
                // Place output settings after all inputs so they govern PDF encoding,
                // not input decoding. JPEG compression uses the configured UI quality.
                args.add("-compress");
                args.add("JPEG");
                args.add("-quality");
                args.add(Integer.toString(quality));
                args.add("-adjoin");
                args.add(output.toAbsolutePath().normalize().toString());
                magic.executeAsync(args).join();
                if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                    Files.deleteIfExists(output);
                    throw new IOException("ImageMagick did not produce a non-empty bulk PDF.");
                }
                // Keep the originals if any cleanup fails; the combined PDF is already safe.
                for (Path input : individualPdfs) {
                    try { Files.deleteIfExists(input); } catch (IOException ignored) { }
                }
                for (Path oldBulk : existingBulkPdfs) {
                    try { Files.deleteIfExists(oldBulk); } catch (IOException ignored) { }
                }
                return output;
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null && cause instanceof java.util.concurrent.CompletionException) cause = cause.getCause();
                throw new java.util.concurrent.CompletionException(cause);
            }
        });
    }
}
