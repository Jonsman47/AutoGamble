package com.jonsman.autogamble.config;

import java.util.*;
import java.math.BigDecimal;

/** Owns unsaved text, so tabs, resizing and child screens cannot discard edits. */
public final class SettingsDraft {
    public enum Field {
        FOLLOW_THRESHOLD("autoFollowThreshold", "Follow Threshold", false),
        RECENT_MIN("recentMinimumAmount", "Minimum Amount", false),
        RECENT_MAX("recentMaximumAmount", "Maximum (blank = unlimited)", false),
        RECENT_LINES("recentMaxLines", "Maximum Lines", true),
        HISTORY_LIMIT("storedTransactionHistoryLimit", "Stored History Limit", true),
        PREFIX_MIN("minimumPrefixLength", "Minimum Prefix Length", true),
        PREFIX_MAX("maximumPrefixLength", "Maximum Prefix Length", true),
        SPAM_THRESHOLD("spamPaymentThreshold", "Spam Payment Threshold", true),
        SPAM_WINDOW("spamPaymentWindowSeconds", "Spam Detection Window (s)", true),
        SPAM_COOLDOWN("spamWarningCooldownSeconds", "Warning Cooldown (s)", true),
        AMOUNT("autoPayAmount", "Auto Pay Amount ($)", false),
        PAY_MIN("minimumAutoPayDelaySeconds", "Minimum Pay Delay (s)", false),
        PAY_MAX("maximumAutoPayDelaySeconds", "Maximum Pay Delay (s)", false),
        MULTIPLIER("payoutMultiplier", "Payout Multiplier", false),
        BET_MIN("minimumBet", "Minimum Bet ($)", false),
        BET_MAX("maximumBet", "Maximum Bet ($)", false),
        WIN_MIN("winnerDelayMinimumMs", "Winner Minimum Delay (ms)", true),
        WIN_MAX("winnerDelayMaximumMs", "Winner Maximum Delay (ms)", true),
        DEDUP("receiptDeduplicationWindowMs", "Receipt Deduplication (ms)", true),
        TRACK("outgoingPaymentTrackingWindowMs", "Outgoing Tracking (ms)", true);
        public final String key, label; public final boolean integer;
        Field(String key, String label, boolean integer) { this.key = key; this.label = label; this.integer = integer; }
    }
    public final AutoGambleConfig working;
    private final Map<Field, String> text = new EnumMap<>(Field.class);
    public SettingsDraft(AutoGambleConfig snapshot) {
        working = snapshot;
        for (Field f : Field.values()) {
            try { Object value = AutoGambleConfig.class.getField(f.key).get(working); text.put(f, value == null ? "" : new BigDecimal(value.toString()).stripTrailingZeros().toPlainString()); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
    public String text(Field field) { return text.get(field); }
    public void text(Field field, String value) { text.put(field, value); }
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (Field f : Field.values()) {
            String value = text.get(f).trim();
            try {
                var moneyField = AutoGambleConfig.class.getField(f.key);
                if (moneyField.getType() == BigDecimal.class) {
                    moneyField.set(working, f == Field.RECENT_MAX && value.isBlank() ? null : MoneyValues.parse(value)); continue;
                }
                if (value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException();
                BigDecimal n = new BigDecimal(value);
                var property = AutoGambleConfig.class.getField(f.key);
                if (property.getType() == int.class) property.setInt(working, n.intValueExact());
                else if (f.integer) property.setLong(working, n.longValueExact());
                else property.setDouble(working, n.doubleValue());
            } catch (IllegalArgumentException | ArithmeticException e) { errors.add(f.label + ": enter a valid " + (f.integer ? "whole number." : "number.")); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        if (errors.isEmpty()) errors.addAll(SettingsValidation.errors(working));
        return errors;
    }
}
