
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FSScannerPaths {
    private FSScannerPaths() {}

    public static Path applicationDirectory() {
        String override = System.getProperty("fsscanner.appdir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        try {
            URI location = FSScannerPaths.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();

            Path codePath = Paths.get(location).toAbsolutePath().normalize();
            Path start = Files.isDirectory(codePath)
                    ? codePath
                    : codePath.getParent();

            if (start != null) {
                Path current = start;
                for (int i = 0; current != null && i < 8; i++) {
                    if (Files.isDirectory(current.resolve("bin"))) {
                        return current;
                    }
                    current = current.getParent();
                }
            }
        } catch (Exception ignored) {
            // Fall back to the working directory below.
        }

        return Paths.get("").toAbsolutePath().normalize();
    }

    public static Path bundled(String... components) {
        Path root = applicationDirectory();
        Path candidate = root.resolve("bin");

        for (String component : components) {
            candidate = candidate.resolve(component);
        }

        return candidate.normalize();
    }

    public static Path resolveBundledOrFallback(
            String propertyName,
            String... components) {

        String override = System.getProperty(propertyName);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        Path candidate = bundled(components);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }

        return candidate;
    }

    public static Path configurationFile() {
        String localAppData = System.getenv("LOCALAPPDATA");

        Path base = localAppData == null || localAppData.isBlank()
                ? Paths.get(System.getProperty("user.home"), "AppData", "Local")
                : Paths.get(localAppData);

        return base.resolve("RatioJuris")
                .resolve("FS Legal Size Scanner")
                .resolve("config.properties")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Returns the writable per-user workspace for captures and processing.
     */
    public static Path workspaceDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");

        Path base = localAppData == null || localAppData.isBlank()
                ? Paths.get(System.getProperty("user.home"), "AppData", "Local")
                : Paths.get(localAppData);

        return base.resolve("RatioJuris")
                .resolve("FSScanner")
                .resolve(".tmp")
                .toAbsolutePath()
                .normalize();
    }
}