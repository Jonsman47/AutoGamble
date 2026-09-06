package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.ConfigManager;
import java.util.function.Supplier;

public record SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid,
                              Supplier<String> status, Supplier<String> localUsername, java.util.function.BooleanSupplier resetPayers) {
    public SettingsContext(ConfigManager configs, Runnable changed, Runnable resetPaid, Supplier<String> status, Supplier<String> localUsername) {
        this(configs, changed, resetPaid, status, localUsername, () -> false);
    }
}
