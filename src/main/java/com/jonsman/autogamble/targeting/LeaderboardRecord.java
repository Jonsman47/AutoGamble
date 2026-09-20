package com.jonsman.autogamble.targeting;

import java.math.BigDecimal;
import java.util.*;

public record LeaderboardRecord(String username, BigDecimal value, Integer rank, long observedAt,
                                Set<String> sources) {
    public LeaderboardRecord {
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}")) throw new IllegalArgumentException("Invalid username");
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Invalid value");
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }
    public String key() { return username.toLowerCase(Locale.ROOT); }
    public LeaderboardRecord merge(LeaderboardRecord other) {
        if (!key().equals(other.key())) throw new IllegalArgumentException("Different players");
        LeaderboardRecord preferred = other.observedAt > observedAt ? other : this;
        Set<String> merged = new LinkedHashSet<>(sources); merged.addAll(other.sources);
        Integer bestRank = rank == null ? other.rank : other.rank == null ? rank : Math.min(rank, other.rank);
        return new LeaderboardRecord(preferred.username, preferred.value, bestRank,
                Math.max(observedAt, other.observedAt), merged);
    }
}
