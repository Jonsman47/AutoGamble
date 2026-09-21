package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.baltop.*;
import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import java.util.*;

public final class TargetingCandidates {
    private TargetingCandidates() {}
    public static List<String> money(BaltopDatabase.Snapshot snapshot, AutoGambleConfig c, String local,
                                     PlayerSelectionManager history, long now) {
        return BaltopDatabase.filter(snapshot.entries(),c.leaderboardMinimumBalance,c.leaderboardMaximumBalance,"").stream()
                .map(BaltopEntry::username)
                .filter(name -> eligible(name, local, history, now)).distinct().toList();
    }
    private static boolean eligible(String name, String local, PlayerSelectionManager history, long now) {
        return name != null && name.matches("[A-Za-z0-9_]{2,16}") && !name.equalsIgnoreCase(local)
                && !history.recentlyPaid(name, now);
    }
}
