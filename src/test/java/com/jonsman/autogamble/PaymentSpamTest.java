package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentSpamTest {
    private static final long SECOND = 1_000_000_000L;
    @TempDir Path dir;
    private AutoGambleConfig config() {
        var c = new AutoGambleConfig(); c.gambleEnabled = true; return c;
    }
    private void burst(PaymentSpamTracker tracker, AutoGambleConfig c, long at) {
        tracker.observe("Bob", at, c);
        tracker.observe("bob", at + SECOND, c);
        tracker.observe("BOB", at + 2 * SECOND, c);
    }
    @Test void thresholdCombinesCaseVariantsAndKeepsPlayersSeparate() {
        var c = config(); var t = new PaymentSpamTracker();
        t.observe("Bob", 0, c); t.observe("Alice", SECOND, c); t.observe("bob", SECOND, c);
        assertEquals(0, t.pendingCount());
        t.observe("BOB", 2 * SECOND, c);
        assertEquals(1, t.pendingCount()); assertEquals(2, t.size());
    }
    @Test void paymentsOutsideWindowDoNotTriggerWarning() {
        var c = config(); var t = new PaymentSpamTracker();
        t.observe("Bob", 0, c); t.observe("Bob", 11 * SECOND, c); t.observe("Bob", 12 * SECOND, c);
        assertEquals(0, t.pendingCount());
    }
    @Test void cooldownStartsAtSuccessfulDelivery() {
        var c = config(); var t = new PaymentSpamTracker(); burst(t, c, 0);
        t.tick(5 * SECOND, c, (name, message) -> true);
        burst(t, c, 60 * SECOND); assertEquals(0, t.pendingCount());
        t.observe("Bob", 65 * SECOND, c); assertEquals(1, t.pendingCount());
    }
    @Test void blockedDeliveryRetriesAfterHalfSecond() {
        var c = config(); var t = new PaymentSpamTracker(); burst(t, c, 0);
        t.tick(2 * SECOND, c, (n, m) -> false); assertEquals(1, t.pendingCount());
        t.tick(2 * SECOND + 499_999_999L, c, (n, m) -> { fail("Retried too early"); return true; });
        t.tick(2 * SECOND + 500_000_000L, c, (n, m) -> true); assertEquals(0, t.pendingCount());
    }
    @Test void staleWarningsExpireAndDisableClearsState() {
        var c = config(); var t = new PaymentSpamTracker(); burst(t, c, 0);
        t.tick(33 * SECOND, c, (n, m) -> { fail("Sent stale warning"); return true; });
        assertEquals(0, t.pendingCount());
        burst(t, c, 70 * SECOND); c.spamPaymentWarningEnabled = false;
        t.tick(73 * SECOND, c, (n, m) -> { fail("Sent while disabled"); return true; });
        assertEquals(0, t.size()); assertEquals(0, t.pendingCount());
    }
    @Test void dryRunDoesNotSendAndLiveModeUsesMsg() {
        SpamWarningCommand.execute(true, "Bob", AutoGambleConfig.DEFAULT_SPAM_MESSAGE, command -> fail("Dry run sent a command"));
        List<String> sent = new ArrayList<>();
        SpamWarningCommand.execute(false, "Bob", "Please wait.", sent::add);
        assertEquals(List.of("msg Bob Please wait."), sent);
    }
    @Test void invalidWarningContentNeverReachesSender() {
        for (String message : List.of("", "x".repeat(201), "hello\nworld", "hello\u2028world", "hello\u200Bworld")) {
            assertThrows(IllegalArgumentException.class, () -> SpamWarningCommand.execute(false, "Bob", message, cmd -> fail()));
        }
        assertThrows(IllegalArgumentException.class, () -> SpamWarningCommand.execute(false, "Bob extra", "Wait", cmd -> fail()));
    }
    @Test void versionFourMigratesAndCustomWarningPersists() throws Exception {
        Path file = dir.resolve("autogamble.json");
        Files.writeString(file, "{\"configVersion\":4,\"autoPayAmount\":27.5,\"dryRunMode\":false}");
        var m = new ConfigManager(file, org.slf4j.LoggerFactory.getLogger("test")); m.load();
        var c = m.snapshot(); assertEquals(8, c.configVersion); assertEquals(27.5, c.autoPayAmount);
        assertFalse(c.dryRunMode); assertTrue(c.spamPaymentWarningEnabled);
        assertEquals(3, c.spamPaymentThreshold); assertEquals(10, c.spamPaymentWindowSeconds); assertEquals(60, c.spamWarningCooldownSeconds);
        m.update(next -> next.spamWarningMessage = "Please wait."); m.load();
        assertEquals("Please wait.", m.snapshot().spamWarningMessage);
    }
    @Test void settingsRejectInvalidRangesAndPreserveUnsavedSpamFields() {
        var draft = new SettingsDraft(config());
        draft.text(SettingsDraft.Field.SPAM_THRESHOLD, "1"); assertFalse(draft.validate().isEmpty());
        draft.text(SettingsDraft.Field.SPAM_THRESHOLD, "4"); assertTrue(draft.validate().isEmpty());
        assertEquals(4, draft.working.spamPaymentThreshold);
        draft.working.spamWarningMessage = "bad\nmessage"; assertFalse(draft.validate().isEmpty());
    }
}
