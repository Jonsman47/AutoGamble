package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.ui.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir Path dir;
    private ConfigManager manager() { return new ConfigManager(dir.resolve("autogamble.json"), org.slf4j.LoggerFactory.getLogger("test")); }
    @Test void migrationPreservesStageThreeValuesAndAddsSafeDefault() throws Exception {
        Files.writeString(dir.resolve("autogamble.json"), "{\"configVersion\":2,\"autoPayEnabled\":true,\"gambleEnabled\":true,\"autoPayAmount\":27.5,\"winChance\":0.55,\"receiptDeduplicationWindowMs\":3500}");
        var m = manager(); m.load(); var c = m.snapshot();
        assertEquals(5, c.configVersion); assertTrue(c.dryRunMode); assertTrue(c.autoPayEnabled); assertTrue(c.gambleEnabled);
        assertEquals(27.5, c.autoPayAmount); assertEquals(.55, c.winChance); assertEquals(3500, c.receiptDeduplicationWindowMs);
    }
    @Test void dryRunDefaultsTrueAndPersistsExplicitChoice() {
        var m = manager(); m.load(); assertTrue(m.snapshot().dryRunMode);
        m.update(c -> c.dryRunMode = false); m.load(); assertFalse(m.snapshot().dryRunMode);
    }
    @Test void dryRunAdvertisingNeverCallsRealCommand() { dryRun(OutgoingPaymentTracker.Source.ADVERTISING); }
    @Test void dryRunWinnerNeverCallsRealCommand() { dryRun(OutgoingPaymentTracker.Source.GAMBLE_PAYOUT); }
    private void dryRun(OutgoingPaymentTracker.Source source) {
        var tracker = new OutgoingPaymentTracker();
        assertEquals(PaymentSender.Result.SENT, PaymentExecution.execute(true, "Bob", BigDecimal.ONE, source, 0, 10000, tracker,
                command -> fail("Dry run reached real network callback")));
        assertTrue(tracker.snapshot().isEmpty());
    }
    @Test void disablingDryRunAllowsRealPathAndRecordsBeforeSend() {
        var tracker = new OutgoingPaymentTracker(); List<String> commands = new ArrayList<>();
        assertEquals(PaymentSender.Result.SENT, PaymentExecution.execute(false, "Bob", new BigDecimal("2.50"),
                OutgoingPaymentTracker.Source.GAMBLE_PAYOUT, 0, 10000, tracker, command -> {
                    assertEquals(1, tracker.snapshot().size()); commands.add(command);
                }));
        assertEquals(List.of("pay Bob 2.5"), commands);
    }
    @Test void guiRejectsInvertedPayDelay() {
        var draft = new SettingsDraft(new AutoGambleConfig());
        draft.text(SettingsDraft.Field.PAY_MIN, "10"); draft.text(SettingsDraft.Field.PAY_MAX, "2");
        assertTrue(draft.validate().stream().anyMatch(s -> s.contains("Maximum Pay Delay")));
    }
    @Test void guiRejectsInvalidWinChance() {
        for (double chance : new double[]{-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var c = new AutoGambleConfig(); c.winChance = chance; assertFalse(SettingsValidation.errors(c).isEmpty());
        }
    }
    @Test void invalidTextDoesNotCorruptConfig() throws Exception {
        var m = manager(); m.load(); String original = Files.readString(m.path());
        var draft = new SettingsDraft(m.snapshot());
        for (String invalid : List.of("", "abc", "NaN", "Infinity", "-1", "1e999", "1;pay Bob 1")) {
            draft.text(SettingsDraft.Field.AMOUNT, invalid); assertFalse(draft.validate().isEmpty());
            assertEquals(original, Files.readString(m.path()));
        }
        var bad = m.snapshot(); bad.maximumAutoPayDelaySeconds = 0;
        assertFalse(m.commit(bad)); assertEquals(original, Files.readString(m.path()));
    }
    @Test void invalidIntegersAndRangesAreRejectedWithoutClamping() {
        var draft = new SettingsDraft(new AutoGambleConfig()); draft.text(SettingsDraft.Field.WIN_MIN, "1.5"); assertFalse(draft.validate().isEmpty());
        draft.text(SettingsDraft.Field.WIN_MIN, "1000"); draft.text(SettingsDraft.Field.WIN_MAX, "10"); assertFalse(draft.validate().isEmpty());
        draft.text(SettingsDraft.Field.WIN_MAX, "2000"); draft.text(SettingsDraft.Field.TRACK, "0"); assertFalse(draft.validate().isEmpty());
    }
    @Test void validDraftCommitsImmediatelyAndPersists() {
        var m = manager(); m.load(); long revision = m.revision(); var draft = new SettingsDraft(m.snapshot());
        draft.text(SettingsDraft.Field.AMOUNT, "2.5"); draft.working.winChance = .555;
        assertTrue(draft.validate().isEmpty()); assertTrue(m.commit(draft.working)); assertTrue(m.revision() > revision);
        assertEquals(.555, m.snapshot().winChance); m.load(); assertEquals(2.5, m.snapshot().autoPayAmount);
    }
    @Test void globalDisableAndReenableResetAutoPay() {
        var before = new AutoGambleConfig(); before.autoPayEnabled = true;
        var after = new AutoGambleConfig(); after.enabled = false;
        var change = RuntimeSettingsChange.between(before, after); assertTrue(change.resetAutoPay()); assertTrue(change.clearPayouts());
        assertTrue(RuntimeSettingsChange.between(after, before).resetAutoPay());
    }
    @Test void gamblingDisableClearsPendingPayoutsWithoutRestartingAdvertising() {
        var before = new AutoGambleConfig(); before.autoPayEnabled = before.gambleEnabled = true;
        var after = new AutoGambleConfig(); after.autoPayEnabled = true;
        var change = RuntimeSettingsChange.between(before, after); assertTrue(change.clearPayouts()); assertFalse(change.resetAutoPay());
    }
    @Test void switchingDryModeCannotReleaseSimulatedQueueIntoRealPayments() {
        var before = new AutoGambleConfig(); var after = new AutoGambleConfig(); after.dryRunMode = false;
        var change = RuntimeSettingsChange.between(before, after);
        assertTrue(change.clearPayouts()); assertTrue(change.resetAutoPay()); assertTrue(change.resetSimulation());
    }
    @Test void realPaymentConfirmationOccursOnlyForDryToLive() {
        var before = new AutoGambleConfig(); var after = new AutoGambleConfig();
        assertFalse(RuntimeSettingsChange.requiresRealPaymentConfirmation(before, after));
        after.dryRunMode = false; assertTrue(RuntimeSettingsChange.requiresRealPaymentConfirmation(before, after));
        before.dryRunMode = false; assertFalse(RuntimeSettingsChange.requiresRealPaymentConfirmation(before, after));
    }
    @Test void settingsCommandRoutesCaseInsensitiveArgumentLocally() {
        int[] opened = {0};
        for (String command : List.of("settings Gamble", "settings gamble", "settings GAMBLE", " settings   Gamble "))
            assertTrue(SettingsCommandRouter.intercept(command, () -> opened[0]++));
        assertEquals(4, opened[0]);
    }
    @Test void unrelatedSettingsCommandsAreUntouched() {
        for (String command : List.of("settings", "settings SomethingElse", "settings Gamble more", "settings Gambling", "pay Bob 1"))
            assertFalse(SettingsCommandRouter.intercept(command, () -> fail("Unrelated command intercepted")));
    }
    @Test void parserTestOnlyReturnsDataWithoutTouchingGambleState() {
        var c = new AutoGambleConfig(); c.gambleEnabled = true; c.dryRunMode = false;
        c.incomingPaymentPatterns.add(new AutoGambleConfig.IncomingPattern(true, "(?<sender>Bob) paid you \\$(?<amount>[0-9]+)"));
        var q = new PaymentQueue(); var receipts = new ReceiptDeduplicator(); var outgoing = new OutgoingPaymentTracker();
        assertEquals("Matched: Bob — $100", ParserTestService.test(c, "Bob paid you $100", "Local"));
        assertEquals(0, q.size()); assertEquals(0, receipts.size()); assertTrue(outgoing.snapshot().isEmpty());
        assertEquals("No pattern matched", ParserTestService.test(c, "hello", "Local"));
    }
    @Test void invalidRegexAndMissingGroupsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PatternConfigService.validate("["));
        assertThrows(IllegalArgumentException.class, () -> PatternConfigService.validate(".*"));
        assertDoesNotThrow(() -> PatternConfigService.validate("(?<sender>.+) (?<amount>.+)"));
    }
    @Test void patternImportDoesNotImportLivePaymentFlags() throws Exception {
        Files.writeString(dir.resolve("autogamble.json"), "{\"dryRunMode\":false,\"incomingPaymentPatterns\":[{\"enabled\":true,\"regex\":\"(?<sender>Bob) (?<amount>[0-9]+)\"}]}");
        var draft = new SettingsDraft(new AutoGambleConfig()); draft.working.incomingPaymentPatterns = PatternConfigService.read(dir.resolve("autogamble.json"));
        assertTrue(draft.working.dryRunMode); assertEquals(1, draft.working.incomingPaymentPatterns.size());
    }
    @Test void winningJobHasPriorityWhenBothSourcesAreDue() {
        var gate = new DispatchGate(); var tracker = new OutgoingPaymentTracker(); var q = new PaymentQueue();
        q.offer(new PaymentQueue.Payment("Winner", BigDecimal.TEN, PaymentQueue.Purpose.WINNER_PAYOUT, 0));
        var c = new AutoGambleConfig(); c.gambleEnabled = true; c.winnerDelayMinimumMs = c.winnerDelayMaximumMs = 0;
        var processor = new WinnerPayoutProcessor(q, new Random()); List<String> sent = new ArrayList<>();
        PaymentSender sender = (name, amount, source) -> gate.reserve()
                ? PaymentExecution.execute(false, name, amount, source, 0, 10000, tracker, sent::add) : PaymentSender.Result.RETRY_LATER;
        processor.tick(0, c, sender); gate.beginTick(); processor.tick(1, c, sender);
        assertEquals(PaymentSender.Result.RETRY_LATER, sender.sendPayment("Advert", BigDecimal.ONE, OutgoingPaymentTracker.Source.ADVERTISING));
        assertEquals(List.of("pay Winner 10"), sent); assertEquals(0, q.size());
    }
}
