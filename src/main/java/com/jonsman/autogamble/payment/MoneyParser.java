package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.Optional;

/** Strict decimal notation and K/M/B/T multipliers. Exponents and arbitrary suffixes are rejected. */
public final class MoneyParser {
    private MoneyParser() {}
    public static Optional<BigDecimal> parse(String text) {
        if (text == null || text.length() > 32 || !text.matches("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?[kKmMbBtT]?")) return Optional.empty();
        char last = Character.toUpperCase(text.charAt(text.length() - 1));
        int exponent = switch (last) { case 'K' -> 3; case 'M' -> 6; case 'B' -> 9; case 'T' -> 12; default -> 0; };
        String numeric = exponent == 0 ? text : text.substring(0, text.length() - 1);
        BigDecimal amount = new BigDecimal(numeric.replace(",", "")).multiply(BigDecimal.TEN.pow(exponent));
        return amount.signum() > 0 && amount.compareTo(new BigDecimal("1000000000000")) <= 0
                ? Optional.of(amount.stripTrailingZeros()) : Optional.empty();
    }
}
