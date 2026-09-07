package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GoodCustomerFollowTest {
    @TempDir Path dir;
    AutoGambleConfig c;
    PaymentHistory h;
    GoodCustomerFollow f;
    List<String> sent = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        c = new AutoGambleConfig(); c.autoFollowGoodCustomersEnabled = true; c.dryRunMode = false;
        h = new PaymentHistory(dir, c); h.awaitWrites(); f = new GoodCustomerFollow(h);
    }
    @AfterEach void close() { h.close(); }
    void receive(String name, String amount) throws Exception { h.record(PaymentHistory.Direction.RECEIVED,name,new BigDecimal(amount),"test"); h.awaitWrites(); }
    void tick() throws Exception { f.tick(c, 1_000_000_000L, n -> { sent.add("follow " + n); return PaymentSender.Result.SENT; }); h.awaitWrites(); }
    @Test void defaultsOff() { assertFalse(new AutoGambleConfig().autoFollowGoodCustomersEnabled); }
    @Test void defaultThresholdFiveMillion() { assertEquals(new BigDecimal("5000000"),new AutoGambleConfig().autoFollowThreshold); }
    @Test void belowThresholdDoesNotFollow() throws Exception { receive("Bob","4999999"); tick(); assertTrue(sent.isEmpty()); }
    @Test void reachingThresholdFollows() throws Exception { receive("Bob","5000000"); tick(); assertEquals(List.of("follow Bob"),sent); }
    @Test void cumulativeThreshold() throws Exception { receive("Bob","1000000"); receive("Bob","2000000"); tick(); assertTrue(sent.isEmpty()); receive("Bob","2000000"); tick(); assertEquals(1,sent.size()); }
    @Test void onlyFollowsOnce() throws Exception { receive("Bob","5000000"); tick(); receive("Bob","45000000"); tick(); tick(); assertEquals(1,sent.size()); }
    @Test void reconnectCannotFollowAgain() throws Exception { receive("Bob","5000000"); tick(); f.resetSession(); tick(); assertEquals(1,sent.size()); }
    @Test void restartCannotFollowAgain() throws Exception { receive("Bob","5000000"); tick(); h.close(); h=new PaymentHistory(dir,c); h.awaitWrites(); f=new GoodCustomerFollow(h); tick(); assertEquals(1,sent.size()); }
    @Test void fileOneUsernamePerLine() throws Exception { receive("Bob","5000000"); tick(); receive("Alice","5000000"); tick(); tick(); assertEquals(Set.of("Bob","Alice"),new HashSet<>(Files.readAllLines(dir.resolve("followed_players.txt")))); }
    @Test void caseInsensitiveIdentity() throws Exception { receive("Bob","3000000"); receive("bOB","2000000"); tick(); receive("BOB","1000000"); tick(); assertEquals(1,sent.size()); assertEquals(1,Files.readAllLines(dir.resolve("followed_players.txt")).size()); }
    @Test void dryRunDoesNotSendFollow() throws Exception { c.dryRunMode=true; receive("Bob","5000000"); tick(); assertTrue(sent.isEmpty()); }
    @Test void dryRunDoesNotPersistAndLiveCanFollow() throws Exception { c.dryRunMode=true; receive("Bob","5000000"); tick(); assertEquals("",Files.readString(dir.resolve("followed_players.txt"))); c.dryRunMode=false; tick(); assertEquals(1,sent.size()); }
    @Test void clearHistoryAllowsFollowWithoutClearingTotals() throws Exception { receive("Bob","5000000"); tick(); f.clearHistory(); h.awaitWrites(); tick(); assertEquals(2,sent.size()); assertTrue(Files.readString(dir.resolve("reports/top_customers.txt")).contains("5000000")); }
    @Test void blockedDispatchNotRecorded() throws Exception { receive("Bob","5000000"); f.tick(c,0,n->PaymentSender.Result.RETRY_LATER); h.awaitWrites(); assertEquals("",Files.readString(dir.resolve("followed_players.txt"))); tick(); assertEquals(1,sent.size()); }
    @Test void malformedFollowedLinesIgnored() throws Exception { h.close(); Files.writeString(dir.resolve("followed_players.txt"),"Bob\nbob\nbad name\n\nAlice\n"); h=new PaymentHistory(dir,c); h.awaitWrites(); assertEquals(Set.of("bob","alice"),h.snapshot().followed()); }
    @Test void disabledFeatureAndMasterNeverFollow() throws Exception { receive("Bob","5000000"); c.autoFollowGoodCustomersEnabled=false; tick(); c.autoFollowGoodCustomersEnabled=true; c.enabled=false; tick(); assertTrue(sent.isEmpty()); }
    @Test void moneySuffixesAndZeroAccepted() { for(String s:List.of("5m","5M","5000000")) assertEquals(0,MoneyValues.parse(s).compareTo(new BigDecimal("5000000"))); assertEquals(0,MoneyValues.parse("1.5m").compareTo(new BigDecimal("1500000"))); assertEquals(0,MoneyValues.parse("0").signum()); }
}
