package com.jonsman.autogamble.ui;

import java.util.List;

/**
 * Central metadata for every user-facing AutoGamble command.
 * Developer rule: every new command must add an entry here so /help gamble stays complete.
 */
public final class HelpRegistry {
    public record Entry(String syntax, String description) {}
    private static final List<Entry> COMMANDS = List.of(
            new Entry("/settings Gamble", "Open the main AutoGamble settings screen."),
            new Entry("/autogamble settings", "Open the main AutoGamble settings screen."),
            new Entry("/autogamble status", "Show current automation and analytics status."),
            new Entry("/autogamble debug on", "Enable diagnostic logging."),
            new Entry("/autogamble debug off", "Disable diagnostic logging."),
            new Entry("/autogamble reports refresh", "Regenerate enabled payment reports."),
            new Entry("/autogamble reports status", "Show payment report status."),
            new Entry("/help gamble [page]", "Show this command list."));

    private HelpRegistry() {}
    public static List<Entry> commands() { return COMMANDS; }
}
