package io.github.kcsurapaneni.pulse.oauth2;

import java.time.Duration;
import java.time.Instant;

/**
 * @author Krishna Chaitanya Surapaneni
 */
public final class OAuth2TokenCache {

    /** Refresh point as a percentage of the effective token lifetime. */
    private static final long REFRESH_AT_PERCENT = 80L;

    private final Object lock = new Object();
    private Instant cachedAt;
    private Instant refreshAt;
    private String tokenType;
    private int expiresInSec;

    public boolean isFresh(Instant now) {
        synchronized (lock) {
            return refreshAt != null && now.isBefore(refreshAt);
        }
    }

    public void store(Instant fetchedAt, String tokenType, int expiresInSec, Duration cacheTtl) {
        synchronized (lock) {
            this.cachedAt = fetchedAt;
            this.tokenType = tokenType;
            this.expiresInSec = expiresInSec;
            long effectiveSec = Math.max(1L, Math.min((long) expiresInSec, cacheTtl.getSeconds()));
            long refreshAfterMs = (effectiveSec * 1000L) * REFRESH_AT_PERCENT / 100L;
            this.refreshAt = fetchedAt.plusMillis(refreshAfterMs);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(cachedAt, refreshAt, tokenType, expiresInSec);
        }
    }

    public void clear() {
        synchronized (lock) {
            cachedAt = null;
            refreshAt = null;
            tokenType = null;
            expiresInSec = 0;
        }
    }

    public record Snapshot(Instant cachedAt, Instant refreshAt, String tokenType, int expiresInSec) {
    }
}
