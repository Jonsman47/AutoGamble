package com.jonsman.autogamble.baltop;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.targeting.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BaltopLiveRegressionTest {
    @TempDir Path folder;
    private static BaltopEntry row(String name,String balance,Integer rank,int page) {
        return new BaltopEntry(name,new BigDecimal(balance),rank,page,1);
    }
    @Test void rankSortAndLegacyMoneyFallbackAreDeterministic() {
        List<BaltopEntry> ranked=new ArrayList<>(List.of(row("Zeta","900",3,1),row("Alpha","1500",1,1),row("Beta","1200",2,1)));
        BaltopSorting.sort(ranked,BaltopSorting.Sort.RANK);
        assertEquals(List.of("Alpha","Beta","Zeta"),ranked.stream().map(BaltopEntry::username).toList());
        ranked.add(row("Legacy","1300",null,1));
        BaltopSorting.sort(ranked,BaltopSorting.Sort.RANK);
        assertEquals(List.of("Alpha","Legacy","Beta","Zeta"),ranked.stream().map(BaltopEntry::username).toList());
        BaltopSorting.sort(ranked,BaltopSorting.Sort.BALANCE_LOW);
        assertEquals("Zeta",ranked.getFirst().username());
        BaltopSorting.sort(ranked,BaltopSorting.Sort.USERNAME);
        assertEquals("Alpha",ranked.getFirst().username());
    }
    @Test void rankFallsBackFromObservedInventoryGeometryNotParsedCount() {
        var page=new BaltopParser.Page(3,List.of(
                new BaltopParser.Item(0,"head","PlayerA",List.of("$1T"),""),
                new BaltopParser.Item(3,"head","PlayerB",List.of("$900B"),""),
                new BaltopParser.Item(53,"arrow","Next Page",List.of(),"")),54,"Most Money (Page 3)");
        var parsed=BaltopParser.parse(page,1);
        assertEquals(91,parsed.entries().get(0).rank());
        assertEquals(94,parsed.entries().get(1).rank());
        assertNull(BaltopParser.rank("Rank1orFeed",List.of("Balance: $5B")));
        assertEquals(5,BaltopParser.rank("#5 Player",List.of()));
    }
    @Test void exactVerificationUsesWorkingPrefixStyleAndRetriesCandidates() {
        assertEquals(List.of("JonsmanV","Jonsman","Jonsma"),OnlineVerification.prefixes("JonsmanV2"));
        assertEquals(List.of("A"),OnlineVerification.prefixes("AB"));
        assertTrue(OnlineVerification.prefixes("bad-name").isEmpty());
        assertFalse(OnlineVerification.exactUsername(List.of(),"JonsmanV2","Local"));
        assertTrue(OnlineVerification.exactUsername(List.of("Other","JonsmanV2"),"JonsmanV2","Local"));
        var queue=new CandidateRetryQueue(List.of("OfflinePlayer","OnlinePlayer"));
        assertFalse(OnlineVerification.exactUsername(List.of("SomebodyElse"),queue.next().orElseThrow(),"Local"));
        assertTrue(OnlineVerification.exactUsername(List.of("OnlinePlayer"),queue.next().orElseThrow(),"Local"));
        assertTrue(queue.next().isEmpty());
    }
    @Test void fullBaltopWeightNeverSelectsZeroWeightMethod() {
        var config=new AutoGambleConfig();config.smartRandomWeight=0;config.moneyLeaderboardWeight=100;
        for(int i=0;i<1000;i++)assertEquals(TargetMethod.MONEY_LEADERBOARD,
                WeightedTargetSelector.select(config,EnumSet.allOf(TargetMethod.class),new Random(i)).orElseThrow());
        assertTrue(WeightedTargetSelector.select(config,EnumSet.of(TargetMethod.SMART_RANDOM),new Random()).isEmpty());
    }
    @Test void largeCachedPoolAndBalanceDiagnosticsRemainAvailable() {
        var config=new AutoGambleConfig();var entries=new ArrayList<BaltopEntry>();
        for(int i=0;i<16000;i++)entries.add(row("Player"+i,i%4==0?"499999999":i%4==1?"500000000":i%4==2?"1000000000":"1000000000000",null,i/45+1));
        var snapshot=new BaltopDatabase.Snapshot(entries,356,1,1,false,0,"");
        var history=new PlayerSelectionManager();history.markPaid("Player1",100);
        var pool=TargetingCandidates.summary(snapshot,config,"Player2",history,null,101);
        assertEquals(16000,pool.total());assertEquals(12000,pool.balanceEligible());
        assertEquals(1,pool.localExcluded());assertEquals(1,pool.recentExcluded());
        assertEquals(11998,pool.candidates().size());
        assertTrue(pool.candidates().contains("Player3"));
    }
    @Test void oldCacheAndConfigMigrateWithoutLosingPlayerData() throws Exception {
        Path cache=folder.resolve("cache.json");
        Files.writeString(cache,"{\"version\":1,\"highestPage\":108,\"entries\":[{\"username\":\"Rank1orFeed\",\"balance\":\"5400000000\",\"rank\":1,\"page\":108,\"lastSeenInBaltop\":1}]}");
        try(var db=new BaltopDatabase(cache,LoggerFactory.getLogger("test"))){
            assertEquals(1,db.snapshot().entries().size());assertNull(db.snapshot().entries().getFirst().rank());
        }
        Path cfg=folder.resolve("config.json");Files.writeString(cfg,"{\"configVersion\":9,\"smartRandomWeight\":0,\"moneyLeaderboardWeight\":100}");
        var manager=new ConfigManager(cfg,LoggerFactory.getLogger("test"));manager.load();
        assertEquals(100,manager.snapshot().moneyLeaderboardWeight);assertEquals(30,manager.snapshot().baltopMaxChecksPerCycle);
    }
}
