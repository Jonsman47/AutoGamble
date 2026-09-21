package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.baltop.*;
import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.AnalyticsEngine;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetingSystemTest {
    @TempDir Path directory;
    private static BaltopEntry row(String name, String balance, int rank, int page) {
        return new BaltopEntry(name,new BigDecimal(balance),rank,page,100);
    }
    @Test void weightsValidateAndRedistribute() {
        var c=new AutoGambleConfig(); assertEquals(50,c.smartRandomWeight);assertEquals(50,c.moneyLeaderboardWeight);
        var counts=new EnumMap<TargetMethod,Integer>(TargetMethod.class);var random=new Random(9);
        for(int i=0;i<100_000;i++) counts.merge(WeightedTargetSelector.select(c,EnumSet.allOf(TargetMethod.class),random).orElseThrow(),1,Integer::sum);
        assertEquals(.5,counts.get(TargetMethod.SMART_RANDOM)/100000.0,.01);
        assertEquals(.5,counts.get(TargetMethod.MONEY_LEADERBOARD)/100000.0,.01);
        assertFalse(counts.containsKey(TargetMethod.ECONOMY_ACTIVE));
        assertEquals(TargetMethod.SMART_RANDOM,WeightedTargetSelector.select(c,EnumSet.of(TargetMethod.SMART_RANDOM),random).orElseThrow());
        c.smartRandomWeight=49;assertFalse(SettingsValidation.errors(c).isEmpty());
    }
    @Test void parserHandlesTitlesEntriesAndNavigation() {
        assertEquals(1,BaltopParser.pageNumber("Most Money (Page 1)"));
        assertEquals(1234,BaltopParser.pageNumber("§6Most Money (Page 1234)"));
        assertEquals(-1,BaltopParser.pageNumber("Other"));
        assertEquals("Bob",BaltopParser.username("§e#1 Bob",List.of()));
        assertEquals(1234,BaltopParser.rank("Bob",List.of("Rank: #1,234")));
        assertEquals(0,new BigDecimal("1500000000").compareTo(BaltopParser.balance("Bob",List.of("Balance: $1.5B"))));
        var items=List.of(new BaltopParser.Item(2,"head","#1 Bob",List.of("Money: $1.5B"),""),
                new BaltopParser.Item(5,"head","BOB",List.of("Balance: $2B"),""),
                new BaltopParser.Item(8,"head","BadName",List.of("Balance: unknown"),""),
                new BaltopParser.Item(42,"arrow","Next Page",List.of("Click to view next page"),""));
        var parsed=BaltopParser.parse(new BaltopParser.Page(1,items,54,"Most Money (Page 1)"),100);
        assertEquals(42,parsed.nextSlot());assertEquals(1,parsed.entries().size());assertEquals("BOB",parsed.entries().getFirst().username());
        assertEquals(1,parsed.failures());
        assertEquals(parsed.fingerprint(),BaltopParser.parse(new BaltopParser.Page(2,items,54,"Most Money (Page 2)"),200).fingerprint());
    }
    @Test void moneyAndRange() {
        for(var pair:Map.of("500m","500000000","1b","1000000000","1.5b","1500000000","100b","100000000000","2t","2000000000000").entrySet())
            assertEquals(0,new BigDecimal(pair.getValue()).compareTo(MoneyValues.parse(pair.getKey())));
        var c=new AutoGambleConfig();c.leaderboardMinimumBalance=new BigDecimal("500000000");c.leaderboardMaximumBalance=new BigDecimal("10000000000");
        var history=new PlayerSelectionManager();history.markPaid("Max",100);
        var snapshot=new BaltopDatabase.Snapshot(List.of(row("Low","499999999",1,1),row("Min","500000000",2,1),
                row("Max","10000000000",3,1),row("High","10000000001",4,1),row("Local","1000000000",5,1)),1,1,1,false,0,"");
        assertEquals(List.of("Min"),TargetingCandidates.money(snapshot,c,"Local",history,101));
        assertEquals(1,BaltopDatabase.filter(snapshot.entries(),c.leaderboardMinimumBalance,c.leaderboardMaximumBalance,"min").size());
        assertTrue(history.recentlyPaid("max",101));assertFalse(history.recentlyPaid("Max",100+PlayerSelectionManager.RECENT_COOLDOWN_NANOS));
    }
    @Test void cachePersistsDeduplicatesAndResets() throws Exception {
        Path path=directory.resolve("baltop-cache.json");
        try(var db=new BaltopDatabase(path,LoggerFactory.getLogger("test"))){
            assertTrue(db.recordPage(1,List.of(row("Bob","100",1,1)),100));
            assertFalse(db.recordPage(3,List.of(row("Alice","200",2,3)),100));
            assertTrue(db.recordPage(2,List.of(row("BOB","200",3,2),row("Alice","300",2,2)),200));
            db.completed();assertEquals(1,db.snapshot().duplicates());
        }
        try(var db=new BaltopDatabase(path,LoggerFactory.getLogger("test"))){
            assertEquals(2,db.snapshot().highestPage());assertEquals(2,db.snapshot().entries().size());assertTrue(db.snapshot().complete());
            assertEquals("BOB",db.snapshot().entries().stream().filter(e->e.key().equals("bob")).findFirst().orElseThrow().username());
            assertTrue(db.reset());assertEquals(0,db.snapshot().highestPage());assertFalse(Files.exists(path));
        }
        Files.writeString(path,"{ broken json");
        try(var db=new BaltopDatabase(path,LoggerFactory.getLogger("test"))){assertFalse(db.snapshot().warning().isBlank());}
        assertTrue(Files.exists(path));
    }
    @Test void onlineVerificationAndConfigMigration() throws Exception {
        assertTrue(OnlineVerification.exactUsername(List.of("ExactPlayer"),"exactplayer","Local"));
        assertFalse(OnlineVerification.exactUsername(List.of("ExactPlayer2"),"ExactPlayer","Local"));
        assertFalse(OnlineVerification.exactUsername(List.of("Local"),"Local","local"));
        Path path=directory.resolve("autogamble.json");Files.writeString(path,"{\"configVersion\":8,\"autoPayAmount\":77}");
        var manager=new ConfigManager(path,LoggerFactory.getLogger("test"));manager.load();var c=manager.snapshot();
        assertEquals(77,c.autoPayAmount);assertEquals(50,c.smartRandomWeight);assertEquals(50,c.moneyLeaderboardWeight);
        Files.writeString(path,"{\"configVersion\":9,\"smartRandomWeight\":50,\"moneyLeaderboardWeight\":30,\"economyActiveWeight\":15,\"experimentalWeight\":5}");
        manager.load();c=manager.snapshot();assertEquals(70,c.smartRandomWeight);assertEquals(30,c.moneyLeaderboardWeight);
        assertEquals(0,c.economyActiveWeight);assertEquals(0,c.experimentalWeight);
    }
    @Test void analyticsAttributesMatchingPlayerAndPersists() {
        Path path=directory.resolve("analytics.json");var c=new AutoGambleConfig();
        try(var analytics=new AnalyticsEngine(path,LoggerFactory.getLogger("test"))){
            analytics.targetingAttempt(TargetMethod.MONEY_LEADERBOARD);analytics.targetingValidCandidate(TargetMethod.MONEY_LEADERBOARD);
            analytics.targetingOfflineRejected(TargetMethod.MONEY_LEADERBOARD);analytics.targetingRepeatPrevented(TargetMethod.MONEY_LEADERBOARD);
            analytics.targetingPayment(TargetMethod.MONEY_LEADERBOARD,"Bob",BigDecimal.ONE,1000);
            analytics.incoming("Alice",BigDecimal.TEN,1100,c);analytics.incoming("Bob",BigDecimal.TEN,1200,c);
            var stats=analytics.targetingSnapshot().stream().filter(s->s.method()==TargetMethod.MONEY_LEADERBOARD).findFirst().orElseThrow();
            assertEquals(1,stats.paymentsSent());assertEquals(1,stats.conversions());assertEquals(BigDecimal.TEN,stats.attributedRevenue());
        }
        try(var analytics=new AnalyticsEngine(path,LoggerFactory.getLogger("test"))){
            assertEquals(1,analytics.targetingSnapshot().stream().filter(s->s.method()==TargetMethod.MONEY_LEADERBOARD).findFirst().orElseThrow().conversions());
        }
    }
}
