package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.GambleManager;
import com.jonsman.autogamble.manager.GambleManager.Outcome;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.payment.PaymentParser.IncomingPayment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GambleTest {
    // Synthetic examples ONLY. Neither pattern is a verified DonutSMP format.
    private static final String EXAMPLE = "(?<sender>.+) paid you \\$(?<amount>.+)";
    private static final String ALTERNATE = "You received \\$(?<amount>.+) from (?<sender>.+)";
    private static AutoGambleConfig.IncomingPattern pattern(String regex) { return new AutoGambleConfig.IncomingPattern(true, regex); }
    private final RegexPaymentParser parser = new RegexPaymentParser(List.of(pattern(EXAMPLE), pattern(ALTERNATE)));
    private final PaymentQueue queue = new PaymentQueue();
    private final ReceiptDeduplicator receipts = new ReceiptDeduplicator();
    private final OutgoingPaymentTracker outgoing = new OutgoingPaymentTracker();
    private final CountingRandom random = new CountingRandom();
    private int invalidCalls;
    private final GambleManager gamble = new GambleManager(parser, queue, receipts, outgoing, random, p -> invalidCalls++);
    private final AutoGambleConfig config = new AutoGambleConfig();
    @TempDir Path directory;
    private static class CountingRandom extends Random {
        int calls; double value = .25;
        @Override public double nextDouble() { calls++; return value; }
    }
    GambleTest() { config.gambleEnabled = true; }
    private IncomingPayment receipt(String sender, String amount) { return new IncomingPayment(sender, new BigDecimal(amount), "synthetic"); }
    private Outcome accept(String sender, String amount, long ms) { return gamble.accept(receipt(sender, amount), "Local", ms * 1_000_000L, config); }
    private ReceivedMessage message(String text) { return new ReceivedMessage(text, ReceivedMessage.Channel.SYSTEM); }

    @Test void validExampleIncomingPaymentParses() {
        var result = parser.parse(message("Bob paid you $100"), "Local").orElseThrow();
        assertEquals("Bob", result.sender()); assertEquals(0, new BigDecimal("100").compareTo(result.amount()));
        assertEquals(64, result.receiptId().length());
    }
    @Test void unrelatedAndOutgoingTextDoNotMatchFullMessage() {
        for (String text : List.of("hello", "<Bob> Bob paid you $100", "You paid Bob $100", "Bob paid you $100 goodbye"))
            assertTrue(parser.parse(message(text), "Local").isEmpty(), text);
    }
    @Test void signedPlayerChatIsNeverAcceptedEvenIfItMatches() {
        assertTrue(parser.parse(new ReceivedMessage("Bob paid you $100", ReceivedMessage.Channel.PLAYER_CHAT), "Local").isEmpty());
    }
    @Test void malformedAndSelfSenderRejected() {
        for (String sender : List.of("Bob /pay Evil", "ab", "12345678901234567", "Bob;op", "LOCAL", "Bob\nEvil"))
            assertTrue(parser.parse(message(sender + " paid you $100"), "Local").isEmpty(), sender);
    }
    @Test void malformedAmountsRejected() {
        for (String amount : List.of("-1", "0", "NaN", "Infinity", "1e3", "1,00", "1,000,00", ".50", "1.", "1.001", "+1", "1kk", "1000000000001", "1 000", "1;pay Evil 100"))
            assertTrue(parser.parse(message("Bob paid you $" + amount), "Local").isEmpty(), amount);
    }
    @Test void commasAndDecimalsAreExact() {
        assertEquals(new BigDecimal("1000.5"), MoneyParser.parse("1,000.50").orElseThrow());
        assertEquals(new BigDecimal("1000.5"), MoneyParser.parse("1000.50").orElseThrow());
        assertEquals(0, new BigDecimal("1000").compareTo(MoneyParser.parse("1,000").orElseThrow()));
    }
    @Test void disabledPatternsAndMissingGroupsAreInactive() {
        assertEquals(0, new RegexPaymentParser(List.of(new AutoGambleConfig.IncomingPattern(false, EXAMPLE), pattern("["), pattern(".*"))).enabledCount());
        assertTrue(new RegexPaymentParser(new AutoGambleConfig().incomingPaymentPatterns).parse("Bob paid you $100", "Local").isEmpty());
    }
    @Test void normalizedFormattingHasSameFingerprint() {
        var first = parser.parse("§aBob  paid you $100", "Local").orElseThrow();
        var second = parser.parse("Bob paid you $100", "Local").orElseThrow();
        assertEquals(first.receiptId(), second.receiptId());
    }
    @Test void pathologicalRegexAndOverlongMessagesFailSafely() {
        var bad = new RegexPaymentParser(List.of(pattern("(?<sender>(a+)+) (?<amount>.+)")));
        assertTimeout(Duration.ofSeconds(2), () -> assertTrue(bad.parse("a".repeat(500) + "!", "Local").isEmpty()));
        assertTrue(parser.parse("x".repeat(1025), "Local").isEmpty());
    }
    @Test void minimumBetEnforcedWithoutRollAndPolicyCalled() {
        config.minimumBet = 10;
        assertEquals(Outcome.INVALID, accept("Bob", "9.99", 0));
        assertEquals(0, random.calls); assertEquals(1, invalidCalls); assertEquals(0, queue.size());
    }
    @Test void maximumBetEnforcedWithoutRoll() {
        config.maximumBet = 100;
        assertEquals(Outcome.INVALID, accept("Bob", "100.01", 0)); assertEquals(0, random.calls);
    }
    @Test void exactBetLimitsAccepted() {
        config.minimumBet = 10; config.maximumBet = 100;
        assertEquals(Outcome.WIN, accept("Bob", "10", 0));
        assertEquals(Outcome.WIN, accept("Alice", "100", 0)); assertEquals(2, random.calls);
    }
    @Test void zeroWinChanceAlwaysLosesWithOneRoll() {
        config.firstTimePayerBonusEnabled = false;
        config.winChance = 0; random.value = 0;
        assertEquals(Outcome.LOSS, accept("Bob", "100", 0)); assertEquals(1, random.calls); assertEquals(0, queue.size());
    }
    @Test void oneWinChanceAlwaysWinsWithOneRoll() {
        config.winChance = 1; random.value = Math.nextDown(1.0);
        assertEquals(Outcome.WIN, accept("Bob", "100", 0)); assertEquals(1, random.calls);
        assertEquals(new BigDecimal("200"), queue.peek().orElseThrow().amount());
    }
    @Test void probabilityUsesStrictThreshold() {
        config.firstTimePayerBonusEnabled = false;
        config.winChance = .45; random.value = .45;
        assertEquals(Outcome.LOSS, accept("Bob", "100", 0));
        random.value = .449;
        assertEquals(Outcome.WIN, accept("Alice", "100", 0));
    }
    @Test void multiplierUsesEntireReceivedStake() {
        assertEquals(new BigDecimal("500"), GambleManager.calculatePayout(new BigDecimal("250"), 2));
        assertEquals(new BigDecimal("375"), GambleManager.calculatePayout(new BigDecimal("250"), 1.5));
    }
    @Test void decimalPayoutsRoundOnceHalfUp() {
        assertEquals(new BigDecimal("1.52"), GambleManager.calculatePayout(new BigDecimal("1.01"), 1.5));
        assertEquals(new BigDecimal("0.3"), GambleManager.calculatePayout(new BigDecimal("0.10"), 3));
        assertEquals("1000000000000", AmountFormatter.format(new BigDecimal("1000000000000")));
        assertThrows(IllegalArgumentException.class, () -> GambleManager.calculatePayout(new BigDecimal("1000000000"), 1001));
    }
    @Test void duplicateReceiptAndAlternateRenderingRollOnlyOnce() {
        assertEquals(Outcome.WIN, gamble.receive(message("Bob paid you $100"), "Local", 0, config));
        assertEquals(Outcome.DUPLICATE, gamble.receive(new ReceivedMessage("You received $100.00 from Bob", ReceivedMessage.Channel.OVERLAY), "Local", 100_000_000L, config));
        assertEquals(1, random.calls); assertEquals(1, queue.size());
    }
    @Test void samePlayerSameAmountLaterIsNewBet() {
        assertEquals(Outcome.WIN, accept("Bob", "100", 1000));
        assertEquals(Outcome.WIN, accept("Bob", "100", 10000)); assertEquals(2, random.calls);
    }
    @Test void dedupWindowBoundaryDoesNotSlideOnDuplicates() {
        accept("Bob", "100", 0);
        assertEquals(Outcome.DUPLICATE, accept("bob", "100.00", 1999));
        assertEquals(Outcome.WIN, accept("Bob", "100", 2000));
    }
    @Test void advertisingCannotBecomeBet() {
        outgoing.record("Bob", new BigDecimal("100"), 0, OutgoingPaymentTracker.Source.ADVERTISING, 10000);
        assertEquals(Outcome.OUTGOING, accept("Bob", "100", 100)); assertEquals(0, random.calls);
    }
    @Test void winnerPaymentCannotBecomeBet() {
        outgoing.record("Bob", new BigDecimal("200"), 0, OutgoingPaymentTracker.Source.GAMBLE_PAYOUT, 10000);
        assertEquals(Outcome.OUTGOING, accept("Bob", "200", 100)); assertEquals(0, random.calls);
    }
    @Test void outgoingSuppressionExpiresAndDoesNotBlockDifferentAmounts() {
        outgoing.record("Bob", new BigDecimal("100"), 0, OutgoingPaymentTracker.Source.ADVERTISING, 10000);
        assertEquals(Outcome.WIN, accept("Bob", "101", 100));
        assertEquals(Outcome.WIN, accept("Bob", "100", 10000)); assertTrue(outgoing.snapshot().isEmpty());
    }
    @Test void disabledMessagesNeverQueueAndAreRememberedBriefly() {
        config.gambleEnabled = false; assertEquals(Outcome.IGNORED, accept("Bob", "100", 0));
        config.gambleEnabled = true; assertEquals(Outcome.DUPLICATE, accept("Bob", "100", 10));
        config.enabled = false; assertEquals(Outcome.IGNORED, accept("Alice", "100", 10));
        assertEquals(0, random.calls); assertEquals(0, queue.size());
    }
    @Test void fullQueueRejectsBeforeRollAndNeverOverwritesWinner() {
        for (int i = 0; i < 256; i++) queue.offer(new PaymentQueue.Payment("Bob", BigDecimal.ONE, PaymentQueue.Purpose.WINNER_PAYOUT, i));
        assertEquals(Outcome.CAPACITY, accept("Alice", "100", 0)); assertEquals(0, random.calls); assertEquals(256, queue.size());
    }
    @Test void multipleWinnersPreservedAndPaidSequentially() {
        config.winChance = 1; config.winnerDelayMinimumMs = config.winnerDelayMaximumMs = 200;
        accept("Alice", "10", 0); accept("Bob", "20", 0); accept("Steve", "30", 0);
        var processor = new WinnerPayoutProcessor(queue, new Random(4));
        List<String> sent = new ArrayList<>();
        PaymentSender sender = (name, amount, source) -> { sent.add(name + ":" + AmountFormatter.format(amount)); return PaymentSender.Result.SENT; };
        processor.tick(0, config, sender); processor.tick(199_000_000L, config, sender); assertTrue(sent.isEmpty());
        processor.tick(200_000_000L, config, sender); assertEquals(2, queue.size());
        processor.tick(200_000_000L, config, sender); assertEquals(1, sent.size());
        processor.tick(400_000_000L, config, sender); processor.tick(600_000_000L, config, sender);
        assertEquals(List.of("Alice:20", "Bob:40", "Steve:60"), sent); assertEquals(0, queue.size());
    }
    @Test void payoutDelaysAreFreshAndWithinInclusiveBounds() {
        Random rng = new Random(5); Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long delay = WinnerPayoutProcessor.randomDelayNanos(config, rng);
            assertTrue(delay >= 200_000_000L && delay <= 700_000_000L); delays.add(delay);
        }
        assertTrue(delays.size() > 100);
        config.winnerDelayMinimumMs = config.winnerDelayMaximumMs = 0;
        assertEquals(0, WinnerPayoutProcessor.randomDelayNanos(config, rng));
    }
    @Test void blockedPayoutPreservedWithBriefRetry() {
        accept("Bob", "100", 0); config.winnerDelayMinimumMs = config.winnerDelayMaximumMs = 200;
        var processor = new WinnerPayoutProcessor(queue, new Random(4)); int[] calls = {0};
        PaymentSender blocked = (name, amount, source) -> { calls[0]++; return PaymentSender.Result.RETRY_LATER; };
        processor.tick(0, config, blocked); processor.tick(200_000_000L, config, blocked);
        processor.tick(699_000_000L, config, blocked); assertEquals(1, calls[0]); assertEquals(1, queue.size());
        processor.tick(700_000_000L, config, (n, a, s) -> PaymentSender.Result.SENT); assertEquals(0, queue.size());
    }
    @Test void ambiguousDispatchNotRetriedOrRerolled() {
        accept("Bob", "100", 0); config.winnerDelayMinimumMs = config.winnerDelayMaximumMs = 0;
        var processor = new WinnerPayoutProcessor(queue, new Random()); int[] calls = {0};
        PaymentSender sender = (n, a, s) -> { calls[0]++; return PaymentSender.Result.UNCERTAIN; };
        processor.tick(0, config, sender); processor.tick(1, config, sender); processor.tick(2, config, sender);
        assertEquals(1, calls[0]); assertEquals(1, random.calls); assertEquals(0, queue.size());
    }
    @Test void sharedGatePreventsTwoCommandsOnSameTick() {
        DispatchGate gate = new DispatchGate(); assertFalse(gate.reserve());
        gate.beginTick(); assertTrue(gate.reserve()); assertFalse(gate.reserve());
        gate.beginTick(); assertTrue(gate.reserve());
    }
    @Test void sessionResetClearsAllTransientGambleState() {
        accept("Bob", "100", 0); outgoing.record("Alice", BigDecimal.ONE, 0, OutgoingPaymentTracker.Source.ADVERTISING, 10000);
        var processor = new WinnerPayoutProcessor(queue, new Random());
        processor.cancel(); gamble.reset();
        assertEquals(0, queue.size()); assertEquals(0, receipts.size()); assertTrue(outgoing.snapshot().isEmpty());
    }
    @Test void disablingCancelsQueueButRetainsEchoAndDedupProtection() {
        accept("Bob", "100", 0); outgoing.record("Alice", BigDecimal.ONE, 0, OutgoingPaymentTracker.Source.ADVERTISING, 10000);
        config.gambleEnabled = false;
        new WinnerPayoutProcessor(queue, new Random()).tick(0, config, (n,a,s) -> { fail("Must not dispatch"); return null; });
        assertEquals(0, queue.size()); assertEquals(1, receipts.size()); assertEquals(1, outgoing.snapshot().size());
    }
    @Test void migratesStageTwoConfigWithoutLosingSettings() throws Exception {
        Path path = directory.resolve("autogamble.json");
        Files.writeString(path, "{\"configVersion\":1,\"enabled\":false,\"autoPayEnabled\":true,\"autoPayAmount\":37,\"winChance\":0.55}");
        ConfigManager manager = new ConfigManager(path, org.slf4j.LoggerFactory.getLogger("test")); manager.load();
        var c = manager.snapshot(); assertEquals(4, c.configVersion); assertFalse(c.enabled); assertTrue(c.autoPayEnabled);
        assertEquals(37, c.autoPayAmount); assertEquals(.55, c.winChance); assertEquals(2000, c.receiptDeduplicationWindowMs);
        assertTrue(c.incomingPaymentPatterns.isEmpty());
        manager.update(edit -> edit.incomingPaymentPatterns.add(pattern(EXAMPLE))); manager.load();
        assertEquals(1, new RegexPaymentParser(manager.snapshot().incomingPaymentPatterns).enabledCount());
    }
    @Test void parserConfigTypesRecoverAndWindowsValidate() throws Exception {
        Path path = directory.resolve("autogamble.json");
        Files.writeString(path, "{\"incomingPaymentPatterns\":[{\"enabled\":\"true\",\"regex\":\".*\"}]}");
        ConfigManager manager = new ConfigManager(path, org.slf4j.LoggerFactory.getLogger("test")); assertDoesNotThrow(manager::load);
        assertTrue(manager.snapshot().incomingPaymentPatterns.isEmpty());
        config.receiptDeduplicationWindowMs = -1; config.outgoingPaymentTrackingWindowMs = Long.MAX_VALUE; config.validate();
        assertEquals(100, config.receiptDeduplicationWindowMs); assertEquals(120000, config.outgoingPaymentTrackingWindowMs);
    }
}
