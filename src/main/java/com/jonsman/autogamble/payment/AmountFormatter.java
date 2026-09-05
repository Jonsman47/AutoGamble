package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal command amounts, rounded to cents without binary floating-point tails. */
public final class AmountFormatter {
    private AmountFormatter() {}
    public static String format(double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000)
            throw new IllegalArgumentException("Invalid auto-pay amount");
        return format(BigDecimal.valueOf(amount));
    }
    public static String format(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000000000")) > 0)
            throw new IllegalArgumentException("Invalid payment amount");
        BigDecimal decimal = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (decimal.signum() <= 0) throw new IllegalArgumentException("Amount rounds to zero");
        return decimal.toPlainString();
    }
}
