package io.github.kcsurapaneni.pulse.mount;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kcsurapaneni.pulse.core.PulseCheck;
import io.github.kcsurapaneni.pulse.mount.MountPointProperties.MountPoint;

import org.springframework.boot.health.contributor.Health;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public class MountPointCheck implements PulseCheck {

    /**
     * Lookup hook for {@link java.nio.file.FileStore#name()} — extracted as a functional interface
     * so tests can inject a deterministic resolver to simulate mount-identity changes without
     * touching the actual filesystem.
     */
    @FunctionalInterface
    interface FileStoreNameResolver {

        String nameOf(Path path) throws IOException;
    }

    private static final FileStoreNameResolver DEFAULT_RESOLVER =
            path -> Files.getFileStore(path).name();

    private static final String PROBE_PREFIX = ".pulse-probe-";

    private final MountPoint config;

    private final FileStoreNameResolver resolver;

    /**
     * File-store name captured on the first probe. Subsequent probes compare against this value;
     * a mismatch indicates the mount was swapped (vanished and got re-bound to a different
     * filesystem at the same path).
     */
    private final AtomicReference<String> fileStoreBaseline = new AtomicReference<>();

    public MountPointCheck(MountPoint config) {
        this(config, DEFAULT_RESOLVER);
    }

    MountPointCheck(MountPoint config, FileStoreNameResolver resolver) {
        this.config = config;
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public Health check() {
        Health.Builder b = new Health.Builder().withDetail("path", config.getPath());
        Path path = Path.of(config.getPath());

        if (!Files.exists(path)) {
            return b.down().withDetail("error", "path does not exist").build();
        }
        if (!Files.isDirectory(path)) {
            return b.down().withDetail("error", "path is not a directory").build();
        }
        if (!Files.isReadable(path)) {
            return b.down().withDetail("error", "path is not readable").build();
        }

        File file = path.toFile();
        long total = file.getTotalSpace();
        long free = file.getUsableSpace();
        b.withDetail("totalBytes", total).withDetail("freeBytes", free);

        if (total == 0L) {
            // Exotic filesystems (unmounted SMB, degraded FUSE) can still resolve the path but
            // report zero space. Without this guard the check would silently report UP.
            return b.down()
                    .withDetail("error", "totalBytes unavailable — likely unmounted or degraded")
                    .build();
        }

        // freePercent is emitted unconditionally now (was: only when min-free-percent was set,
        // which forced operators to configure a no-op threshold just to see the metric).
        double pct = free * 100.0 / total;
        double pctRounded = Math.round(pct * 100.0) / 100.0;
        b.withDetail("freePercent", pctRounded);

        Long minBytes = config.getMinFreeBytes();
        if (minBytes != null && free < minBytes) {
            return b.down()
                    .withDetail("threshold", "minFreeBytes=" + minBytes)
                    .withDetail("error", "free space below threshold")
                    .build();
        }

        Integer minPercent = config.getMinFreePercent();
        if (minPercent != null && pct < minPercent) {
            return b.down()
                    .withDetail("threshold", "minFreePercent=" + minPercent)
                    .withDetail("error", "free space below threshold")
                    .build();
        }

        Health identity = checkIdentity(b, path);
        if (identity != null) {
            return identity;
        }

        if (config.isRequireWritable()) {
            Health writable = checkWritable(b, path);
            if (writable != null) {
                return writable;
            }
        }

        return b.up().build();
    }

    /**
     * Captures the mount's file-store name on the first probe; on subsequent probes, compares
     * against the captured baseline. Returns a DOWN {@link Health} only on confirmed mismatch.
     * A lookup {@link IOException} surfaces as a {@code fileStoreWarn} detail but does not fail
     * the check — other checks already detect the underlying failure.
     */
    private Health checkIdentity(Health.Builder b, Path path) {
        String observed;
        try {
            observed = resolver.nameOf(path);
        }
        catch (IOException ex) {
            b.withDetail("fileStoreWarn",
                    "filestore lookup failed: " + ex.getClass().getSimpleName() + ": "
                            + ex.getMessage());
            return null;
        }
        fileStoreBaseline.compareAndSet(null, observed);
        String baseline = fileStoreBaseline.get();
        b.withDetail("fileStore", observed);
        if (!observed.equals(baseline)) {
            return b.down()
                    .withDetail("error", "mount identity changed — the mount appears to have been "
                            + "swapped since the first probe")
                    .withDetail("expectedFileStore", baseline)
                    .withDetail("actualFileStore", observed)
                    .build();
        }
        return null;
    }

    /**
     * Probe-write a tiny file under the mount point and delete it. {@link IOException} means the
     * mount can be read but not written — the failure mode {@link Files#isReadable} alone misses.
     */
    private Health checkWritable(Health.Builder b, Path path) {
        Path probe = null;
        try {
            probe = Files.createTempFile(path, PROBE_PREFIX, "");
            b.withDetail("writable", true);
            return null;
        }
        catch (IOException ex) {
            return b.down()
                    .withDetail("error", "path is not writable: "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
        finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                }
                catch (IOException ignored) {
                    // Best-effort cleanup; the probe-write itself already succeeded.
                }
            }
        }
    }
}
