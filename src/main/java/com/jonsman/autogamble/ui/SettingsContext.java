package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.ConfigManager;
import java.util.function.*;

public record SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid,
        Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers,
        Runnable clearFollowed, Supplier<String> automationStatus) {
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername) {
        this(configs, changed, resetPaid, status, localUsername, () -> false);
    }
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername, BooleanSupplier resetPayers) {
        this(configs, changed, resetPaid, status, localUsername, resetPayers, () -> {}, () -> "Current Known Balance: UNKNOWN");
    }
}
