package com.jonsman.autogamble.config;

import java.util.*;

/** Strict UI rules. Disk recovery keeps its existing bounded, tolerant validation. */
public final class SettingsValidation {
    private SettingsValidation() {}
    public static List<String> errors(AutoGambleConfig c) {
        List<String> errors = new ArrayList<>();
        range(errors, "Minimum Alert Spacing", c.minimumAlertSpacingMs, 0, 5000);
        range(errors, "Auto-Pay Conversion Window", c.autoPayConversionWindowSeconds, 0, 86400);
        range(errors, "Auto-Pay Attribution Duration", c.autoPayAttributionDurationSeconds, 0, 86400);
        if (c.paymentAlertTiers == null || c.paymentAlertTiers.size() != 5) errors.add("Exactly five payment alert tiers are required.");
        else for (PaymentAlertTier tier : c.paymentAlertTiers) {
            if (tier == null || !MoneyValues.valid(tier.threshold, false)) errors.add("Payment alert thresholds must be valid non-negative amounts.");
            else if (tier.sound == null || !tier.sound.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) errors.add("Payment alert sound IDs must be namespaced.");
            else if (!Float.isFinite(tier.volume) || tier.volume < 0 || tier.volume > 4 || !Float.isFinite(tier.pitch) || tier.pitch < .5 || tier.pitch > 2)
                errors.add("Payment alert volume or pitch is outside its allowed range.");
        }
        if (!MoneyValues.valid(c.autoFollowThreshold, false)) errors.add("Follow Threshold must be at least zero.");
        if (!MoneyValues.valid(c.recentMinimumAmount, false)) errors.add("Recent Minimum Amount must be at least zero.");
        if (c.recentMaximumAmount != null && (!MoneyValues.valid(c.recentMaximumAmount, false)
                || c.recentMinimumAmount != null && c.recentMaximumAmount.compareTo(c.recentMinimumAmount) < 0)) errors.add("Recent Maximum Amount must be at least the minimum, or blank for unlimited.");
        range(errors, "Maximum Lines", c.recentMaxLines, 1, 100000);
        range(errors, "Stored Transaction History Limit", c.storedTransactionHistoryLimit, 100, 1000000);
        if (c.balancePaymentRules == null || c.balancePaymentRules.size() > 100) errors.add("Use at most 100 balance rules.");
        else {
            var ids = new java.util.HashSet<String>();
            for (var rule : c.balancePaymentRules) if (rule == null || !rule.valid() || !ids.add(rule.id)) errors.add("Invalid or duplicate balance rule.");
        }
        range(errors, "Minimum Prefix Length", c.minimumPrefixLength, 1, 3);
        range(errors, "Maximum Prefix Length", c.maximumPrefixLength, c.minimumPrefixLength, 3);
        range(errors, "Auto Pay Amount", c.autoPayAmount, .01, 1e9);
        range(errors, "Minimum Pay Delay", c.minimumAutoPayDelaySeconds, .001, 86400);
        range(errors, "Maximum Pay Delay", c.maximumAutoPayDelaySeconds, c.minimumAutoPayDelaySeconds, 86400);
        range(errors, "First-Time Win Bonus", c.firstTimeWinBonus, 0, 1);
        range(errors, "Spam Payment Threshold", c.spamPaymentThreshold, 2, 20);
        range(errors, "Spam Detection Window", c.spamPaymentWindowSeconds, 1, 60);
        range(errors, "Warning Cooldown", c.spamWarningCooldownSeconds, 5, 600);
        if (!com.jonsman.autogamble.payment.SpamWarningCommand.validText(c.spamWarningMessage))
            errors.add("Warning message must be 1–200 characters without line breaks or control characters.");
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
