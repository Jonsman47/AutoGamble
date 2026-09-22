package com.jonsman.autogamble.config;

/** Commands are user-initiated shortcuts, never scheduled automation. */
public final class QuickCommands {
    private QuickCommands() {}

    public static boolean valid(String command) {
        return command != null && command.length() >= 2 && command.length() <= 80
                && command.matches("/[A-Za-z0-9_:-]+(?: [A-Za-z0-9_:-]+)*");
    }

    public static String wireCommand(String command) {
        if (!valid(command)) throw new IllegalArgumentException("Invalid quick command");
        return command.substring(1);
    }
    public static void executeClicked(String command, java.util.function.Consumer<String> sender) {
        sender.accept(wireCommand(command));
    }
}
