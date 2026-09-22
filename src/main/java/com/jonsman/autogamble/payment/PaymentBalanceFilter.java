package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.baltop.BaltopEntry;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Optional gate over already-discovered recipients; it never generates player names. */
public final class PaymentBalanceFilter {
    public static final int MAX_CHECKS = 30;
    private PaymentBalanceFilter() {}
    /** Scanned balance evidence is usable for a bounded period; no server query is made here. */
    public static boolean approved(BaltopEntry entry, BigDecimal minimum, long nowMillis, long maxAgeMillis) {
        return entry != null && minimum != null && minimum.signum()>0 && entry.lastSeenInBaltop()>0
                && nowMillis>=entry.lastSeenInBaltop() && nowMillis-entry.lastSeenInBaltop()<=maxAgeMillis
                && entry.balance().compareTo(minimum)>=0;
    }
    public static List<Candidate> eligible(List<Candidate> discovered, BigDecimal minimum,
            Function<String,BigDecimal> knownBalance, Random random) {
        Objects.requireNonNull(discovered); Objects.requireNonNull(minimum);
        if (minimum.signum() < 0) throw new IllegalArgumentException("Minimum balance cannot be negative");
        if (minimum.signum() == 0) return discovered; // Exact legacy path: no cache lookup or reordering.
        Objects.requireNonNull(knownBalance); Objects.requireNonNull(random);
        List<Candidate> shuffled=new ArrayList<>(discovered);
        Collections.shuffle(shuffled,random);
        List<Candidate> allowed=new ArrayList<>(); int checked=0;
        for(Candidate candidate:shuffled) {
            if(checked++ >= MAX_CHECKS) break;
            BigDecimal value=knownBalance.apply(candidate.username());
            if(value!=null && value.compareTo(minimum)>=0) allowed.add(candidate);
        }
        return List.copyOf(allowed);
    }
}
