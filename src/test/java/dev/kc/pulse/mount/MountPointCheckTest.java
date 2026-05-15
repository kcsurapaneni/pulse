package dev.kc.pulse.mount;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.kc.pulse.mount.MountPointProperties.MountPoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Krishna Chaitanya Surapaneni
 */
class MountPointCheckTest {

    @Test
    void upWhenPathExistsAndReadable(@TempDir Path tmp) {
        Health health = checkOf("data", tmp.toString(), null, null).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("path", tmp.toString())
                .containsKey("totalBytes")
                .containsKey("freeBytes");
    }

    @Test
    void downWhenPathMissing() {
        Health health = checkOf("ghost", "/this/path/should/not/exist/anywhere", null, null).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "path does not exist");
    }

    @Test
    void downWhenPathIsAFile(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("not-a-dir.txt"));

        Health health = checkOf("file", file.toString(), null, null).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "path is not a directory");
    }

    @Test
    void downWhenFreeBytesBelowThreshold(@TempDir Path tmp) {
        long unattainable = new File(tmp.toString()).getTotalSpace() + 1L;

        Health health = checkOf("tight", tmp.toString(), unattainable, null).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("error", "free space below threshold")
                .containsEntry("threshold", "minFreeBytes=" + unattainable);
    }

    @Test
    void reportsFreePercentWhenThresholdConfigured(@TempDir Path tmp) {
        Health health = checkOf("pct", tmp.toString(), null, 0).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("freePercent");
    }

    @Test
    void downWhenFreePercentBelowThreshold(@TempDir Path tmp) {
        Health health = checkOf("pct", tmp.toString(), null, 101).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("error", "free space below threshold")
                .containsEntry("threshold", "minFreePercent=101");
    }

    private static MountPointCheck checkOf(String name, String path, Long minBytes, Integer minPercent) {
        MountPoint p = new MountPoint();
        p.setName(name);
        p.setPath(path);
        p.setMinFreeBytes(minBytes);
        p.setMinFreePercent(minPercent);
        return new MountPointCheck(p);
    }
}
