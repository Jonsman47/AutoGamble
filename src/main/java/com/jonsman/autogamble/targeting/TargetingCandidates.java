package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import java.util.*;

public final class TargetingCandidates {
    private TargetingCandidates() {}
    public static List<String> money(LeaderboardSnapshot snapshot, AutoGambleConfig c, String local,
                                     PlayerSelectionManager history, long now) {
        return snapshot.money().stream()
                .filter(r -> r.value().compareTo(c.leaderboardMinimumBalance) >= 0)
                .filter(r -> c.leaderboardMaximumBalance == null || r.value().compareTo(c.leaderboardMaximumBalance) <= 0)
                .map(LeaderboardRecord::username).filter(name -> eligible(name, local, history, now)).distinct().toList();
    }
    public static List<String> economy(LeaderboardSnapshot snapshot, String local,
                                       PlayerSelectionManager history, long now) {
        return snapshot.economy().stream().map(LeaderboardRecord::username)
                .filter(name -> eligible(name, local, history, now)).distinct().toList();
    }
    private static boolean eligible(String name, String local, PlayerSelectionManager history, long now) {
        return name != null && name.matches("[A-Za-z0-9_]{2,16}") && !name.equalsIgnoreCase(local)
                && !history.recentlyPaid(name, now);
    }
}
