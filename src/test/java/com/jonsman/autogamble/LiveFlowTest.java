package com.jonsman.autogamble;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveFlowTest {
    final AutoGambleConfig c = new AutoGambleConfig();
    final CommandSuggestionPlayerSource source = new CommandSuggestionPlayerSource();
    final PlayerSelectionManager selection = new PlayerSelectionManager();
    final PaymentQueue queue = new PaymentQueue();
    final OutgoingPaymentTracker outgoing = new OutgoingPaymentTracker();
    final ReceiptDeduplicator receipts = new ReceiptDeduplicator();
    int rolls;
    GambleManager gamble() {
        c.gambleEnabled = true; c.winChance = 1;
        return new GambleManager(RegexPaymentParser.fromConfig(c), queue, receipts, outgoing,
            new Random(1) { @Override public double nextDouble() { rolls++; return .5; } }, p -> {});
    }
    ReceivedMessage message(ReceivedMessage.Channel channel) { return new ReceivedMessage("JonsmanV6or7 paid you $ 19.8k", channel); }
    void load(String... names) { source.complete(source.beginRequest(0), Arrays.asList(names), "Local"); }
    @Test void exactLiveOne() { assertEquals("1", AmountFormatter.format(RegexPaymentParser.fromConfig(c).parse("JonsmanV6or7 paid you $ 1", "Local").orElseThrow().amount())); }
    @Test void exactLiveDecimal() { assertEquals("19800", AmountFormatter.format(RegexPaymentParser.fromConfig(c).parse(message(ReceivedMessage.Channel.SYSTEM), "Local").orElseThrow().amount())); }
    @Test void decimalKCanonical() { assertEquals(0, new BigDecimal("19800").compareTo(MoneyParser.parse("19.8k").orElseThrow())); }
    @Test void millionCanonical() { assertEquals(0, new BigDecimal("1000000").compareTo(MoneyParser.parse("1M").orElseThrow())); }
    @Test void twoHooksOneRoll() {
        var g = gamble();
        assertEquals(GambleManager.Outcome.WIN, g.receive(message(ReceivedMessage.Channel.SYSTEM), "Local", 0, c));
        assertEquals(GambleManager.Outcome.DUPLICATE, g.receive(message(ReceivedMessage.Channel.SERVER_CHAT), "Local", 1, c));
        assertEquals(1, rolls); assertEquals(1, queue.size());
    }
    List<String> winner(boolean dry) {
        c.dryRunMode = dry; var g = gamble(); c.winnerDelayMinimumMs = c.winnerDelayMaximumMs = 0;
        g.receive(message(ReceivedMessage.Channel.SERVER_CHAT), "Local", 0, c);
        var commands = new ArrayList<String>();
        var processor = new WinnerPayoutProcessor(queue, new Random(3));
        PaymentSender sender = (name, amount, purpose) -> PaymentExecution.execute(c.dryRunMode, name, amount, purpose, 1, 10000, outgoing, commands::add);
        processor.tick(0, c, sender); processor.tick(1, c, sender); processor.tick(2, c, sender);
        assertEquals(1, rolls); assertEquals(0, queue.size());
        return commands;
    }
    @Test void dryWinnerRollsWithoutCommand() { assertTrue(winner(true).isEmpty()); }
    @Test void realWinnerSendsOnce() { assertEquals(List.of("pay JonsmanV6or7 39600"), winner(false)); }
    List<String> advertising(boolean dry) {
        load("Bob"); c.autoPayEnabled = true; c.dryRunMode = dry;
        c.minimumAutoPayDelaySeconds = c.maximumAutoPayDelaySeconds = 1;
        var commands = new ArrayList<String>(); var manager = new AutoPayManager();
        AutoPayEnvironment env = new AutoPayEnvironment() {
            public boolean connected() { return true; }
            public boolean inputBlocked() { return false; }
            public List<Candidate> eligiblePlayers() { return source.candidates(1); }
            public boolean dispatch(Candidate target, String amount) {
                return PaymentExecution.execute(c.dryRunMode, target.username(), new BigDecimal(amount), OutgoingPaymentTracker.Source.ADVERTISING,
                    1, 10000, outgoing, commands::add) == PaymentSender.Result.SENT;
            }
        };
        manager.tick(0, c, env, selection); manager.tick(1_000_000_000L, c, env, selection);
        assertEquals(1, selection.paidUsernames().size());
        assertEquals(dry ? "SIMULATED" : "DISPATCHED", manager.state());
        c.autoPayEnabled = false; manager.tick(2_000_000_000L, c, env, selection);
        assertEquals(0, manager.remainingNanos(0));
        return commands;
    }
    @Test void dryAdvertisingSimulated() { assertTrue(advertising(true).isEmpty()); }
    @Test void realAdvertisingCommand() { assertEquals(List.of("pay Bob 1"), advertising(false)); }
    @Test void suggestionNamesCollected() { load("Bob", "Steve"); assertEquals(2, source.candidates(1).size()); }
    @Test void localExcluded() { load("Local", "LOCAL", "Bob"); assertEquals("Bob", source.candidates(1).getFirst().username()); }
    @Test void malformedExcluded() { load("Bob", "bad name", "/pay Bob", "ab", "<player>", "Bob 1", "12345678901234567"); assertEquals(1, source.candidates(1).size()); }
    @Test void duplicatesRemoved() { load("Bob", "BOB", "Bob"); assertEquals(1, source.candidates(1).size()); }
    @Test void suggestionsRandomized() {
        load("Bob", "Steve", "Alice"); var seen = new HashSet<String>(); var random = new Random(11);
        for (int i = 0; i < 100; i++) seen.add(selection.select(source.candidates(1), false, random).orElseThrow().username());
        assertEquals(Set.of("Bob", "Steve", "Alice"), seen);
    }
    @Test void unpaidPreferred() { load("Bob", "Steve"); selection.markPaid("Bob"); assertEquals("Steve", selection.select(source.candidates(1), true, new Random()).orElseThrow().username()); }
    @Test void emptySuggestionsFallback() { load(); var tab = List.of(new Candidate(UUID.randomUUID(), "Bob")); assertEquals(tab, source.choose(1, tab)); assertEquals("TAB_FALLBACK", source.source(1)); }
    @Test void refreshRateLimited() { load("Bob"); for (long i=1; i<20; i++) assertEquals(-1, source.beginRequest(i * 1_000_000_000L)); assertTrue(source.beginRequest(20_000_000_000L) > 0); }
    @Test void changingSourcesPreservesHistory() {
        load("Bob"); selection.markPaid("Bob");
        source.complete(source.beginRequest(20_000_000_000L), List.of("Bob", "Steve"), "Local");
        assertEquals("Steve", selection.select(source.candidates(20_000_000_001L), true, new Random()).orElseThrow().username()); assertTrue(selection.wasPaid("Bob"));
    }
    @Test void sourceAndCountReported() { load("Bob", "Steve"); assertEquals("PAY_COMMAND_SUGGESTIONS", source.source(1)); assertEquals(2, source.choose(1, List.of()).size()); }
    @Test void lateSessionResponseIgnored() { long token = source.beginRequest(0); source.reset(); assertFalse(source.complete(token, List.of("Bob"), "Local")); assertTrue(source.candidates(1).isEmpty()); }
    @Test void oldRefreshResponseIgnored() { long token = source.beginRequest(0); source.beginRequest(20_000_000_000L); assertFalse(source.complete(token, List.of("Bob"), "Local")); }
    @Test void expiredCacheNotUsed() { load("Bob"); assertTrue(source.candidates(20_000_000_000L).isEmpty()); }
    @Test void playerAuthoredNoticeCannotBet() { var g = gamble(); assertEquals(GambleManager.Outcome.IGNORED, g.receive(message(ReceivedMessage.Channel.PLAYER_CHAT), "Local", 0, c)); assertEquals(0, rolls); }
    @Test void lastParsedShowsExpandedAmountAndResets() { var g = gamble(); g.receive(message(ReceivedMessage.Channel.SERVER_CHAT), "Local", 0, c); assertTrue(g.lastIncoming().contains("$19800")); g.reset(); assertEquals("none", g.lastIncoming()); }
}
