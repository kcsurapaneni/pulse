package dev.kc.pulse.mount;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.mount")
public class MountPointProperties {

    /**
     * Master switch for the mount-point check. Defaults to {@code false} so the check
     * only registers when explicitly enabled.
     */
    private boolean enabled;

    /**
     * K8s probe groups the {@code mount} composite participates in. Default {@code [readiness]} —
     * a missing or degraded mount drops the pod from the LB without restarting it. Add
     * {@code "liveness"} to also fail liveness when this is a genuinely pod-fatal condition.
     */
    private List<String> probes = new ArrayList<>(List.of("readiness"));

    /**
     * Mount points to check. Each entry becomes a sub-contributor under
     * {@code /actuator/health/mount/<name>}.
     */
    private List<MountPoint> points = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getProbes() {
        return probes;
    }

    public void setProbes(List<String> probes) {
        this.probes = probes;
    }

    public List<MountPoint> getPoints() {
        return points;
    }

    public void setPoints(List<MountPoint> points) {
        this.points = points;
    }

    /**
     * Configuration for a single mount point.
     */
    public static class MountPoint {

        /**
         * Component key under {@code mount.<name>} in {@code /actuator/health}. Must be non-blank
         * and must not contain {@code '/'}.
         */
        private String name;

        /**
         * Filesystem path to check. UNC paths are supported on Windows.
         */
        private String path;

        /**
         * Minimum free bytes threshold. When set, the check reports {@code DOWN} if the path's
         * usable space falls below this value. Leave unset to skip the byte-level threshold.
         */
        private Long minFreeBytes;

        /**
         * Minimum free percent threshold (0–100). When set, the check reports {@code DOWN} if the
         * path's usable space drops below this fraction of total space. Leave unset to skip the
         * percent threshold.
         */
        private Integer minFreePercent;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Long getMinFreeBytes() {
            return minFreeBytes;
        }

        public void setMinFreeBytes(Long minFreeBytes) {
            this.minFreeBytes = minFreeBytes;
        }

        public Integer getMinFreePercent() {
            return minFreePercent;
        }

        public void setMinFreePercent(Integer minFreePercent) {
            this.minFreePercent = minFreePercent;
        }
    }
}
