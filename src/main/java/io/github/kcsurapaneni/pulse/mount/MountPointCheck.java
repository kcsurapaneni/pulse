package io.github.kcsurapaneni.pulse.mount;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.kcsurapaneni.pulse.core.PulseCheck;
import io.github.kcsurapaneni.pulse.mount.MountPointProperties.MountPoint;

import org.springframework.boot.health.contributor.Health;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public class MountPointCheck implements PulseCheck {

    private final MountPoint config;

    public MountPointCheck(MountPoint config) {
        this.config = config;
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

        Long minBytes = config.getMinFreeBytes();
        if (minBytes != null && free < minBytes) {
            return b.down()
                    .withDetail("threshold", "minFreeBytes=" + minBytes)
                    .withDetail("error", "free space below threshold")
                    .build();
        }

        Integer minPercent = config.getMinFreePercent();
        if (minPercent != null) {
            double pct = free * 100.0 / total;
            b.withDetail("freePercent", Math.round(pct * 100.0) / 100.0);
            if (pct < minPercent) {
                return b.down()
                        .withDetail("threshold", "minFreePercent=" + minPercent)
                        .withDetail("error", "free space below threshold")
                        .build();
            }
        }

        return b.up().build();
    }
}
