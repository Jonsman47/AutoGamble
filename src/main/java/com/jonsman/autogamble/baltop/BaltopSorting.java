package com.jonsman.autogamble.baltop;

import java.util.Comparator;
import java.util.List;

/** Stable leaderboard ordering for both newly ranked and legacy unranked cache entries. */
public final class BaltopSorting {
    public enum Sort { RANK, BALANCE_HIGH, BALANCE_LOW, USERNAME, SOURCE_PAGE }
    private BaltopSorting() {}
    public static void sort(List<BaltopEntry> entries, Sort sort) {
        // Mixed old/new caches cannot compare an unknown rank to a known one reliably.
        // The leaderboard itself is money-ordered, so use balances for the whole view until ranks are complete.
        entries.sort(comparator(sort == Sort.RANK && entries.stream().anyMatch(e -> e.rank() == null) ? Sort.BALANCE_HIGH : sort));
    }
    public static Comparator<BaltopEntry> comparator(Sort sort) {
        Comparator<BaltopEntry> name = Comparator.comparing(BaltopEntry::username,String.CASE_INSENSITIVE_ORDER);
        return switch (sort) {
            case RANK -> Comparator.comparing(BaltopEntry::rank,Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(BaltopEntry::balance,Comparator.reverseOrder()).thenComparing(name);
            case BALANCE_HIGH -> Comparator.comparing(BaltopEntry::balance,Comparator.reverseOrder()).thenComparing(name);
            case BALANCE_LOW -> Comparator.comparing(BaltopEntry::balance).thenComparing(name);
            case USERNAME -> name;
            case SOURCE_PAGE -> Comparator.comparingInt(BaltopEntry::page).thenComparing(BaltopEntry::rank,Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(BaltopEntry::balance,Comparator.reverseOrder()).thenComparing(name);
        };
    }
}
