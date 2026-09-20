package com.jonsman.autogamble.targeting;

import java.util.List;

public record LeaderboardSnapshot(List<LeaderboardRecord> money, List<LeaderboardRecord> economy, long fetchedAt) {
    public LeaderboardSnapshot {
        money = money == null ? List.of() : List.copyOf(money);
        economy = economy == null ? List.of() : List.copyOf(economy);
    }
    public static LeaderboardSnapshot empty() { return new LeaderboardSnapshot(List.of(), List.of(), 0); }
}
