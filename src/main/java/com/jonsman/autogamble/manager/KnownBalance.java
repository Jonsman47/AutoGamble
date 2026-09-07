package com.jonsman.autogamble.manager;

import java.math.BigDecimal;
import com.jonsman.autogamble.config.MoneyValues;

/** No production adapter exists yet. Only a verified server observation may populate this value. */
public final class KnownBalance {
    private BigDecimal amount;
    private long observed;
    private String source = "No verified DonutSMP balance source";
    public void observeVerified(BigDecimal amount, long now, String source) {
        if (!MoneyValues.valid(amount, false) || source == null || source.isBlank()) throw new IllegalArgumentException("Invalid observation");
        this.amount = amount; observed = now; this.source = source;
    }
    public BigDecimal current(long now) { return amount != null && now - observed >= 0 && now - observed <= 5_000_000_000L ? amount : null; }
    public String source() { return source; }
    public void invalidate() { amount = null; }
}
