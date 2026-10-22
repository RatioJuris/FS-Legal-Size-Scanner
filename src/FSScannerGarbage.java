import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous file-management API for FS Scanner.
 */
public final class FSScannerGarbage implements AutoCloseable {
    private final ExecutorService workers;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FSScannerGarbage() {
        this(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    public FSScannerGarbage(int parallelJobs) {
        if (parallelJobs < 1) throw new IllegalArgumentException("parallelJobs must be at least 1");
        workers = Executors.newFixedThreadPool(parallelJobs, r -> {
            Thread t = new Thread(r, "FSScanner-FileManager"); t.setDaemon(true); return t;
        });
    }

    public CompletableFuture<FileOperationResult> copyAsync(Path source, Path destination, boolean overwrite) {
        return submit(() -> {
            Path src = requireFile(source); Path dst = normalize(destination);
            if (src.equals(dst)) throw new IOException("Source and destination are the same file.");
            ensureParent(dst);
            CopyOption[] options = overwrite
                    ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES}
                    : new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};
            Files.copy(src, dst, options);
            return result("copy", src, dst, Files.size(dst));
        });
    }

    public CompletableFuture<FileOperationResult> cutAsync(Path source, Path destination, boolean overwrite) {
        return submit(() -> {
            Path src = requireFile(source); Path dst = normalize(destination);
            if (src.equals(dst)) throw new IOException("Source and destination are the same file.");
            ensureParent(dst);
            if (Files.exists(dst) && !overwrite) throw new IOException("Destination already exists: " + dst);
            Path staging = Files.createTempFile(dst.getParent(), ".fsscanner-cut-", ".tmp");
            try {
                Files.copy(src, staging, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (overwrite) Files.move(staging, dst, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(staging, dst);
                long bytes = Files.size(dst);
                Files.delete(src);
                return result("cut", src, dst, bytes);
            } finally { Files.deleteIfExists(staging); }
        });
    }

    public CompletableFuture<FileOperationResult> moveAsync(Path source, Path destination, boolean overwrite) {
        return submit(() -> {
            Path src = requireFile(source); Path dst = normalize(destination);
            if (src.equals(dst)) return result("move-noop", src, dst, Files.size(src));
            ensureParent(dst);
            try {
                if (overwrite) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                else Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                if (overwrite) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(src, dst);
            }
            return result("move", src, dst, Files.size(dst));
        });
    }

    public CompletableFuture<FileOperationResult> deleteAsync(Path target) {
        return submit(() -> {
            Path p = normalize(target);
            if (!Files.exists(p)) throw new IOException("File does not exist: " + p);
            if (!Files.isRegularFile(p)) throw new IOException("Only regular files may be deleted by deleteAsync: " + p);
            long bytes = Files.size(p);
            Files.delete(p);
            return result("delete", p, null, bytes);
        });
    }

    public CompletableFuture<FileOperationResult> replaceBytesAsync(Path target, byte[] content) {
        Objects.requireNonNull(content, "content");
        byte[] snapshot = content.clone();
        return submit(() -> {
            Path p = normalize(target); ensureParent(p);
            Path temp = Files.createTempFile(p.getParent(), ".fsscanner-replace-", ".tmp");
            try {
                Files.write(temp, snapshot, StandardOpenOption.TRUNCATE_EXISTING);
                try { Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING); }
                return result("replace", p, p, Files.size(p));
            } finally { Files.deleteIfExists(temp); }
        });
    }

    public CompletableFuture<FileOperationResult> replaceTextAsync(Path target, String content) {
        Objects.requireNonNull(content, "content");
        return replaceBytesAsync(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public CompletableFuture<FileOperationResult> amendTextAsync(Path target, String find, String replacement,
                                                                  boolean appendIfFindMissing) {
        Objects.requireNonNull(replacement, "replacement");
        return submit(() -> {
            Path p = requireFile(target);
            String original = Files.readString(p, StandardCharsets.UTF_8);
            String amended;
            if (find == null || find.isEmpty()) {
                amended = original + replacement;
            } else if (original.contains(find)) {
                amended = original.replace(find, replacement);
            } else if (appendIfFindMissing) {
                amended = original + replacement;
            } else {
                throw new IOException("Requested text to amend was not found in: " + p);
            }
            writeAtomic(p, amended.getBytes(StandardCharsets.UTF_8));
            return result("amend-text", p, p, Files.size(p));
        });
    }

    public CompletableFuture<FileOperationResult> renameAsync(Path source, String newFileName, boolean overwrite) {
        Objects.requireNonNull(newFileName, "newFileName");
        if (newFileName.isBlank() || newFileName.contains("/") || newFileName.contains("\\") || newFileName.equals(".") || newFileName.equals(".."))
            return CompletableFuture.failedFuture(new IllegalArgumentException("newFileName must be a plain filename."));
        Path src = normalize(source);
        return moveAsync(src, src.resolveSibling(newFileName), overwrite).thenApply(r -> new FileOperationResult("rename", r.source(), r.destination(), r.bytes(), r.completedAt()));
    }

    public CompletableFuture<FileInfo> inspectAsync(Path target) {
        return submit(() -> {
            Path p = normalize(target);
            if (!Files.exists(p)) throw new IOException("Path does not exist: " + p);
            return new FileInfo(p, Files.isDirectory(p), Files.size(p), Files.isReadable(p), Files.isWritable(p), Files.getLastModifiedTime(p).toInstant());
        });
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".fsscanner-amend-", ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static Path requireFile(Path p) throws IOException {
        Path n = normalize(p);
        if (!Files.isRegularFile(n)) throw new IOException("Not a regular file: " + n);
        if (!Files.isReadable(n)) throw new IOException("File is not readable: " + n);
        return n;
    }
    private static Path normalize(Path p) { return Objects.requireNonNull(p, "path").toAbsolutePath().normalize(); }
    private static void ensureParent(Path p) throws IOException { Path parent = p.getParent(); if (parent != null) Files.createDirectories(parent); }
    private static FileOperationResult result(String op, Path src, Path dst, long bytes) { return new FileOperationResult(op, src, dst, bytes, Instant.now()); }

    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("FSScannerGarbage is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try { return action.get(); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, workers);
    }

    @Override public void close() { if (closed.compareAndSet(false, true)) workers.shutdownNow(); }

    public record FileOperationResult(String operation, Path source, Path destination, long bytes, Instant completedAt) { }
    public record FileInfo(Path path, boolean directory, long sizeBytes, boolean readable, boolean writable, Instant modifiedAt) { }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
}