package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.baltop.*;
import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.payment.FailedTargetBlacklist;
import java.util.*;

public final class TargetingCandidates {
    private TargetingCandidates() {}
    public record Pool(int total, int balanceEligible, int localExcluded, int recentExcluded,
                       int invalidExcluded, int blockedExcluded, List<String> candidates) {}
    public static Pool summary(BaltopDatabase.Snapshot snapshot, AutoGambleConfig c, String local,
                               PlayerSelectionManager history, FailedTargetBlacklist failed, long now) {
        int balance=0, self=0, recent=0, invalid=0, blocked=0;
        Map<String,String> names=new LinkedHashMap<>();
        for(BaltopEntry entry:snapshot.entries()) {
            if(entry.balance().compareTo(c.leaderboardMinimumBalance)<0
                    || c.leaderboardMaximumBalance!=null && entry.balance().compareTo(c.leaderboardMaximumBalance)>0) continue;
            balance++;
            String name=entry.username();
            if(name.equalsIgnoreCase(local)){self++;continue;}
            if(history.recentlyPaid(name,now)){recent++;continue;}
            if(!PrefixValid.valid(name,local,c.excludeNumericOnlyNames)){invalid++;continue;}
            if(failed!=null && failed.contains(name,now)){blocked++;continue;}
            names.putIfAbsent(name.toLowerCase(Locale.ROOT),name);
        }
        return new Pool(snapshot.entries().size(),balance,self,recent,invalid,blocked,List.copyOf(names.values()));
    }
    private static final class PrefixValid {
        static boolean valid(String name,String local,boolean skipNumeric) {
            return name!=null && name.matches("[A-Za-z0-9_]{2,16}") && !name.equalsIgnoreCase(local)
                    && (!skipNumeric || !name.matches("[0-9]+"));
        }
    }
    public static List<String> money(BaltopDatabase.Snapshot snapshot, AutoGambleConfig c, String local,
                                     PlayerSelectionManager history, long now) {
        return summary(snapshot,c,local,history,null,now).candidates();
    }
}
