package com.jonsman.autogamble.payment;

import java.util.*;

/** Conservative semantic dedup: different renderings of the same sender/amount share one short window. */
public final class ReceiptDeduplicator {
    private final Map<String, Long> seen = new HashMap<>();
    public boolean accept(PaymentParser.IncomingPayment payment, long now, long windowMs) {
        expire(now, windowMs);
        String key = payment.sender().toLowerCase(Locale.ROOT) + ":" + payment.amount().stripTrailingZeros().toPlainString();
        if (seen.containsKey(key) || seen.size() >= 4096) return false;
        seen.put(key, now); return true;
    }
    public void expire(long now, long windowMs) { seen.values().removeIf(time -> now - time >= windowMs * 1_000_000L); }
    public int size() { return seen.size(); }
    public void reset() { seen.clear(); }
}
