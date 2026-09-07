package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class FoundationTest {
    @TempDir Path directory;
    private ConfigManager manager() { return new ConfigManager(directory.resolve("autogamble.json"), LoggerFactory.getLogger("test")); }
    @Test void defaultsPersistAndEditsSurviveReload() throws Exception {
        var m = manager(); m.load();
        assertFalse(m.snapshot().autoPayEnabled);
        assertEquals(5, m.snapshot().maximumAutoPayDelaySeconds);
        m.update(c -> { c.enabled = false; c.minimumAutoPayDelaySeconds = 1; c.maximumAutoPayDelaySeconds = 10; });
        var restored = manager(); restored.load();
        assertFalse(restored.snapshot().enabled);
        assertEquals(10, restored.snapshot().maximumAutoPayDelaySeconds);
        var copy = restored.snapshot(); copy.enabled = true;
        assertFalse(restored.snapshot().enabled);
    }
    @Test void repairsRangesAndNonFiniteValues() {
        var c = new AutoGambleConfig();
        c.minimumAutoPayDelaySeconds = -2; c.maximumAutoPayDelaySeconds = -9;
        c.winChance = 5; c.autoPayAmount = -3; c.payoutMultiplier = Double.NaN;
        c.minimumBet = 100; c.maximumBet = 2;
        c.winnerDelayMinimumMs = 500; c.winnerDelayMaximumMs = -1;
        c.validate();
        assertTrue(c.minimumAutoPayDelaySeconds > 0);
        assertTrue(c.maximumAutoPayDelaySeconds >= c.minimumAutoPayDelaySeconds);
        assertEquals(1, c.winChance); assertEquals(0, c.autoPayAmount);
        assertEquals(2, c.payoutMultiplier); assertEquals(100, c.maximumBet);
        assertEquals(500, c.winnerDelayMaximumMs);
    }
    @Test void corruptFilesRecoverAndAreBackedUp() throws Exception {
        for (String input : new String[]{"{oops", "null", "[]", "{\"enabled\":\"false\"}", "{\"winnerDelayMinimumMs\":1e100}"}) {
            Files.writeString(directory.resolve("autogamble.json"), input);
            var m = manager(); assertDoesNotThrow(m::load);
            assertTrue(m.snapshot().enabled);
            assertTrue(Files.readString(directory.resolve("autogamble.json")).contains("configVersion"));
        }
        try (var files = Files.list(directory)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("invalid-"))); }
    }
    @Test void migrationAndFutureProtection() throws Exception {
        var path = directory.resolve("autogamble.json");
        Files.writeString(path, "{\"autoPayAmount\":3}");
        var m = manager(); m.load();
        assertEquals(3, m.snapshot().autoPayAmount); assertEquals(7, m.snapshot().configVersion);
        String future = "{\"configVersion\":8,\"enabled\":true}";
        Files.writeString(path, future); m.load();
        assertFalse(m.snapshot().enabled); assertFalse(m.save()); assertEquals(future, Files.readString(path));
    }
    @Test void unreadableLocationDoesNotThrow() throws Exception {
        Files.createDirectory(directory.resolve("autogamble.json"));
        var m = manager(); assertDoesNotThrow(m::load); assertFalse(m.save());
    }
    @Test void oversizedFloatingPointNumbersRecover() throws Exception {
        Files.writeString(directory.resolve("autogamble.json"), "{\"autoPayAmount\":1e999,\"winChance\":-1e999}");
        var m = manager(); assertDoesNotThrow(m::load);
        assertEquals(1, m.snapshot().autoPayAmount); assertEquals(.5, m.snapshot().winChance);
    }
    @Test void independentRandomDeadlinesAndReset() {
        var m = new AutoPayManager(); var c = new AutoGambleConfig(); var random = new Random(7);
        int dueAtMidpoint = 0;
        for (int i = 0; i < 100; i++) {
            m.scheduleAfterPayment(0, c, random);
            assertFalse(m.isDue(1_999_999_999L)); assertTrue(m.isDue(5_000_000_000L));
            if (m.isDue(3_500_000_000L)) dueAtMidpoint++;
        }
        assertTrue(dueAtMidpoint > 10 && dueAtMidpoint < 90);
        m.reset(); assertFalse(m.isDue(Long.MAX_VALUE));
        c.minimumAutoPayDelaySeconds = c.maximumAutoPayDelaySeconds = 2;
        m.scheduleAfterPayment(0, c, random); assertTrue(m.isDue(2_000_000_000L));
    }
    @Test void queueAndHistoryAreSessionLocalAndParserIsInactive() {
        var q = new PaymentQueue();
        q.offer(new PaymentQueue.Payment("PlayerA", BigDecimal.ONE, PaymentQueue.Purpose.ADVERTISEMENT, 100));
        assertTrue(q.pollDue(99).isEmpty()); assertEquals(PaymentQueue.Purpose.ADVERTISEMENT, q.pollDue(100).orElseThrow().purpose());
        assertThrows(IllegalArgumentException.class, () -> new PaymentQueue.Payment("bad name", BigDecimal.ONE, PaymentQueue.Purpose.WINNER_PAYOUT, 0));
        q.offer(new PaymentQueue.Payment("PlayerA", BigDecimal.ONE, PaymentQueue.Purpose.WINNER_PAYOUT, 200));
        q.reset(); assertEquals(0, q.size());
        var s = new PlayerSelectionManager(); s.markPaid("PlayerA"); assertTrue(s.wasPaid("playera")); s.reset(); assertFalse(s.wasPaid("PlayerA"));
        assertTrue(PaymentParser.inactive().parse("You paid PlayerA $1", "Me").isEmpty());
    }
}
