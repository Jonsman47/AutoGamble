package com.jonsman.autogamble;

import com.jonsman.autogamble.ui.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HelpCommandTest {
    private static List<HelpRegistry.Entry> entries(int count) {
        var list = new ArrayList<HelpRegistry.Entry>();
        for (int i = 1; i <= count; i++) list.add(new HelpRegistry.Entry("/command" + i, "Description " + i));
        return list;
    }
    @Test void baseCommandInterceptedLocally() { assertTrue(new HelpCommandRouter().intercept("help gamble", s -> {})); }
    @Test void explicitPageCommandInterceptedLocally() { assertTrue(new HelpCommandRouter().intercept("help gamble 1", s -> {})); }
    @Test void firstPageStartsAtFirstEntry() { assertTrue(new HelpCommandRouter(entries(16)).render(1).contains("/command1 - Description 1")); }
    @Test void secondPageStartsAtSixteenthEntry() { String text = new HelpCommandRouter(entries(16)).render(2); assertTrue(text.contains("/command16")); assertFalse(text.contains("/command15 ")); }
    @Test void finalPartialPageContainsRemainingEntries() { assertTrue(new HelpCommandRouter(entries(31)).render(3).contains("/command31")); }
    @Test void invalidPageProducesClearFeedback() { assertTrue(new HelpCommandRouter(entries(1)).render(2).contains("Invalid help page")); }
    @Test void nonNumericPageProducesClearFeedback() { List<String> out = new ArrayList<>(); assertTrue(new HelpCommandRouter().intercept("help gamble wat", out::add)); assertTrue(out.getFirst().contains("Invalid help page")); }
    @Test void pageNeverExceedsFifteenCommands() { String text = new HelpCommandRouter(entries(40)).render(2); assertEquals(15, text.lines().filter(l -> l.startsWith("/command")).count()); }
    @Test void unrelatedHelpIsNotIntercepted() { var router = new HelpCommandRouter(); assertFalse(router.intercept("help", s -> fail())); assertFalse(router.intercept("help other", s -> fail())); }
    @Test void registryCoversEveryImplementedSyntax() {
        Set<String> expected = Set.of("/settings Gamble", "/autogamble settings", "/autogamble status", "/autogamble debug on",
                "/autogamble debug off", "/autogamble reports refresh", "/autogamble reports status", "/help gamble [page]");
        assertEquals(expected, HelpRegistry.commands().stream().map(HelpRegistry.Entry::syntax).collect(java.util.stream.Collectors.toSet()));
    }
}
