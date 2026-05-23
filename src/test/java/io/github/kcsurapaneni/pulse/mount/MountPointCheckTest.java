package io.github.kcsurapaneni.pulse.mount;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kcsurapaneni.pulse.mount.MountPointProperties.MountPoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    void freePercentEmittedEvenWithoutThreshold(@TempDir Path tmp) {
        // Previously freePercent was only emitted when min-free-percent was explicitly set —
        // operators had to configure `min-free-percent: 0` as a no-op just to see the metric on
        // dashboards. Now it's emitted unconditionally.
        Health health = checkOf("plain", tmp.toString(), null, null).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsKey("freePercent")
                .containsKey("totalBytes")
                .containsKey("freeBytes");
    }

    @Test
    void downWhenFreePercentBelowThreshold(@TempDir Path tmp) {
        Health health = checkOf("pct", tmp.toString(), null, 101).check();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("error", "free space below threshold")
                .containsEntry("threshold", "minFreePercent=101");
    }

    // ---------- require-writable ----------

    @Test
    void requireWritableUpOnWritableDir(@TempDir Path tmp) {
        MountPoint p = mountPoint("rw", tmp.toString(), null, null);
        p.setRequireWritable(true);

        Health health = new MountPointCheck(p).check();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("writable", true);
        // The probe-write should clean up after itself — no leftover files.
        assertThat(tmp.toFile().listFiles()).isEmpty();
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void requireWritableDownOnReadOnlyDir(@TempDir Path tmp) throws IOException {
        // chmod a-w to make the dir read-only. Skip on Windows where POSIX bits don't apply.
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            MountPoint p = mountPoint("ro", tmp.toString(), null, null);
            p.setRequireWritable(true);

            Health health = new MountPointCheck(p).check();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat((String) health.getDetails().get("error"))
                    .startsWith("path is not writable:");
        }
        finally {
            // Restore so @TempDir can clean up.
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }

    // ---------- mount-identity detection ----------

    @Test
    void identityBaselineCapturedOnFirstProbe(@TempDir Path tmp) {
        AtomicReference<String> resolverReturns = new AtomicReference<>("/dev/test1");
        MountPointCheck check = new MountPointCheck(
                mountPoint("identity", tmp.toString(), null, null),
                p -> resolverReturns.get());

        Health first = check.check();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(first.getDetails()).containsEntry("fileStore", "/dev/test1");
    }

    @Test
    void identityChangeReportedAsDown(@TempDir Path tmp) {
        AtomicReference<String> resolverReturns = new AtomicReference<>("/dev/test1");
        MountPointCheck check = new MountPointCheck(
                mountPoint("identity", tmp.toString(), null, null),
                p -> resolverReturns.get());

        Health first = check.check();
        resolverReturns.set("/dev/test2"); // simulate mount swap
        Health second = check.check();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getStatus()).isEqualTo(Status.DOWN);
        assertThat(second.getDetails())
                .containsEntry("expectedFileStore", "/dev/test1")
                .containsEntry("actualFileStore", "/dev/test2");
        assertThat((String) second.getDetails().get("error"))
                .startsWith("mount identity changed");
    }

    @Test
    void identityLookupIoExceptionIsNonFatal(@TempDir Path tmp) {
        MountPointCheck check = new MountPointCheck(
                mountPoint("identity", tmp.toString(), null, null),
                p -> {
                    throw new IOException("simulated filestore lookup failure");
                });

        Health health = check.check();

        // Other checks succeed; identity lookup is best-effort — surface the warning, don't fail.
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat((String) health.getDetails().get("fileStoreWarn"))
                .contains("filestore lookup failed")
                .contains("simulated filestore lookup failure");
        assertThat(health.getDetails()).doesNotContainKey("fileStore");
    }

    // ---------- helpers ----------

    private static MountPointCheck checkOf(String name, String path, Long minBytes, Integer minPercent) {
        return new MountPointCheck(mountPoint(name, path, minBytes, minPercent));
    }

    private static MountPoint mountPoint(String name, String path, Long minBytes, Integer minPercent) {
        MountPoint p = new MountPoint();
        p.setName(name);
        p.setPath(path);
        p.setMinFreeBytes(minBytes);
        p.setMinFreePercent(minPercent);
        return p;
    }
}
