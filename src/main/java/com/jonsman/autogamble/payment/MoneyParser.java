package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.Optional;

/** Strict decimal notation; commas must form groups of three. No suffix/exponent interpretation. */
public final class MoneyParser {
    private MoneyParser() {}
    public static Optional<BigDecimal> parse(String text) {
        if (text == null || text.length() > 32 || !text.matches("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) return Optional.empty();
        BigDecimal amount = new BigDecimal(text.replace(",", ""));
        return amount.signum() > 0 && amount.compareTo(new BigDecimal("1000000000")) <= 0
                ? Optional.of(amount.stripTrailingZeros()) : Optional.empty();
    }
}
