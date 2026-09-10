package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.ArrayDeque;

/** Bounded client-thread FIFO. Payouts remain here until dispatched or explicitly cancelled. */
public final class PaymentQueue {
    public enum Purpose { ADVERTISEMENT, WINNER_PAYOUT, LOSING_BET_TIP, TIP_DISABLE_PURCHASE }
    public record Payment(String username, BigDecimal amount, Purpose purpose, long dueNanos) {
        public Payment {
            if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid username");
            if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000000000")) > 0)
                throw new IllegalArgumentException("Invalid amount");
            if (purpose == null) throw new IllegalArgumentException("Missing payment purpose");
        }
    }
    private final ArrayDeque<Payment> pending = new ArrayDeque<>();
    public boolean hasCapacity() { return pending.size() < 256; }
    public Optional<Payment> peek() { return Optional.ofNullable(pending.peek()); }
    public void removeHead() { pending.remove(); }
    public boolean offer(Payment payment) {
        if (pending.size() >= 256) return false;
        return pending.offer(payment);
    }
    public Optional<Payment> pollDue(long nowNanos) {
        return pending.isEmpty() || pending.peek().dueNanos() > nowNanos ? Optional.empty() : Optional.of(pending.remove());
    }
    public int size() { return pending.size(); }
    public void reset() { pending.clear(); }
}
