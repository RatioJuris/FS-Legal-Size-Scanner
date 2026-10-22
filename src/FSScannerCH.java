import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.Attribute;
import javax.print.attribute.standard.PrinterLocation;
import javax.print.attribute.standard.PrinterMakeAndModel;
import javax.print.attribute.standard.PrinterName;

/**
 * Central configuration and cache handler for FS Scanner.
 *
 * Owns config.properties, cached scanner/printer metadata, and alignment values.
 * Live scanner availability is deliberately checked through FSScannerHW immediately
 * before acquisition; cached discovery data is for UI startup/display only.
 */
public final class FSScannerCH {
    private static final String DEVICE_CACHE_COUNT = "cache.scanners.count";
    private static final String PRINTER_CACHE_COUNT = "cache.printers.count";
    private final Path configFile;
    private final Properties properties = new Properties();

    public FSScannerCH(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile").toAbsolutePath().normalize();
        load();
    }

    public Path getConfigFile() { return configFile; }

    public synchronized Properties snapshot() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public synchronized String get(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }

    public synchronized void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (value == null) properties.remove(key);
        else properties.setProperty(key, value);
    }

    public synchronized void load() {
        properties.clear();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException | IllegalArgumentException e) {
                properties.clear();
            }
        }
        applyMissingDefaults();
    }

    private void applyMissingDefaults() {
        setDefaultIfMissing("scan.dpi", FSScannerDefault.DPI);
        setDefaultIfMissing("scan.colour", FSScannerDefault.COLOUR);
        setDefaultIfMissing("scan.quality", FSScannerDefault.QUALITY);
        setDefaultIfMissing("scan.scaling", FSScannerDefault.SCALING);
        setDefaultIfMissing("scan.brightness", FSScannerDefault.BRIGHTNESS);
        setDefaultIfMissing("scan.contrast", FSScannerDefault.CONTRAST);
        setDefaultIfMissing("device.default", FSScannerDefault.DEVICE);
        setDefaultIfMissing("device.default.id", FSScannerDefault.DEVICE);
        setDefaultIfMissing("scan.workflow.profile", FSScannerDefault.DEFAULT_WORKFLOW);
        setDefaultIfMissing("alignment.autoCrop", FSScannerDefault.TEST_AUTO_CROP);
        setDefaultIfMissing("alignment.top.x", FSScannerDefault.ALIGNMENT_TOP_X);
        setDefaultIfMissing("alignment.top.y", FSScannerDefault.ALIGNMENT_TOP_Y);
        setDefaultIfMissing("alignment.top.w", FSScannerDefault.ALIGNMENT_TOP_W);
        setDefaultIfMissing("alignment.top.h", FSScannerDefault.ALIGNMENT_TOP_H);
        setDefaultIfMissing("alignment.bottom.x", FSScannerDefault.ALIGNMENT_BOTTOM_X);
        setDefaultIfMissing("alignment.bottom.y", FSScannerDefault.ALIGNMENT_BOTTOM_Y);
        setDefaultIfMissing("alignment.bottom.w", FSScannerDefault.ALIGNMENT_BOTTOM_W);
        setDefaultIfMissing("alignment.bottom.h", FSScannerDefault.ALIGNMENT_BOTTOM_H);
        setDefaultIfMissing("alignment.rotation", FSScannerDefault.ALIGNMENT_ROTATION);
        setDefaultIfMissing("alignment.join.offset", FSScannerDefault.ALIGNMENT_JOIN_OFFSET);
        setDefaultIfMissing("alignment.brightness", FSScannerDefault.ALIGNMENT_BRIGHTNESS);
        setDefaultIfMissing("alignment.dpi", FSScannerDefault.ALIGNMENT_DPI);
        setDefaultIfMissing("alignment.page.width.mm", FSScannerDefault.ALIGNMENT_PAGE_WIDTH_MM);
        setDefaultIfMissing("alignment.page.height.mm", FSScannerDefault.ALIGNMENT_PAGE_HEIGHT_MM);
        setDefaultIfMissing("printer.default.name", "");
        setDefaultIfMissing("printer.default.id", "");
    }

    private void setDefaultIfMissing(String key, Object value) {
        if (!properties.containsKey(key)) properties.setProperty(key, String.valueOf(value));
    }

    public synchronized void save() throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent == null ? Path.of(".").toAbsolutePath() : parent,
                ".fsscanner-config-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(out, "FS Scanner configuration and metadata cache");
            }
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public synchronized void saveFrom(Properties values) throws IOException {
        if (values != null) {
            for (String key : values.stringPropertyNames()) {
                if (key.startsWith("cache.") || key.startsWith("alignment.") || key.startsWith("printer.")) continue;
                properties.setProperty(key, values.getProperty(key));
            }
        }
        applyMissingDefaults();
        save();
    }

    public synchronized void restoreDefaults() throws IOException {
        properties.clear();
        applyMissingDefaults();
        properties.setProperty("scan.dpi", Integer.toString(FSScannerDefault.DPI));
        properties.setProperty("scan.colour", FSScannerDefault.COLOUR);
        properties.setProperty("scan.quality", Integer.toString(FSScannerDefault.QUALITY));
        properties.setProperty("scan.scaling", Integer.toString(FSScannerDefault.SCALING));
        properties.setProperty("scan.brightness", Integer.toString(FSScannerDefault.BRIGHTNESS));
        properties.setProperty("scan.contrast", Integer.toString(FSScannerDefault.CONTRAST));
        properties.setProperty("device.default", FSScannerDefault.DEVICE);
        properties.setProperty("device.default.id", FSScannerDefault.DEVICE);
        properties.setProperty("printer.default.name", "");
        properties.setProperty("printer.default.id", "");
        properties.setProperty("alignment.page.width.mm", Double.toString(FSScannerDefault.ALIGNMENT_PAGE_WIDTH_MM));
        properties.setProperty("alignment.page.height.mm", Double.toString(FSScannerDefault.ALIGNMENT_PAGE_HEIGHT_MM));
        properties.setProperty("alignment.top.x", Integer.toString(FSScannerDefault.ALIGNMENT_TOP_X));
        properties.setProperty("alignment.top.y", Integer.toString(FSScannerDefault.ALIGNMENT_TOP_Y));
        properties.setProperty("alignment.top.w", Integer.toString(FSScannerDefault.ALIGNMENT_TOP_W));
        properties.setProperty("alignment.top.h", Integer.toString(FSScannerDefault.ALIGNMENT_TOP_H));
        properties.setProperty("alignment.bottom.x", Integer.toString(FSScannerDefault.ALIGNMENT_BOTTOM_X));
        properties.setProperty("alignment.bottom.y", Integer.toString(FSScannerDefault.ALIGNMENT_BOTTOM_Y));
        properties.setProperty("alignment.bottom.w", Integer.toString(FSScannerDefault.ALIGNMENT_BOTTOM_W));
        properties.setProperty("alignment.bottom.h", Integer.toString(FSScannerDefault.ALIGNMENT_BOTTOM_H));
        properties.setProperty("alignment.rotation", Integer.toString(FSScannerDefault.ALIGNMENT_ROTATION));
        properties.setProperty("alignment.join.offset", Integer.toString(FSScannerDefault.ALIGNMENT_JOIN_OFFSET));
        properties.setProperty("alignment.brightness", Integer.toString(FSScannerDefault.ALIGNMENT_BRIGHTNESS));
        properties.setProperty("alignment.dpi", Integer.toString(FSScannerDefault.ALIGNMENT_DPI));
        properties.setProperty("alignment.autoCrop", Boolean.toString(FSScannerDefault.TEST_AUTO_CROP));
        clearCacheKeys("cache.scanners.");
        clearCacheKeys("cache.printers.");
        save();
    }

    private void clearCacheKeys(String prefix) {
        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) properties.remove(key);
        }
    }

    public synchronized void cacheScanners(List<FSScannerHW.DeviceInfo> devices) throws IOException {
        clearCacheKeys("cache.scanners.");
        List<FSScannerHW.DeviceInfo> safe = devices == null ? List.of() : devices;
        properties.setProperty(DEVICE_CACHE_COUNT, Integer.toString(safe.size()));
        for (int i = 0; i < safe.size(); i++) {
            FSScannerHW.DeviceInfo d = safe.get(i);
            String p = "cache.scanners." + i + ".";
            setIfNotNull(p + "id", d.id());
            setIfNotNull(p + "name", d.name());
            setIfNotNull(p + "description", d.description());
            setIfNotNull(p + "manufacturer", d.manufacturer());
            setIfNotNull(p + "kind", d.kind());
            setIfNotNull(p + "backend", d.backend());
            setIfNotNull(p + "model", d.model());
        }
        properties.setProperty("cache.scanners.updatedAt", Long.toString(System.currentTimeMillis()));
        save();
    }

    public synchronized List<FSScannerHW.DeviceInfo> cachedScanners() {
        int count = parseCount(properties.getProperty(DEVICE_CACHE_COUNT));
        List<FSScannerHW.DeviceInfo> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String p = "cache.scanners." + i + ".";
            String id = properties.getProperty(p + "id", "");
            if (id.isBlank()) continue;
            result.add(new FSScannerHW.DeviceInfo(id, properties.getProperty(p + "name"),
                    properties.getProperty(p + "description"), properties.getProperty(p + "manufacturer"),
                    properties.getProperty(p + "kind"), properties.getProperty(p + "backend"),
                    properties.getProperty(p + "model")));
        }
        return List.copyOf(result);
    }

    public synchronized void cachePrinters(List<PrinterInfo> printers) throws IOException {
        clearCacheKeys("cache.printers.");
        List<PrinterInfo> safe = printers == null ? List.of() : printers;
        properties.setProperty(PRINTER_CACHE_COUNT, Integer.toString(safe.size()));
        for (int i = 0; i < safe.size(); i++) {
            PrinterInfo printer = safe.get(i);
            String p = "cache.printers." + i + ".";
            setIfNotNull(p + "id", printer.id());
            setIfNotNull(p + "name", printer.name());
            setIfNotNull(p + "driver", printer.driver());
            setIfNotNull(p + "port", printer.port());
        }
        properties.setProperty("cache.printers.updatedAt", Long.toString(System.currentTimeMillis()));
        save();
    }

    public synchronized List<PrinterInfo> cachedPrinters() {
        int count = parseCount(properties.getProperty(PRINTER_CACHE_COUNT));
        List<PrinterInfo> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String p = "cache.printers." + i + ".";
            String id = properties.getProperty(p + "id", "");
            if (!id.isBlank()) result.add(new PrinterInfo(id, properties.getProperty(p + "name"),
                    properties.getProperty(p + "driver"), properties.getProperty(p + "port")));
        }
        return List.copyOf(result);
    }

    public synchronized boolean printerCacheInitialized() {
        return Boolean.parseBoolean(properties.getProperty("cache.printers.initialized", "false"));
    }

    public synchronized List<PrinterInfo> refreshPrinterCacheFromSystem() throws IOException {
        List<PrinterInfo> found = new ArrayList<>();
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            String name = service.getName();
            if (name == null || name.isBlank()) continue;
            String driver = "";
            String port = "";
            try {
                Attribute model = service.getAttribute(PrinterMakeAndModel.class);
                if (model != null) driver = model.toString();
                Attribute location = service.getAttribute(PrinterLocation.class);
                if (location != null) port = location.toString();
                Attribute printerName = service.getAttribute(PrinterName.class);
                if (printerName != null) name = printerName.toString();
            } catch (RuntimeException ignored) { }
            found.add(new PrinterInfo(service.getName(), name, driver, port));
        }
        cachePrinters(found);
        properties.setProperty("cache.printers.initialized", "true");
        save();
        return List.copyOf(found);
    }

    public synchronized String alignment(String key, String fallback) {
        return properties.getProperty("alignment." + key, fallback);
    }

    public synchronized void setAlignment(String key, String value) {
        properties.setProperty("alignment." + key, value == null ? "0" : value);
    }

    private void setIfNotNull(String key, String value) {
        if (value != null) properties.setProperty(key, value);
    }

    private static int parseCount(String value) {
        try { return Math.max(0, Math.min(1000, Integer.parseInt(value == null ? "0" : value))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    public record PrinterInfo(String id, String name, String driver, String port) { }
}