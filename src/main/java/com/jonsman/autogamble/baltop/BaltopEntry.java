package com.jonsman.autogamble.baltop;

import java.math.BigDecimal;
import java.util.Locale;

public record BaltopEntry(String username, BigDecimal balance, Integer rank, int page, long lastSeenInBaltop) {
    public BaltopEntry {
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}") || balance == null || balance.signum() < 0
                || page < 1 || rank != null && rank < 1) throw new IllegalArgumentException("Invalid baltop entry");
    }
    public String key() { return username.toLowerCase(Locale.ROOT); }
}
