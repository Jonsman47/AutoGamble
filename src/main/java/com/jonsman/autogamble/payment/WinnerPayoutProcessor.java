package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.util.random.RandomGenerator;
import org.slf4j.LoggerFactory;

/** Samples one independent wait per FIFO head and serializes every gamble-related outgoing payment. */
public final class WinnerPayoutProcessor {
    private final PaymentQueue queue;
    private final RandomGenerator random;
    private Long nextAt;
    private TippingManager tipping;

    public WinnerPayoutProcessor(PaymentQueue queue, RandomGenerator random) {
        this.queue = queue;
        this.random = random;
    }

    public void tipping(TippingManager tipping) { this.tipping = tipping; }

    public static long randomDelayNanos(AutoGambleConfig config, RandomGenerator random) {
        return random.nextLong(config.winnerDelayMinimumMs, config.winnerDelayMaximumMs + 1) * 1_000_000L;
    }

    public void tick(long now, AutoGambleConfig config, PaymentSender sender) {
        if (queue.size() == 0) { nextAt = null; return; }
        PaymentQueue.Payment payment = queue.peek().orElseThrow();
        OutgoingPaymentTracker.Source source = source(payment.purpose());

        boolean gamblePayment = source == OutgoingPaymentTracker.Source.GAMBLE_PAYOUT
                || source == OutgoingPaymentTracker.Source.LOSING_BET_TIP;
        if (gamblePayment && (!config.enabled || !config.gambleEnabled
                || source == OutgoingPaymentTracker.Source.LOSING_BET_TIP && config.tippingPermanentlyDisabled)) {
            queue.removeHead();
            nextAt = null;
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cancelled pending {}", source);
            return;
        }

        if (nextAt == null) { nextAt = now + randomDelayNanos(config, random); return; }
        if (now - nextAt < 0) return;

        PaymentSender.Result result = sender.sendPayment(payment.username(), payment.amount(), source);
        if (result == PaymentSender.Result.RETRY_LATER) {
            if (source == OutgoingPaymentTracker.Source.GAMBLE_PAYOUT) {
                nextAt = now + 500_000_000L;
                return;
            }
            queue.removeHead();
            if (tipping != null) tipping.dispatchFailed(source);
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] {} was not dispatched and will not be retried", source);
        } else {
            queue.removeHead();
            if (result == PaymentSender.Result.UNCERTAIN && tipping != null) tipping.dispatchFailed(source);
            LoggerFactory.getLogger("autogamble").info("[AutoGamble] {}{} {}: {} {}",
                    config.dryRunMode ? "DRY RUN: " : "", source, result, payment.username(),
                    AmountFormatter.format(payment.amount()));
        }
        // UNCERTAIN is deliberately never retried: retrying could pay twice.
        nextAt = queue.size() == 0 ? null : now + randomDelayNanos(config, random);
    }

    private static OutgoingPaymentTracker.Source source(PaymentQueue.Purpose purpose) {
        return switch (purpose) {
            case WINNER_PAYOUT -> OutgoingPaymentTracker.Source.GAMBLE_PAYOUT;
            case LOSING_BET_TIP -> OutgoingPaymentTracker.Source.LOSING_BET_TIP;
            case TIP_DISABLE_PURCHASE -> OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE;
            case ADVERTISEMENT -> OutgoingPaymentTracker.Source.ADVERTISING;
        };
    }

    public void cancel() {
        if (queue.size() > 0) LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cancelled {} pending payments", queue.size());
        queue.reset();
        nextAt = null;
    }
}
