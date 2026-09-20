package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.history.AnalyticsEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetingSystemTest {
    @TempDir Path directory;
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of(Objects.requireNonNull(TargetingSystemTest.class.getResource("/fixtures/"+name)).toURI()));
    }
    @Test void defaultWeightsAreValidAndInvalidTotalIsRejected() {
        var c=new AutoGambleConfig(); assertEquals(100,c.smartRandomWeight+c.moneyLeaderboardWeight+c.economyActiveWeight+c.experimentalWeight);
        c.smartRandomWeight=49; assertTrue(SettingsValidation.errors(c).stream().anyMatch(e->e.contains("exactly 100")));
    }
    @Test void weightedSelectionUsesActualPercentages() {
        var c=new AutoGambleConfig(); Map<TargetMethod,Integer> counts=new EnumMap<>(TargetMethod.class); Random r=new Random(9);
        for(int i=0;i<100_000;i++) counts.merge(WeightedTargetSelector.select(c,EnumSet.allOf(TargetMethod.class),r).orElseThrow(),1,Integer::sum);
        assertEquals(.50,counts.get(TargetMethod.SMART_RANDOM)/100000.0,.01);
        assertEquals(.30,counts.get(TargetMethod.MONEY_LEADERBOARD)/100000.0,.01);
        assertEquals(.15,counts.get(TargetMethod.ECONOMY_ACTIVE)/100000.0,.01);
        assertEquals(.05,counts.get(TargetMethod.EXPERIMENTAL)/100000.0,.01);
    }
    @Test void unavailableMethodWeightIsRedistributed() {
        var c=new AutoGambleConfig(); assertEquals(TargetMethod.SMART_RANDOM,
                WeightedTargetSelector.select(c,EnumSet.of(TargetMethod.SMART_RANDOM),new Random()).orElseThrow());
    }
    @Test void suffixMoneyParserSupportsRequiredValues() {
        assertEquals(new BigDecimal("5E+8"),MoneyValues.parse("500m")); assertEquals(new BigDecimal("1E+9"),MoneyValues.parse("1b"));
        assertEquals(new BigDecimal("1.5E+9"),MoneyValues.parse("1.5b")); assertEquals(new BigDecimal("1E+11"),MoneyValues.parse("100b"));
        assertEquals(new BigDecimal("2E+12"),MoneyValues.parse("2t"));
    }
    @Test void parsesAndDeduplicatesPrimaryFixture() throws Exception {
        var rows=LeaderboardHtmlParser.parse(fixture("donutsmpstats-money.html"),"primary",10);
        assertEquals(2,rows.size()); assertEquals("Rich_Player",rows.getFirst().username()); assertEquals(new BigDecimal("1.5E+9"),rows.getFirst().value());
    }
    @Test void parsesSecondaryFixtureAndMalformedDataSafely() throws Exception {
        assertEquals(2,LeaderboardHtmlParser.parse(fixture("donutstats-money.html"),"secondary",10).size());
        assertTrue(LeaderboardHtmlParser.parse("<tr><td>garbage</td></tr>","bad",10).isEmpty());
        assertTrue(LeaderboardHtmlParser.parse(null,"bad",10).isEmpty());
    }
    @Test void filtersInclusiveCustomMoneyRangeAndLocalRecentTargets() {
        var c=new AutoGambleConfig();c.leaderboardMinimumBalance=new BigDecimal("500000000");c.leaderboardMaximumBalance=new BigDecimal("10000000000");
        var rows=List.of(new LeaderboardRecord("Low",new BigDecimal("499999999"),1,1,Set.of()),
                new LeaderboardRecord("Min",new BigDecimal("500000000"),2,1,Set.of()),new LeaderboardRecord("Max",new BigDecimal("10000000000"),3,1,Set.of()),
                new LeaderboardRecord("High",new BigDecimal("10000000001"),4,1,Set.of()),new LeaderboardRecord("Local",new BigDecimal("1000000000"),5,1,Set.of()));
        var history=new PlayerSelectionManager(); history.markPaid("Max",100);
        assertEquals(List.of("Min"),TargetingCandidates.money(new LeaderboardSnapshot(rows,List.of(),1),c,"Local",history,101));
    }
    @Test void exactOnlineVerificationRejectsPrefixOnlyOfflineAndLocal() {
        assertTrue(OnlineVerification.exactUsername(List.of("ExactPlayer"),"exactplayer","Local"));
        assertFalse(OnlineVerification.exactUsername(List.of("ExactPlayer2"),"ExactPlayer","Local"));
        assertFalse(OnlineVerification.exactUsername(List.of("Local"),"Local","local"));
    }
    @Test void recentCooldownPreventsImmediateRepeatAndExpires() {
        var h=new PlayerSelectionManager();h.markPaid("Bob",100);
        assertTrue(h.recentlyPaid("bob",101));assertFalse(h.recentlyPaid("Bob",100+PlayerSelectionManager.RECENT_COOLDOWN_NANOS));
    }
    @Test void providerMergePrefersFresherRecordWithoutDuplicatingPlayer() {
        var old=new LeaderboardRecord("Bob",BigDecimal.ONE,4,10,Set.of("a"));var fresh=new LeaderboardRecord("BOB",BigDecimal.TEN,8,20,Set.of("b"));
        var merged=LeaderboardService.merge(List.of(new LeaderboardSnapshot(List.of(old),List.of(),10),new LeaderboardSnapshot(List.of(fresh),List.of(),20)));
        assertEquals(1,merged.money().size());assertEquals(BigDecimal.TEN,merged.money().getFirst().value());assertEquals(Set.of("a","b"),merged.money().getFirst().sources());
    }
    @Test void failedProviderFallsBackAndCacheReloads() throws Exception {
        LeaderboardRecord bob=new LeaderboardRecord("Bob",BigDecimal.TEN,1,50,Set.of("fallback"));
        LeaderboardProvider failed=new LeaderboardProvider(){public String id(){return "failed";}public LeaderboardSnapshot fetch(HttpClient c)throws Exception{throw new Exception("down");}};
        LeaderboardProvider good=new LeaderboardProvider(){public String id(){return "good";}public LeaderboardSnapshot fetch(HttpClient c){return new LeaderboardSnapshot(List.of(bob),List.of(),50);}};
        Path cache=directory.resolve("cache.json");
        try(var service=new LeaderboardService(cache,org.slf4j.LoggerFactory.getLogger("test"),List.of(failed,good),HttpClient.newHttpClient())){
            assertTrue(service.refresh()); for(int i=0;i<100&&service.status().refreshing();i++)Thread.sleep(10);
            assertEquals(List.of("Bob"),service.snapshot().money().stream().map(LeaderboardRecord::username).toList());assertTrue(Files.exists(cache));
        }
        try(var reloaded=new LeaderboardService(cache,org.slf4j.LoggerFactory.getLogger("test"),List.of(failed,failed),HttpClient.newHttpClient())){
            assertEquals(1,reloaded.snapshot().money().size());
        }
    }
    @Test void versionEightConfigMigratesByAddingTargetingDefaults() throws Exception {
        Path config=directory.resolve("autogamble.json");Files.writeString(config,"{\"configVersion\":8,\"autoPayAmount\":77}");
        var manager=new ConfigManager(config,org.slf4j.LoggerFactory.getLogger("test"));manager.load();var c=manager.snapshot();
        assertEquals(77,c.autoPayAmount);assertEquals(50,c.smartRandomWeight);assertEquals(new BigDecimal("5E+8"),c.leaderboardMinimumBalance);
    }
    @Test void methodAnalyticsAttributesOnlyMatchingPlayerConversionAndPersists() {
        Path path=directory.resolve("analytics.json");var c=new AutoGambleConfig();
        try(var analytics=new AnalyticsEngine(path,org.slf4j.LoggerFactory.getLogger("test"))){
            analytics.targetingAttempt(TargetMethod.MONEY_LEADERBOARD);analytics.targetingValidCandidate(TargetMethod.MONEY_LEADERBOARD);
            analytics.targetingOfflineRejected(TargetMethod.MONEY_LEADERBOARD);analytics.targetingRepeatPrevented(TargetMethod.MONEY_LEADERBOARD);
            analytics.targetingPayment(TargetMethod.MONEY_LEADERBOARD,"Bob",BigDecimal.ONE,1000);
            analytics.incoming("Alice",BigDecimal.TEN,1100,c);analytics.incoming("Bob",BigDecimal.TEN,1200,c);
            var stats=analytics.targetingSnapshot().stream().filter(s->s.method()==TargetMethod.MONEY_LEADERBOARD).findFirst().orElseThrow();
            assertEquals(1,stats.attempts());assertEquals(1,stats.paymentsSent());assertEquals(1,stats.conversions());assertEquals(BigDecimal.TEN,stats.attributedRevenue());
        }
        try(var analytics=new AnalyticsEngine(path,org.slf4j.LoggerFactory.getLogger("test"))){
            var stats=analytics.targetingSnapshot().stream().filter(s->s.method()==TargetMethod.MONEY_LEADERBOARD).findFirst().orElseThrow();
            assertEquals(1,stats.paymentsSent());assertEquals(1,stats.conversions());
        }
    }
}
