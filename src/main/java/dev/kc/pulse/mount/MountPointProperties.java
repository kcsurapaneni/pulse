package dev.kc.pulse.mount;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Krishna Chaitanya Surapaneni
 */
@ConfigurationProperties("pulse.mount")
public class MountPointProperties {

    private boolean enabled;
    private List<MountPoint> points = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<MountPoint> getPoints() {
        return points;
    }

    public void setPoints(List<MountPoint> points) {
        this.points = points;
    }

    public static class MountPoint {

        private String name;
        private String path;
        private Long minFreeBytes;
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
