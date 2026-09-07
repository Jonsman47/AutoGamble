package com.jonsman.autogamble.config;

import java.math.BigDecimal;
import java.util.UUID;

public final class BalanceRule {
    public String id = UUID.randomUUID().toString();
    public boolean enabled = false;
    public String player = "";
    public BigDecimal threshold = BigDecimal.ZERO, amount = BigDecimal.ZERO;
    public int cooldownSeconds = 30;
    public boolean valid() {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}") && player != null && player.matches("[A-Za-z0-9_]{3,16}")
                && MoneyValues.valid(threshold, true) && MoneyValues.valid(amount, true)
                && amount.compareTo(new BigDecimal("1000000000000")) <= 0 && cooldownSeconds >= 0 && cooldownSeconds <= 86400;
    }
    public String signature() { return player.toLowerCase(java.util.Locale.ROOT) + ":" + threshold + ":" + amount; }
}
