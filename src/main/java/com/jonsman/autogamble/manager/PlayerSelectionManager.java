package com.jonsman.autogamble.manager;

import java.util.*;

/** Pure selection policy over a fresh client-visible TAB snapshot. */
public final class PlayerSelectionManager {
    public record Candidate(UUID id, String username) {}
    private final Set<String> paid = new HashSet<>();
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
        if (eligible.isEmpty()) return Optional.empty();
        List<Candidate> pool = eligible;
        if (preferUnpaid) {
            pool = eligible.stream().filter(c -> !wasPaid(c.username())).toList();
            if (pool.isEmpty()) { paid.clear(); pool = eligible; }
        }
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }
    public void markPaid(String username) { paid.add(username.toLowerCase(Locale.ROOT)); }
    public boolean wasPaid(String username) { return paid.contains(username.toLowerCase(Locale.ROOT)); }
    public Set<String> paidUsernames() { return Set.copyOf(paid); }
    public void reset() { paid.clear(); }
}
