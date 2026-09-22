package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.baltop.BaltopEntry;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded, client-thread-owned queue of recently suggested and balance-approved players. */
public final class EligibleRecipientQueue {
    public static final int TARGET_SIZE = 20, LOW_WATERMARK = 5;
    public static final long ENTRY_TTL_NANOS = 30_000_000_000L;
    private record Entry(Candidate candidate, long foundAt) {}
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Set<String> names = new HashSet<>();
    private String lastPaid = "";
    public record Refill(int checked, int accepted) {}

    public Refill addApproved(List<Candidate> candidates, BigDecimal minimum,
            Function<String, BaltopEntry> lookup, long nowNanos, long nowMillis, long evidenceTtlMillis) {
        int checked=0, accepted=0;
        for (Candidate candidate:candidates) {
            if (!needsRefill(nowNanos) || checked>=100) break;
            checked++;
            if (candidate!=null && candidate.username()!=null
                    && PaymentBalanceFilter.approved(lookup.apply(candidate.username()),minimum,nowMillis,evidenceTtlMillis)
                    && offer(candidate,nowNanos)) accepted++;
        }
        return new Refill(checked,accepted);
    }

    public boolean offer(Candidate candidate, long now) {
        expire(now);
        if (candidate == null || candidate.username() == null || entries.size() >= TARGET_SIZE) return false;
        String key = candidate.username().toLowerCase(Locale.ROOT);
        if (!names.add(key)) return false;
        entries.addLast(new Entry(candidate, now));
        return true;
    }
    public List<Candidate> snapshot(long now) {
        expire(now);
        return entries.stream().map(Entry::candidate).toList();
    }
    public Optional<Candidate> next(long now, boolean preferUnpaid, Predicate<String> wasPaid) {
        expire(now);
        if (entries.isEmpty()) return Optional.empty();
        for (Entry entry : entries) if ((!preferUnpaid || !wasPaid.test(entry.candidate.username()))
                && (entries.size() == 1 || !entry.candidate.username().equalsIgnoreCase(lastPaid)))
            return Optional.of(entry.candidate);
        for (Entry entry : entries) if (entries.size() == 1 || !entry.candidate.username().equalsIgnoreCase(lastPaid))
            return Optional.of(entry.candidate);
        return Optional.of(entries.getFirst().candidate);
    }
    public void paid(String username) { remove(username); lastPaid = username; }
    public void remove(String username) {
        if (username == null) return;
        String key = username.toLowerCase(Locale.ROOT);
        entries.removeIf(e -> e.candidate.username().equalsIgnoreCase(username)); names.remove(key);
    }
    public boolean needsRefill(long now) { expire(now); return entries.size() < TARGET_SIZE; }
    public boolean low(long now) { expire(now); return entries.size() <= LOW_WATERMARK; }
    public void clear() { entries.clear(); names.clear(); lastPaid = ""; }
    private void expire(long now) {
        while (!entries.isEmpty() && now - entries.getFirst().foundAt >= ENTRY_TTL_NANOS) {
            names.remove(entries.removeFirst().candidate.username().toLowerCase(Locale.ROOT));
        }
    }
}
