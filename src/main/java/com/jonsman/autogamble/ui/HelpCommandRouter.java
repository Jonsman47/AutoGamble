package com.jonsman.autogamble.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact local interception for /help gamble and its optional page. */
public final class HelpCommandRouter {
    public static final int PAGE_SIZE = 15;
    private static final Pattern COMMAND = Pattern.compile("^help\\s+gamble(?:\\s+(\\S+))?$", Pattern.CASE_INSENSITIVE);
    private final List<HelpRegistry.Entry> entries;

    public HelpCommandRouter() { this(HelpRegistry.commands()); }
    public HelpCommandRouter(List<HelpRegistry.Entry> entries) { this.entries = List.copyOf(entries); }

    public boolean intercept(String command, Consumer<String> feedback) {
        Matcher match = COMMAND.matcher(command == null ? "" : command.strip());
        if (!match.matches()) return false;
        int page = 1;
        if (match.group(1) != null) {
            try { page = Integer.parseInt(match.group(1)); }
            catch (NumberFormatException ignored) { feedback.accept("[AutoGamble] Invalid help page. Use /help gamble [page]."); return true; }
        }
        feedback.accept(render(page));
        return true;
    }

    public String render(int page) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 1 || page > pages) return "[AutoGamble] Invalid help page " + page + ". Valid pages: 1-" + pages + ".";
        StringBuilder out = new StringBuilder("AutoGamble Commands (Page ").append(page).append('/').append(pages).append(")");
        int start = (page - 1) * PAGE_SIZE, end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            HelpRegistry.Entry entry = entries.get(i);
            out.append('\n').append(entry.syntax()).append(" - ").append(entry.description());
        }
        out.append("\nUse /help gamble <page> for more commands.");
        return out.toString();
    }
}
