package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.manager.GambleManager;
import com.jonsman.autogamble.ui.ParserTestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DonutSmpTest {
    @TempDir Path dir;
    private final AutoGambleConfig config = new AutoGambleConfig();
    private PaymentParser.IncomingPayment parse(String token) {
        return RegexPaymentParser.fromConfig(config).parse("JonsmanV6or7 paid you $ " + token, "Local").orElseThrow();
    }
    private void money(String token, String expected) { assertEquals(0, new BigDecimal(expected).compareTo(parse(token).amount()), token); }
    @Test void confirmedPlainParses() { money("1", "1"); money("100", "100"); }
    @Test void paidYouPhraseRequired() {
        for (String text : List.of("Bob sent you $ 1", "Bob paid Alice $ 1", "<Bob> Bob paid you $ 1", "You paid Bob $ 1"))
            assertTrue(RegexPaymentParser.fromConfig(config).parse(text, "Local").isEmpty());
    }
    @Test void dollarSignRequired() { assertTrue(RegexPaymentParser.fromConfig(config).parse("Bob paid you 1k", "Local").isEmpty()); }
    @Test void extractsExactUsername() { assertEquals("JonsmanV6or7", parse("1").sender()); }
    @Test void oneK() { money("1k", "1000"); money("1K", "1000"); }
    @Test void tenK() { money("10k", "10000"); }
    @Test void hundredK() { money("100k", "100000"); }
    @Test void upperM() { money("1M", "1000000"); }
    @Test void lowerM() { money("1m", "1000000"); }
    @Test void billions() { money("1B", "1000000000"); money("1.2b", "1200000000"); money("1.25B", "1250000000"); }
    @Test void trillions() { money("1T", "1000000000000"); money("1t", "1000000000000"); }
    @Test void decimalThousands() { money("2.5k", "2500"); }
    @Test void decimalMillions() { money("1.5M", "1500000"); money("0.5M", "500000"); }
    @Test void commaValuesStillWork() { money("1,000", "1000"); money("1,000.50", "1000.50"); }
    @Test void decimalPlainStillWorks() { money("1000.50", "1000.50"); }
    @Test void malformedSuffixesRejected() {
        for (String bad : List.of("k", "M", "1kk", "1km", "1.2.3k", "abc1k", "1q", "NaN", "Infinity", "+1k", "1,00k"))
            assertTrue(MoneyParser.parse(bad).isEmpty(), bad);
    }
    @Test void negativeSuffixRejected() { assertTrue(MoneyParser.parse("-1k").isEmpty()); }
    @Test void exponentRejected() { assertTrue(MoneyParser.parse("1e6").isEmpty()); }
    private GambleManager manager(PaymentQueue q, ReceiptDeduplicator d, OutgoingPaymentTracker o, Random random) {
        return new GambleManager(RegexPaymentParser.fromConfig(config), q, d, o, random, p -> {});
    }
    @Test void expandedAmountRespectsMaximumBet() {
        config.gambleEnabled = true;
        var q = new PaymentQueue(); var g = manager(q, new ReceiptDeduplicator(), new OutgoingPaymentTracker(), new Random() {
            @Override public double nextDouble() { fail("Out-of-range bet rolled"); return 0; }
        });
        assertEquals(GambleManager.Outcome.INVALID, g.accept(parse("2M"), "Local", 0, config)); assertEquals(0, q.size());
    }
    @Test void canonicalValuesEqualAndOutgoingMatchesSuffix() {
        assertEquals(MoneyParser.parse("1000").orElseThrow(), MoneyParser.parse("1k").orElseThrow());
        var o = new OutgoingPaymentTracker(); o.record("JonsmanV6or7", new BigDecimal("1000"), 0, OutgoingPaymentTracker.Source.ADVERTISING, 10000);
        config.gambleEnabled = true;
        assertEquals(GambleManager.Outcome.OUTGOING, manager(new PaymentQueue(), new ReceiptDeduplicator(), o, new Random()).accept(parse("1k"), "Local", 1, config));
    }
    @Test void parserTestDisplaysExpandedValueOnly() {
        config.dryRunMode = false; config.gambleEnabled = true;
        assertEquals("Matched: JonsmanV6or7 — $1000000", ParserTestService.test(config, "JonsmanV6or7 paid you $ 1M", "Local"));
        // The service accepts only config/text and has no dispatch or manager dependencies.
    }
    @Test void endToEndOneRollDryRunNeverSends() {
        config.gambleEnabled = true; config.winChance = 1;
        config.winnerDelayMinimumMs = config.winnerDelayMaximumMs = 0;
        var q = new PaymentQueue(); var d = new ReceiptDeduplicator(); var o = new OutgoingPaymentTracker(); int[] rolls = {0};
        var g = manager(q, d, o, new Random() { @Override public double nextDouble() { rolls[0]++; return .5; } });
        var message = new ReceivedMessage("JonsmanV6or7 paid you $ 1M", ReceivedMessage.Channel.SYSTEM);
        assertEquals(GambleManager.Outcome.WIN, g.receive(message, "Local", 0, config));
        assertEquals(new BigDecimal("2000000"), q.peek().orElseThrow().amount());
        assertEquals(GambleManager.Outcome.DUPLICATE, g.receive(message, "Local", 1, config));
        var processor = new WinnerPayoutProcessor(q, new Random());
        PaymentSender sender = (name, amount, source) -> PaymentExecution.execute(config.dryRunMode, name, amount, source, 0, 10000, o,
                command -> fail("Dry run sent a real command"));
        processor.tick(0, config, sender); processor.tick(1, config, sender);
        assertEquals(1, rolls[0]); assertEquals(0, q.size()); assertTrue(config.dryRunMode);
    }
    @Test void migrationPreservesCustomPatternsAndAddsSeparateBuiltin() throws Exception {
        Path path = dir.resolve("autogamble.json");
        Files.writeString(path, "{\"configVersion\":3,\"autoPayAmount\":5,\"incomingPaymentPatterns\":[{\"enabled\":false,\"regex\":\"(?<sender>Bob) received (?<amount>[0-9]+)\"}]}");
        var m = new ConfigManager(path, org.slf4j.LoggerFactory.getLogger("test")); m.load();
        var c = m.snapshot(); assertEquals(6, c.configVersion); assertEquals(5, c.autoPayAmount);
        assertEquals(1, c.incomingPaymentPatterns.size()); assertFalse(c.incomingPaymentPatterns.getFirst().enabled);
        assertTrue(c.donutSmpIncomingEnabled); assertEquals(1, RegexPaymentParser.fromConfig(c).enabledCount());
        m.load(); assertEquals(1, m.snapshot().incomingPaymentPatterns.size());
    }
    @Test void builtinIsNotDuplicatedAndCanBeDisabled() {
        config.incomingPaymentPatterns.add(new AutoGambleConfig.IncomingPattern(true, DonutSmpPattern.REGEX));
        assertEquals(1, RegexPaymentParser.fromConfig(config).enabledCount());
        config.incomingPaymentPatterns.getFirst().enabled = false;
        assertEquals(0, RegexPaymentParser.fromConfig(config).enabledCount());
        config.incomingPaymentPatterns.clear(); config.donutSmpIncomingEnabled = false;
        assertEquals(0, RegexPaymentParser.fromConfig(config).enabledCount());
    }
    @Test void loaderMetadataTargets0193() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(">=0.19.3", json.getAsJsonObject("depends").get("fabricloader").getAsString());
            assertEquals("26.2", json.getAsJsonObject("depends").get("minecraft").getAsString());
        }
        assertTrue(Files.readString(Path.of("gradle.properties")).contains("loader_version=0.19.3"));
    }
    @Test void formattingAndWhitespaceAreTolerated() {
        assertTrue(RegexPaymentParser.fromConfig(config).parse("§7JonsmanV6or7\t paid   you §a$ §f1M", "Local").isPresent());
        assertTrue(RegexPaymentParser.fromConfig(config).parse("  JonsmanV6or7 paid you $1k  ", "Local").isPresent());
    }
    @Test void signedPlayerChatStillRejected() {
        assertTrue(RegexPaymentParser.fromConfig(config).parse(new ReceivedMessage("Bob paid you $ 1k", ReceivedMessage.Channel.PLAYER_CHAT), "Local").isEmpty());
    }
    @Test void defaultConfigHasUsableBuiltin() { assertEquals(1, RegexPaymentParser.fromConfig(config).enabledCount()); assertTrue(config.dryRunMode); }
    @Test void overflowRejectedAfterExpansion() { assertTrue(MoneyParser.parse("2T").isEmpty()); assertTrue(MoneyParser.parse("9999999999999999999999999999999T").isEmpty()); }
    @Test void suffixDeduplicatesAgainstPlainRendering() {
        var d = new ReceiptDeduplicator(); assertTrue(d.accept(parse("1k"), 0, 2000)); assertFalse(d.accept(parse("1000"), 1, 2000));
    }
}
