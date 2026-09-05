package com.jonsman.autogamble.manager;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.payment.*;
import java.math.BigDecimal;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parsing, arbitration and exactly one roll; no direct Minecraft or network dependency. */
public final class GambleManager {
    public enum Outcome { IGNORED, DUPLICATE, OUTGOING, INVALID, CAPACITY, LOSS, WIN }
    @FunctionalInterface public interface InvalidBetPolicy { void ignored(PaymentParser.IncomingPayment payment); }
    private static final Logger LOG = LoggerFactory.getLogger("autogamble");
    private PaymentParser parser;
    private final PaymentQueue queue;
    private final ReceiptDeduplicator receipts;
    private final OutgoingPaymentTracker outgoing;
    private final RandomGenerator random;
    private final InvalidBetPolicy invalidPolicy;
    public GambleManager(PaymentParser parser, PaymentQueue queue, ReceiptDeduplicator receipts,
                         OutgoingPaymentTracker outgoing, RandomGenerator random, InvalidBetPolicy invalidPolicy) {
        this.parser = parser; this.queue = queue; this.receipts = receipts;
        this.outgoing = outgoing; this.random = random; this.invalidPolicy = invalidPolicy;
    }
    public void parser(PaymentParser parser) { this.parser = parser; }
    public PaymentParser parser() { return parser; }
    public Outcome receive(ReceivedMessage message, String local, long now, AutoGambleConfig config) {
        return parser.parse(message, local).map(p -> accept(p, local, now, config)).orElse(Outcome.IGNORED);
    }
    public Outcome accept(PaymentParser.IncomingPayment payment, String local, long now, AutoGambleConfig c) {
        if (payment.sender().equalsIgnoreCase(local)) return Outcome.INVALID;
        if (!receipts.accept(payment, now, c.receiptDeduplicationWindowMs)) {
            LOG.debug("[AutoGamble] Duplicate receipt ignored"); return Outcome.DUPLICATE;
        }
        if (!c.enabled || !c.gambleEnabled) return Outcome.IGNORED;
        if (outgoing.conflicts(payment, now, c.outgoingPaymentTrackingWindowMs)) {
            LOG.debug("[AutoGamble] Incoming candidate conflicts with tracked outgoing payment"); return Outcome.OUTGOING;
        }
        if (payment.amount().compareTo(BigDecimal.valueOf(c.minimumBet)) < 0
                || payment.amount().compareTo(BigDecimal.valueOf(c.maximumBet)) > 0) {
            LOG.info("[AutoGamble] Ignored bet outside configured limits: {} ${}", payment.sender(), payment.amount());
            invalidPolicy.ignored(payment); return Outcome.INVALID;
        }
        BigDecimal payout;
        try { payout = calculatePayout(payment.amount(), c.payoutMultiplier); }
        catch (IllegalArgumentException e) {
            LOG.warn("[AutoGamble] Ignored bet with invalid calculated payout"); invalidPolicy.ignored(payment); return Outcome.INVALID;
        }
        // Reserve capacity before accepting a bet so a winning job cannot be silently dropped after its roll.
        if (!queue.hasCapacity()) { LOG.warn("[AutoGamble] Payout queue full; bet ignored before rolling"); return Outcome.CAPACITY; }
        String mode = c.dryRunMode ? "DRY RUN: " : "";
        LOG.info("[AutoGamble] {}Incoming bet: {} ${}", mode, payment.sender(), payment.amount());
        boolean wins = random.nextDouble() < c.winChance;
        if (!wins) { LOG.info("[AutoGamble] {}{} lost", mode, payment.sender()); return Outcome.LOSS; }
        queue.offer(new PaymentQueue.Payment(payment.sender(), payout, PaymentQueue.Purpose.WINNER_PAYOUT, now));
        LOG.info("[AutoGamble] {}{} WON -> {}pay ${}", mode, payment.sender(), c.dryRunMode ? "would " : "", AmountFormatter.format(payout));
        return Outcome.WIN;
    }
    public static BigDecimal calculatePayout(BigDecimal received, double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0) throw new IllegalArgumentException("Invalid multiplier");
        return new BigDecimal(AmountFormatter.format(received.multiply(BigDecimal.valueOf(multiplier))));
    }
    public void tick(long now, AutoGambleConfig c) {
        receipts.expire(now, c.receiptDeduplicationWindowMs);
        outgoing.expire(now, c.outgoingPaymentTrackingWindowMs);
    }
    public void reset() { receipts.reset(); outgoing.reset(); }
}
