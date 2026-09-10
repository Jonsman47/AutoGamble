package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.*;

/** Records all mod command attempts, including ambiguous failures, before calling the network API. */
public final class OutgoingPaymentTracker {
    public enum Source { ADVERTISING, GAMBLE_PAYOUT, BALANCE_RULE, LOSING_BET_TIP, TIP_DISABLE_PURCHASE }
    public record Outgoing(String username, BigDecimal amount, long timestamp, Source source) {}
    private final Deque<Outgoing> recent = new ArrayDeque<>();
    public boolean record(String username, BigDecimal amount, long now, Source source, long windowMs) {
        expire(now, windowMs);
        if (recent.size() >= 4096) return false; // Fail closed; never send untracked payments.
        recent.addLast(new Outgoing(username, amount, now, source)); return true;
    }
    public boolean conflicts(PaymentParser.IncomingPayment payment, long now, long windowMs) {
        expire(now, windowMs);
        return recent.stream().anyMatch(p -> p.username().equalsIgnoreCase(payment.sender()) && p.amount().compareTo(payment.amount()) == 0);
    }
    public void expire(long now, long windowMs) { recent.removeIf(p -> now - p.timestamp() >= windowMs * 1_000_000L); }
    public List<Outgoing> snapshot() { return List.copyOf(recent); }
    public void reset() { recent.clear(); }
}
