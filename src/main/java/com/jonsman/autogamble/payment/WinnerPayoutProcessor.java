package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.util.random.RandomGenerator;
import org.slf4j.LoggerFactory;

/** Samples one independent wait per FIFO head; screen/offline retries preserve the job. */
public final class WinnerPayoutProcessor {
    private final PaymentQueue queue;
    private final RandomGenerator random;
    private Long nextAt;
    public WinnerPayoutProcessor(PaymentQueue queue, RandomGenerator random) { this.queue = queue; this.random = random; }
    public static long randomDelayNanos(AutoGambleConfig c, RandomGenerator random) {
        return random.nextLong(c.winnerDelayMinimumMs, c.winnerDelayMaximumMs + 1) * 1_000_000L;
    }
    public void tick(long now, AutoGambleConfig c, PaymentSender sender) {
        if (!c.enabled || !c.gambleEnabled) { cancel(); return; }
        if (queue.size() == 0) { nextAt = null; return; }
        if (nextAt == null) { nextAt = now + randomDelayNanos(c, random); return; }
        if (now - nextAt < 0) return;
        var payment = queue.peek().orElseThrow();
        PaymentSender.Result result = sender.sendPayment(payment.username(), payment.amount(), OutgoingPaymentTracker.Source.GAMBLE_PAYOUT);
        if (result == PaymentSender.Result.RETRY_LATER) { nextAt = now + 500_000_000L; return; }
        queue.removeHead();
        LoggerFactory.getLogger("autogamble").info("[AutoGamble] {}Payout {}: {} ${}", c.dryRunMode ? "DRY RUN: " : "", result, payment.username(), AmountFormatter.format(payment.amount()));
        // UNCERTAIN is deliberately never retried: retrying could pay twice.
        nextAt = queue.size() == 0 ? null : now + randomDelayNanos(c, random);
    }
    public void cancel() {
        if (queue.size() > 0) LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cancelled {} pending payouts", queue.size());
        queue.reset(); nextAt = null;
    }
}
