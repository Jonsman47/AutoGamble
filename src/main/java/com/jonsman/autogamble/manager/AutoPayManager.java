package com.jonsman.autogamble.manager;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.util.random.RandomGenerator;
import com.jonsman.autogamble.payment.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-thread state machine with a fresh delay after every attempt, never catch-up bursts. */
public final class AutoPayManager {
    private static final Logger LOG = LoggerFactory.getLogger("autogamble");
    private final RandomGenerator delayRandom;
    private final RandomGenerator playerRandom;
    private boolean running, emptyReported, invalidAmountReported;
    private Long nextPaymentNanos;
    public AutoPayManager() { this(new java.util.Random(), new java.util.Random()); }
    public AutoPayManager(RandomGenerator delayRandom, RandomGenerator playerRandom) {
        this.delayRandom = delayRandom; this.playerRandom = playerRandom;
    }
    public void scheduleAfterPayment(long nowNanos, AutoGambleConfig config, RandomGenerator random) {
        double min = config.minimumAutoPayDelaySeconds;
        double max = config.maximumAutoPayDelaySeconds;
        if (!Double.isFinite(min) || !Double.isFinite(max) || min <= 0 || max < min || max > 86400)
            throw new IllegalArgumentException("Invalid delay range");
        double seconds = min == max ? min : min + random.nextDouble() * (max - min);
        nextPaymentNanos = nowNanos + (long) (seconds * 1_000_000_000L);
    }
    public boolean isDue(long nowNanos) { return nextPaymentNanos != null && nowNanos - nextPaymentNanos >= 0; }
    public void tick(long nowNanos, AutoGambleConfig config, AutoPayEnvironment environment, PlayerSelectionManager selection) {
        if (!config.enabled || !config.autoPayEnabled || !environment.connected()) { reset(); return; }
        if (!running) {
            running = true;
            LOG.info("[AutoGamble] Auto-pay enabled");
            scheduleAfterPayment(nowNanos, config, delayRandom);
            return;
        }
        if (!isDue(nowNanos)) return;
        if (environment.inputBlocked()) { nextPaymentNanos = nowNanos + 500_000_000L; return; }
        try {
            String amount;
            try { amount = AmountFormatter.format(config.autoPayAmount); }
            catch (IllegalArgumentException e) {
                if (!invalidAmountReported) LOG.warn("[AutoGamble] Auto-pay amount is invalid or rounds to zero; skipping");
                invalidAmountReported = true;
                return;
            }
            invalidAmountReported = false;
            var candidates = environment.eligiblePlayers();
            var target = selection.select(candidates, config.preferUnpaidPlayers, playerRandom);
            if (target.isEmpty()) {
                if (!emptyReported) LOG.info("[AutoGamble] No eligible players found");
                emptyReported = true;
                return;
            }
            emptyReported = false;
            if (environment.dispatch(target.get(), amount)) {
                selection.markPaid(target.get().username());
                LOG.info("[AutoGamble] {} /pay {} {}", config.dryRunMode ? "DRY RUN simulated" : "Dispatched", target.get().username(), amount);
            }
        } catch (RuntimeException e) {
            LOG.debug("[AutoGamble] Auto-pay attempt failed; waiting for next cycle", e);
        } finally {
            scheduleAfterPayment(nowNanos, config, delayRandom);
        }
    }
    public long remainingNanos(long now) { return nextPaymentNanos == null ? 0 : Math.max(0, nextPaymentNanos - now); }
    public void reset() {
        if (running) LOG.info("[AutoGamble] Auto-pay disabled");
        running = false; nextPaymentNanos = null; emptyReported = false; invalidAmountReported = false;
    }
}
