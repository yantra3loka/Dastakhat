package com.dastakhat.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small fixed window rate limiter.
 *
 * <p>It is intentionally in-memory: the platform is a self hosted single node application.
 * When the deployment grows to several nodes the limiter has to be moved to a shared store
 * (Redis) - the interface stays the same.</p>
 */
@Component
public class RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Registers a hit for the given key.
     *
     * @return {@code true} when the call is allowed, {@code false} when the limit is exceeded.
     */
    public boolean tryAcquire(String key, int maxAttempts, Duration window) {
        Instant now = Instant.now();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.windowStart.plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (w.count.get() > maxAttempts) {
            throw new RateLimitException("Too many requests. Please wait a moment and try again.");
        }
        return true;
    }

    /** Clears all counters. Used by tests. */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private final Instant windowStart;
        private final AtomicInteger count;

        private Window(Instant windowStart, int count) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(count);
        }
    }
}
