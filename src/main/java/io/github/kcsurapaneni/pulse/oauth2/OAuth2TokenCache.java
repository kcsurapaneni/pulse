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

    /**
     * Whether the cached token is still within its IdP-reported natural lifetime, regardless of
     * whether we've already passed the refresh point. Lets the OAuth2 check fall back to a
     * stale-but-cryptographically-valid token when a refresh attempt hits a transient failure.
     */
    public boolean isUsable(Instant now) {
        synchronized (lock) {
            if (cachedAt == null) {
                return false;
            }
            Instant naturalExpiry = cachedAt.plusSeconds(expiresInSec);
            return now.isBefore(naturalExpiry);
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
