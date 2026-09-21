package com.jonsman.autogamble.manager;

import java.util.*;

/** Pure selection policy over a fresh client-visible TAB snapshot. */
public final class PlayerSelectionManager {
    public static final long RECENT_COOLDOWN_NANOS = 10L * 60L * 1_000_000_000L;
    public record Candidate(UUID id, String username) {}
    private final Set<String> paid = new HashSet<>();
    private final Map<String, Long> recent = new HashMap<>();
    public List<Candidate> eligible(Collection<Candidate> entries, UUID localId, String localName) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (Candidate c : entries) {
            if (c == null || c.id() == null || c.id().equals(new UUID(0, 0)) || c.id().equals(localId)
                    || c.username() == null || !c.username().matches("[A-Za-z0-9_]{3,16}")
                    || c.username().equalsIgnoreCase(localName)) continue;
            if (ids.add(c.id())) unique.putIfAbsent(c.username().toLowerCase(Locale.ROOT), c);
        }
        return List.copyOf(unique.values());
    }
    public Optional<Candidate> select(List<Candidate> eligible, boolean preferUnpaid, java.util.random.RandomGenerator random) {
        return select(eligible, preferUnpaid, random, System.nanoTime());
    }
    /** The exact pre-baltop selection policy from v1.2.4. */
    public Optional<Candidate> selectLegacy(List<Candidate> eligible, boolean preferUnpaid, java.util.random.RandomGenerator random) {
        if (eligible.isEmpty()) return Optional.empty();
        List<Candidate> pool = eligible;
        if (preferUnpaid) {
            pool = eligible.stream().filter(c -> !wasPaid(c.username())).toList();
            if (pool.isEmpty()) { paid.clear(); pool = eligible; }
        }
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }
    public Optional<Candidate> select(List<Candidate> eligible, boolean preferUnpaid, java.util.random.RandomGenerator random, long now) {
        recent.entrySet().removeIf(e -> now - e.getValue() >= RECENT_COOLDOWN_NANOS);
        List<Candidate> pool = eligible.stream().filter(c -> !recentlyPaid(c.username(), now)).toList();
        if (pool.isEmpty()) return Optional.empty();
        if (preferUnpaid) {
            List<Candidate> unpaid = pool.stream().filter(c -> !wasPaid(c.username())).toList();
            if (unpaid.isEmpty()) { paid.clear(); } else pool = unpaid;
        }
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }
    public void markPaid(String username) { paid.add(username.toLowerCase(Locale.ROOT)); }
    public void markPaid(String username, long now) { markPaid(username); recent.put(username.toLowerCase(Locale.ROOT), now); }
    public boolean recentlyPaid(String username, long now) {
        Long paidAt = recent.get(username.toLowerCase(Locale.ROOT));
        return paidAt != null && now - paidAt < RECENT_COOLDOWN_NANOS;
    }
    public boolean wasPaid(String username) { return paid.contains(username.toLowerCase(Locale.ROOT)); }
    public Set<String> paidUsernames() { return Set.copyOf(paid); }
    public void reset() { paid.clear(); recent.clear(); }
}
