package com.jonsman.autogamble.config;

/** Reconfiguration policy shared by UI commits and future config editors. */
public record RuntimeSettingsChange(boolean resetAutoPay, boolean clearPayouts, boolean resetSimulation) {
    public static RuntimeSettingsChange between(AutoGambleConfig before, AutoGambleConfig after) {
        boolean mode = before != null && before.dryRunMode != after.dryRunMode;
        return new RuntimeSettingsChange(!after.enabled || !after.autoPayEnabled || mode
                || (before != null && (before.enabled != after.enabled || before.autoPayEnabled != after.autoPayEnabled)),
                !after.enabled || !after.gambleEnabled || mode, mode);
    }
    public static boolean requiresRealPaymentConfirmation(AutoGambleConfig before, AutoGambleConfig after) {
        return before.dryRunMode && !after.dryRunMode;
    }
}
