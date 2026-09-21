package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.ConfigManager;
import com.jonsman.autogamble.history.AnalyticsEngine;
import com.jonsman.autogamble.history.PaymentHistory;
import com.jonsman.autogamble.payment.TippingManager;
import com.jonsman.autogamble.baltop.*;
import java.util.List;
import java.util.function.*;

public record SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid,
        Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers,
        Runnable clearFollowed, Supplier<String> automationStatus, Supplier<AnalyticsEngine.Snapshot> analytics,
        Supplier<PaymentHistory.Snapshot> reports, Runnable refreshReports,
        Supplier<TippingManager.Snapshot> tipping, Supplier<TippingManager.QueueResult> disableTipping,
        Supplier<BaltopDatabase.Snapshot> baltopData, Supplier<BaltopCrawler.Status> baltopStatus,
        Runnable startBaltop, Runnable pauseBaltop, BooleanSupplier resetBaltop,
        Supplier<List<AnalyticsEngine.MethodStats>> targetingAnalytics) {
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status,
            Supplier<String> localUsername, BooleanSupplier resetPayers, Runnable clearFollowed,
            Supplier<String> automationStatus, Supplier<AnalyticsEngine.Snapshot> analytics,
            Supplier<PaymentHistory.Snapshot> reports, Runnable refreshReports,
            Supplier<TippingManager.Snapshot> tipping, Supplier<TippingManager.QueueResult> disableTipping) {
        this(configs, changed, resetPaid, status, localUsername, resetPayers, clearFollowed, automationStatus,
                analytics, reports, refreshReports, tipping, disableTipping,
                () -> new BaltopDatabase.Snapshot(List.of(),0,0,0,false,0,""),
                () -> new BaltopCrawler.Status(BaltopCrawler.State.IDLE,0,0,false,-1,0,0,0,"",""),
                () -> {}, () -> {}, () -> false, List::of);
    }
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername) {
        this(configs, changed, resetPaid, status, localUsername, () -> false);
    }
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers) {
        this(configs, changed, resetPaid, status, localUsername, resetPayers, () -> {},
                () -> "Current Known Balance: UNKNOWN", () -> null, () -> null, () -> {},
                () -> new TippingManager.Snapshot(0, false, ""), () -> TippingManager.QueueResult.FULL,
                () -> new BaltopDatabase.Snapshot(List.of(),0,0,0,false,0,""),
                () -> new BaltopCrawler.Status(BaltopCrawler.State.IDLE,0,0,false,-1,0,0,0,"",""),
                () -> {}, () -> {}, () -> false, List::of);
    }
}
