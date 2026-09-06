package com.jonsman.autogamble.config;

import java.util.*;

/** Strict UI rules. Disk recovery keeps its existing bounded, tolerant validation. */
public final class SettingsValidation {
    private SettingsValidation() {}
    public static List<String> errors(AutoGambleConfig c) {
        List<String> errors = new ArrayList<>();
        range(errors, "Minimum Prefix Length", c.minimumPrefixLength, 1, 3);
        range(errors, "Maximum Prefix Length", c.maximumPrefixLength, c.minimumPrefixLength, 3);
        range(errors, "Auto Pay Amount", c.autoPayAmount, .01, 1e9);
        range(errors, "Minimum Pay Delay", c.minimumAutoPayDelaySeconds, .001, 86400);
        range(errors, "Maximum Pay Delay", c.maximumAutoPayDelaySeconds, c.minimumAutoPayDelaySeconds, 86400);
        range(errors, "First-Time Win Bonus", c.firstTimeWinBonus, 0, 1);
        range(errors, "Win Chance", c.winChance, 0, 1);
        range(errors, "Payout Multiplier", c.payoutMultiplier, .001, 1000);
        range(errors, "Minimum Bet", c.minimumBet, 0, 1e9);
        range(errors, "Maximum Bet", c.maximumBet, c.minimumBet, 1e9);
        range(errors, "Winner Minimum Delay", c.winnerDelayMinimumMs, 0, 86400000);
        range(errors, "Winner Maximum Delay", c.winnerDelayMaximumMs, c.winnerDelayMinimumMs, 86400000);
        range(errors, "Receipt Deduplication", c.receiptDeduplicationWindowMs, 100, 60000);
        range(errors, "Outgoing Tracking", c.outgoingPaymentTrackingWindowMs, 1000, 120000);
        return errors;
    }
    private static void range(List<String> errors, String name, double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || value < min || value > max)
            errors.add(name + " must be between " + plain(min) + " and " + plain(max) + ".");
    }
    private static String plain(double n) { return Double.isFinite(n) ? java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString() : "a valid minimum"; }
}
