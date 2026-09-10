package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.ConfigManager;
import com.jonsman.autogamble.history.AnalyticsEngine;
import com.jonsman.autogamble.history.PaymentHistory;
import com.jonsman.autogamble.payment.TippingManager;
import java.util.function.*;

public record SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid,
        Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers,
        Runnable clearFollowed, Supplier<String> automationStatus, Supplier<AnalyticsEngine.Snapshot> analytics,
        Supplier<PaymentHistory.Snapshot> reports, Runnable refreshReports,
        Supplier<TippingManager.Snapshot> tipping, Supplier<TippingManager.QueueResult> disableTipping) {
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername) {
        this(configs, changed, resetPaid, status, localUsername, () -> false);
    }
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers) {
        this(configs, changed, resetPaid, status, localUsername, resetPayers, () -> {},
                () -> "Current Known Balance: UNKNOWN", () -> null, () -> null, () -> {},
                () -> new TippingManager.Snapshot(0, false, ""), () -> TippingManager.QueueResult.FULL);
    }
}
