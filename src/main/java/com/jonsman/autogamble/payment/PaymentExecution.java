package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Last gate immediately before the only real command callback. Shared by both payment sources. */
public final class PaymentExecution {
    private PaymentExecution() {}
    public static PaymentSender.Result execute(boolean dryRun, String username, BigDecimal amount,
            OutgoingPaymentTracker.Source source, long now, long windowMs,
            OutgoingPaymentTracker tracker, Consumer<String> realCommand) {
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}")) throw new IllegalArgumentException("Invalid target");
        String formatted = AmountFormatter.format(amount);
        if (dryRun) {
            LoggerFactory.getLogger("autogamble").info("[AutoGamble] DRY RUN: Would pay {} ${} ({})", username, formatted, source);
            return PaymentSender.Result.SENT; // Advance simulated history/queue; never register imaginary outgoing money.
        }
        if (!tracker.record(username, new BigDecimal(formatted), now, source, windowMs)) return PaymentSender.Result.RETRY_LATER;
        try { realCommand.accept("pay " + username + " " + formatted); return PaymentSender.Result.SENT; }
        catch (RuntimeException e) {
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Ambiguous dispatch; not retrying {} ${}", username, formatted, e);
            return PaymentSender.Result.UNCERTAIN;
        }
    }
}
