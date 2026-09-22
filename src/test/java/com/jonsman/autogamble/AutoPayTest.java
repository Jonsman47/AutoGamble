package com.jonsman.autogamble;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class AutoPayTest {
    private static Candidate player(String name) { return new Candidate(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name); }
    private final Candidate bob = player("Bob"), alex = player("Alex"), steve = player("Steve");
    private final PlayerSelectionManager selection = new PlayerSelectionManager();
    private final AutoPayManager manager = new AutoPayManager(new Random(72), new Random(99));
    private final AutoGambleConfig config = new AutoGambleConfig();
    { config.minimumPaymentBalance = BigDecimal.ZERO; config.minimumAutoPayDelaySeconds = 2; config.maximumAutoPayDelaySeconds = 5; }
    private final FakeEnvironment env = new FakeEnvironment();
    private class FakeEnvironment implements AutoPayEnvironment {
        boolean connected = true, blocked, succeeds = true, throwsOnDispatch;
        int scans, attempts, sent;
        int queueReads;
        boolean queueMode;
        Deque<Candidate> queued = new ArrayDeque<>();
        String amount;
        List<Candidate> players = List.of(bob, alex, steve);
        public boolean connected() { return connected; }
        public boolean inputBlocked() { return blocked; }
        public List<Candidate> eligiblePlayers() { scans++; return queueMode ? List.copyOf(queued) : players; }
        public boolean prepare(long now) { return !queueMode || !queued.isEmpty(); }
        public Optional<Candidate> nextQueuedRecipient(long now, boolean preferUnpaid) {
            queueReads++; return Optional.ofNullable(queued.peekFirst());
        }
        public boolean dispatch(Candidate target, String amount) {
            attempts++; this.amount = amount;
            if (throwsOnDispatch) throw new IllegalStateException("Disconnected during attempt");
            if (succeeds) { sent++; if (queueMode) queued.remove(target); }
            return succeeds;
        }
    }
    private void tick(long now) { manager.tick(now, config, env, selection); }
    private void start() { config.autoPayEnabled = true; tick(0); }
    private long due() { return manager.remainingNanos(0); }

    @Test void excludesLocalByUuidAndCaseInsensitiveNameAndMalformedEntries() {
        var local = player("Local");
        var list = Arrays.asList(local, new Candidate(local.id(), "Alias"), player("LOCAL"), bob,
                player("bad name"), player("ab"), player("12345678901234567"),
                new Candidate(null, "NoUUID"), new Candidate(new UUID(0, 0), "Zero"), null);
        assertEquals(List.of(bob), selection.eligible(list, local.id(), local.username()));
    }
    @Test void deduplicatesNamesAndIds() {
        assertEquals(List.of(bob), selection.eligible(List.of(bob, player("BOB"), new Candidate(bob.id(), "Alias")), UUID.randomUUID(), "Local"));
    }
    @Test void prefersUnpaidAndOnlyDispatchMarksHistory() {
        selection.markPaid("BOB");
        assertEquals(alex, selection.select(List.of(bob, alex), true, new Random(2)).orElseThrow());
        assertFalse(selection.wasPaid("Alex"));
    }
    @Test void resetsExhaustedCycleAndIncludesNewPlayers() {
        selection.markPaid("Bob"); selection.markPaid("Alex");
        assertEquals(steve, selection.select(List.of(bob, alex, steve), true, new Random()).orElseThrow());
        selection.markPaid("Steve");
        assertTrue(List.of(bob, alex).contains(selection.select(List.of(bob, alex), true, new Random()).orElseThrow()));
        assertTrue(selection.paidUsernames().isEmpty());
    }
    @Test void historyNeverSuppliesOfflineTargets() {
        selection.markPaid("Gone");
        assertEquals(bob, selection.select(List.of(bob), true, new Random()).orElseThrow());
        assertTrue(selection.select(List.of(), true, new Random()).isEmpty());
    }
    @Test void preferenceOffAllowsAlreadyPaidAndSelectionIsRandom() {
        selection.markPaid("Bob");
        Set<Candidate> seen = new HashSet<>();
        Random random = new Random(12);
        for (int i = 0; i < 100; i++) seen.add(selection.select(List.of(bob, alex), false, random).orElseThrow());
        assertEquals(Set.of(bob, alex), seen); assertTrue(selection.wasPaid("Bob"));
    }
    @Test void startsWithDelayAndUsesConfiguredAmount() {
        config.autoPayAmount = 1000.5; start();
        assertEquals(0, env.attempts); assertEquals(0, env.scans);
        long first = due(); tick(first - 1); assertEquals(0, env.attempts);
        tick(first); assertEquals(1, env.sent); assertEquals("1000.5", env.amount);
        assertEquals(1, selection.paidUsernames().size()); assertFalse(manager.isDue(first));
    }
    @Test void fullCyclePaysEachPlayerBeforeRepeating() {
        start();
        for (int i = 0; i < 3; i++) tick(due());
        assertEquals(3, env.sent); assertEquals(3, selection.paidUsernames().size());
        tick(due()); assertEquals(4, env.sent); assertEquals(1, selection.paidUsernames().size());
    }
    @Test void freshFractionalDelayWithinBoundsAfterEveryAttempt() {
        start(); long now = 0; Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long delay = manager.remainingNanos(now);
            assertTrue(delay >= 2_000_000_000L && delay <= 5_000_000_000L);
            delays.add(delay); now += delay; tick(now);
        }
        assertTrue(delays.size() > 90);
        assertTrue(delays.stream().anyMatch(d -> d % 1_000_000_000L != 0));
    }
    @Test void approvedQueueDoesNotAddSearchDelayToPaymentTimer() {
        config.minimumPaymentBalance = new BigDecimal("100000000");
        config.minimumAutoPayDelaySeconds=.2; config.maximumAutoPayDelaySeconds=1.6;
        env.queueMode=true; env.queued.addAll(List.of(bob,alex)); start();
        long first=due(); assertTrue(first>=200_000_000L && first<=1_600_000_000L);
        tick(first); assertEquals(1,env.sent); assertEquals(1,env.queueReads);
        long second=first+manager.remainingNanos(first);
        assertTrue(second-first>=200_000_000L && second-first<=1_600_000_000L);
        tick(second); assertEquals(2,env.sent);
        long third=second+manager.remainingNanos(second);
        tick(third); assertEquals(2,env.sent); assertEquals("DISCOVERING",manager.state());
        env.queued.add(steve); tick(third+50_000_000L);
        assertEquals(3,env.sent); assertTrue(manager.remainingNanos(third+50_000_000L)>=200_000_000L);
    }
    @Test void zeroMinimumDoesNotReadTheApprovedQueue() {
        env.queueMode=false; config.minimumPaymentBalance=BigDecimal.ZERO; start(); tick(due());
        assertEquals(1,env.sent); assertEquals(0,env.queueReads);
    }
    @Test void nextCycleUsesChangedDelayAndEqualBoundsWork() {
        start(); long first = due();
        config.minimumAutoPayDelaySeconds = config.maximumAutoPayDelaySeconds = 1.25;
        tick(first); assertEquals(1_250_000_000L, manager.remainingNanos(first));
    }
    @Test void eitherFlagDisablesAndReenableDoesNotFireOldDeadline() {
        start(); long first = due(); config.autoPayEnabled = false; tick(first);
        assertEquals(0, env.attempts); config.autoPayEnabled = true; tick(first + 10_000_000_000L);
        assertEquals(0, env.attempts); assertTrue(manager.remainingNanos(first + 10_000_000_000L) > 0);
        config.enabled = false; tick(due()); assertEquals(0, env.attempts);
        config.enabled = true; tick(100_000_000_000L); assertEquals(0, env.attempts);
    }
    @Test void emptyTabChecksAreDelayedWithoutPerTickScans() {
        env.players = List.of(); start(); long first = due(); tick(first);
        assertEquals(1, env.scans); assertEquals(0, env.attempts);
        for (int i = 1; i < 20; i++) tick(first + i * 50_000_000L);
        assertEquals(1, env.scans); tick(due()); assertEquals(2, env.scans);
    }
    @Test void screensPostponeUntilSafeWithBoundedRetry() {
        start(); long first = due(); env.blocked = true; tick(first);
        assertEquals(500_000_000L, manager.remainingNanos(first)); assertEquals(0, env.scans);
        tick(first + 499_000_000L); assertEquals(0, env.scans);
        tick(first + 500_000_000L); assertEquals(0, env.attempts);
        env.blocked = false; tick(first + 1_000_000_000L);
        assertEquals(1, env.sent); assertTrue(manager.remainingNanos(first + 1_000_000_000L) >= 2_000_000_000L);
    }
    @Test void failedDispatchAndExceptionsRescheduleWithoutMarkingPaid() {
        env.succeeds = false; start(); long first = due(); tick(first);
        assertEquals(1, env.attempts); assertTrue(selection.paidUsernames().isEmpty());
        assertTrue(manager.remainingNanos(first) >= 2_000_000_000L);
        env.throwsOnDispatch = true; long second = due(); assertDoesNotThrow(() -> tick(second));
        assertTrue(selection.paidUsernames().isEmpty()); assertTrue(manager.remainingNanos(second) > 0);
    }
    @Test void disconnectAndSessionResetDropDeadlineAndHistory() {
        start(); tick(due()); env.connected = false; tick(due());
        assertEquals(0, manager.remainingNanos(0));
        manager.reset(); selection.reset(); assertTrue(selection.paidUsernames().isEmpty());
        env.connected = true; tick(100_000_000_000L); assertEquals(1, env.sent);
    }
    @Test void longPauseDoesNotCauseCatchUpBurst() {
        start(); tick(1_000_000_000_000L); tick(1_000_000_000_000L);
        assertEquals(1, env.sent);
    }
    @Test void invalidAmountsAreSkippedWithNormalDelay() {
        config.autoPayAmount = 0; start(); long first = due(); tick(first);
        assertEquals(0, env.attempts); assertTrue(manager.remainingNanos(first) >= 2_000_000_000L);
    }
    @Test void formatsStablePlainDecimalAmounts() {
        assertEquals("1", AmountFormatter.format(1)); assertEquals("100", AmountFormatter.format(100));
        assertEquals("1000.5", AmountFormatter.format(1000.5));
        assertEquals("1", AmountFormatter.format(1.0000000003));
        assertEquals("0.3", AmountFormatter.format(.1 + .2));
        assertEquals("1.24", AmountFormatter.format(1.235));
        assertEquals("1000000000", AmountFormatter.format(1e9));
        for (double invalid : new double[]{0, -1, .001, Double.NaN, Double.POSITIVE_INFINITY, 1e10})
            assertThrows(IllegalArgumentException.class, () -> AmountFormatter.format(invalid));
    }
}
