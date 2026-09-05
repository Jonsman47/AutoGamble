package com.jonsman.autogamble.config;

/** Mutable editing model; publish changes through ConfigManager.update on the client thread. */
public final class AutoGambleConfig {
    public int configVersion = 3;
    public boolean dryRunMode = true;
    public java.util.List<IncomingPattern> incomingPaymentPatterns = new java.util.ArrayList<>();
    public long receiptDeduplicationWindowMs = 2000, outgoingPaymentTrackingWindowMs = 10000;
    public static final class IncomingPattern {
        public boolean enabled = false;
        public String regex = "";
        public IncomingPattern() {}
        public IncomingPattern(boolean enabled, String regex) { this.enabled = enabled; this.regex = regex; }
    }
    public boolean enabled = true, autoPayEnabled = false, gambleEnabled = false;
    public double autoPayAmount = 1;
    public double minimumAutoPayDelaySeconds = 2, maximumAutoPayDelaySeconds = 5;
    public double winChance = 0.50, payoutMultiplier = 2;
    public boolean preferUnpaidPlayers = true;
    public double minimumBet = 1, maximumBet = 1_000_000;
    public long winnerDelayMinimumMs = 200, winnerDelayMaximumMs = 700;

    public void validate() {
        configVersion = 3;
        receiptDeduplicationWindowMs = Math.clamp(receiptDeduplicationWindowMs, 100, 60000);
        outgoingPaymentTrackingWindowMs = Math.clamp(outgoingPaymentTrackingWindowMs, 1000, 120000);
        if (incomingPaymentPatterns == null) incomingPaymentPatterns = new java.util.ArrayList<>();
        incomingPaymentPatterns = new java.util.ArrayList<>(incomingPaymentPatterns.stream()
                .filter(p -> p != null && p.regex != null && p.regex.length() <= 512).limit(16).toList());
        autoPayAmount = bounded(autoPayAmount, 1, 0, 1_000_000_000);
        minimumAutoPayDelaySeconds = bounded(minimumAutoPayDelaySeconds, 2, 0.001, 86400);
        maximumAutoPayDelaySeconds = Math.max(minimumAutoPayDelaySeconds,
                bounded(maximumAutoPayDelaySeconds, 5, 0.001, 86400));
        winChance = bounded(winChance, .5, 0, 1);
        payoutMultiplier = bounded(payoutMultiplier, 2, .001, 1000);
        minimumBet = bounded(minimumBet, 1, 0, 1_000_000_000);
        maximumBet = Math.max(minimumBet, bounded(maximumBet, 1_000_000, 0, 1_000_000_000));
        winnerDelayMinimumMs = Math.clamp(winnerDelayMinimumMs, 0, 86_400_000);
        winnerDelayMaximumMs = Math.clamp(winnerDelayMaximumMs, winnerDelayMinimumMs, 86_400_000);
    }
    private static double bounded(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
