package dev.streamable.streaming;

/**
 * Backoff schedule for a dropped destination.
 *
 * <p>Delays grow geometrically and are capped, so a service that is down for an
 * hour is retried every {@code maxDelay} rather than every second, but a brief
 * Wi-Fi blip recovers almost immediately.</p>
 *
 * @param enabled      whether to retry at all
 * @param initialDelayMs delay before the first retry
 * @param maxDelayMs   ceiling for the delay
 * @param multiplier   growth factor per attempt
 * @param maxAttempts  attempts before giving up; {@code <= 0} means unlimited
 */
public record ReconnectPolicy(boolean enabled, long initialDelayMs, long maxDelayMs,
                              double multiplier, int maxAttempts) {

    public static final ReconnectPolicy DEFAULT = new ReconnectPolicy(true, 5_000L, 60_000L, 2.0, 10);

    public ReconnectPolicy {
        initialDelayMs = Math.clamp(initialDelayMs, 250L, 300_000L);
        maxDelayMs = Math.clamp(maxDelayMs, initialDelayMs, 900_000L);
        multiplier = Double.isFinite(multiplier) ? Math.clamp(multiplier, 1.0, 10.0) : 2.0;
    }

    /**
     * Delay before the given retry attempt.
     *
     * @param attempt 1-based attempt number
     */
    public long delayForAttempt(int attempt) {
        if (attempt <= 1) {
            return initialDelayMs;
        }
        double delay = initialDelayMs * Math.pow(multiplier, attempt - 1);
        if (!Double.isFinite(delay) || delay > maxDelayMs) {
            return maxDelayMs;
        }
        return Math.min(maxDelayMs, (long) delay);
    }

    /** Whether another attempt is permitted after {@code attemptsSoFar} failures. */
    public boolean shouldRetry(int attemptsSoFar) {
        if (!enabled) {
            return false;
        }
        return maxAttempts <= 0 || attemptsSoFar < maxAttempts;
    }

    /** Human-readable progress text, e.g. {@code "Attempt 2/10"}. */
    public String describeAttempt(int attempt) {
        return maxAttempts <= 0 ? "Attempt " + attempt : "Attempt " + attempt + "/" + maxAttempts;
    }
}
