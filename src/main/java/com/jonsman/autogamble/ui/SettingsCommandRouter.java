package com.jonsman.autogamble.ui;

/** An exact local interception avoids claiming the server's entire /settings command root. */
public final class SettingsCommandRouter {
    private SettingsCommandRouter() {}
    public static boolean intercept(String command, Runnable open) {
        if (command != null && command.trim().matches("settings\\s+(?i:Gamble)")) { open.run(); return true; }
        return false;
    }
}
