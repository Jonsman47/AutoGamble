package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Owns losing-bet tip calculation and confirmation-gated accounting for tipping payments. */
public final class TippingManager {
    public static final String RECIPIENT = "Mac10HeatInciden";
    public static final BigDecimal RATE = new BigDecimal("0.05");
    public static final BigDecimal DISABLE_PRICE = new BigDecimal("500000000");
    public static final long CONFIRMATION_TIMEOUT_MS = 15_000;

    public enum QueueResult {
        QUEUED("Payment queued. Tipping remains enabled until the server confirms it."),
        DRY_RUN("Disable Dry Run before making the 500M permanent-disable payment."),
        ALREADY_DISABLED("Tipping is already permanently disabled."),
        ALREADY_PENDING("A permanent-disable payment is already pending."),
        ZERO("The rounded tip is zero."),
        FULL("The outgoing payment queue is full.");

        private final String message;
        QueueResult(String message) { this.message = message; }
        public String message() { return message; }
    }

    public record Pending(String username, BigDecimal amount, OutgoingPaymentTracker.Source source, long sentAtMillis) {}
    public record Snapshot(int pendingTips, boolean purchasePending, String message) {}

    @FunctionalInterface public interface ConfirmedPayment {
        void confirmed(String username, BigDecimal amount, OutgoingPaymentTracker.Source source);
    }

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final ConfirmedPayment confirmed;
    private boolean purchaseQueued;
    private String message = "";

    public TippingManager(ConfirmedPayment confirmed) {
        this.confirmed = Objects.requireNonNull(confirmed);
    }

    public static BigDecimal tipAmount(BigDecimal bet) {
        if (bet == null || bet.signum() <= 0) return BigDecimal.ZERO;
        return bet.multiply(RATE).setScale(0, RoundingMode.DOWN);
    }

    public QueueResult queueLosingBetTip(BigDecimal bet, long nowNanos, AutoGambleConfig config, PaymentQueue queue) {
        if (config.tippingPermanentlyDisabled) return QueueResult.ALREADY_DISABLED;
        BigDecimal amount = tipAmount(bet);
        if (amount.signum() == 0) return QueueResult.ZERO;
        if (config.dryRunMode) {
            LoggerFactory.getLogger("autogamble").info("[AutoGamble] DRY RUN: Losing-bet tip would pay {} {}", RECIPIENT, AmountFormatter.format(amount));
            return QueueResult.DRY_RUN;
        }
        return queue.offer(new PaymentQueue.Payment(RECIPIENT, amount, PaymentQueue.Purpose.LOSING_BET_TIP, nowNanos))
                ? QueueResult.QUEUED : QueueResult.FULL;
    }

    public QueueResult requestPermanentDisable(AutoGambleConfig config, PaymentQueue queue, long nowNanos) {
        if (config.tippingPermanentlyDisabled) return QueueResult.ALREADY_DISABLED;
        if (config.dryRunMode) return QueueResult.DRY_RUN;
        if (purchaseQueued || pending.stream().anyMatch(p -> p.source() == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE))
            return QueueResult.ALREADY_PENDING;
        if (!queue.offer(new PaymentQueue.Payment(RECIPIENT, DISABLE_PRICE,
                PaymentQueue.Purpose.TIP_DISABLE_PURCHASE, nowNanos))) return QueueResult.FULL;
        purchaseQueued = true;
        message = QueueResult.QUEUED.message();
        return QueueResult.QUEUED;
    }

    public void dispatched(String username, BigDecimal amount, OutgoingPaymentTracker.Source source, long nowMillis) {
        if (source != OutgoingPaymentTracker.Source.LOSING_BET_TIP
                && source != OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) return;
        pending.addLast(new Pending(username, amount, source, nowMillis));
        if (source == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) purchaseQueued = true;
    }

    public boolean receiveConfirmation(ReceivedMessage message, long nowMillis) {
        Optional<OutgoingConfirmation> parsed = OutgoingConfirmation.parse(message);
        if (parsed.isEmpty()) return false;
        OutgoingConfirmation confirmation = parsed.get();
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending payment = iterator.next();
            if (nowMillis - payment.sentAtMillis() > CONFIRMATION_TIMEOUT_MS) continue;
            if (!payment.username().equalsIgnoreCase(confirmation.username())
                    || payment.amount().compareTo(confirmation.amount()) != 0) continue;
            iterator.remove();
            if (payment.source() == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) {
                purchaseQueued = false;
                this.message = "500M payment confirmed. Tipping is permanently disabled.";
            }
            confirmed.confirmed(payment.username(), payment.amount(), payment.source());
            return true;
        }
        return false;
    }

    public void tick(long nowMillis) {
        boolean purchaseExpired = false;
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending payment = iterator.next();
            if (nowMillis - payment.sentAtMillis() <= CONFIRMATION_TIMEOUT_MS) continue;
            iterator.remove();
            if (payment.source() == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) {
                purchaseQueued = false;
                purchaseExpired = true;
            }
        }
        if (purchaseExpired) message = "500M payment was not confirmed. Tipping remains enabled.";
    }

    public void dispatchFailed(OutgoingPaymentTracker.Source source) {
        if (source == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) {
            purchaseQueued = false;
            message = "500M payment was not confirmed. Tipping remains enabled.";
        }
    }

    public Snapshot snapshot() {
        return new Snapshot((int) pending.stream().filter(p -> p.source() == OutgoingPaymentTracker.Source.LOSING_BET_TIP).count(),
                purchaseQueued || pending.stream().anyMatch(p -> p.source() == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE),
                message);
    }

    public List<Pending> pending() { return List.copyOf(pending); }

    public void resetSession() {
        pending.clear();
        purchaseQueued = false;
        message = "";
    }

    public record OutgoingConfirmation(String username, BigDecimal amount) {
        public static Optional<OutgoingConfirmation> parse(ReceivedMessage message) {
            if (message == null || message.channel() == ReceivedMessage.Channel.PLAYER_CHAT || message.text() == null
                    || message.text().length() > 1024) return Optional.empty();
            String text = ReceivedMessage.normalize(message.text());
            java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("^You paid ([A-Za-z0-9_]{2,16}) \\$ ?(.+)$").matcher(text);
            if (!match.matches()) return Optional.empty();
            return MoneyParser.parse(match.group(2)).map(amount -> new OutgoingConfirmation(match.group(1), amount));
        }
    }
}

