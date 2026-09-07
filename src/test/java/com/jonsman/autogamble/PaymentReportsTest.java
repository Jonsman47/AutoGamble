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

class PaymentReportsTest {
    @TempDir Path dir;
    AutoGambleConfig c = new AutoGambleConfig(); PaymentHistory h;
    @BeforeEach void setup() throws Exception { h=new PaymentHistory(dir,c); h.awaitWrites(); }
    @AfterEach void close() { h.close(); }
    void record(PaymentHistory.Direction d,String player,String amount,long time) throws Exception { h.record(new PaymentHistory.Transaction(d,player,new BigDecimal(amount),time,"TEST")); h.awaitWrites(); }
    void received(String player,String amount,long time) throws Exception { record(PaymentHistory.Direction.RECEIVED,player,amount,time); }
    void paid(String player,String amount,long time) throws Exception { record(PaymentHistory.Direction.PAID,player,amount,time); }
    String read(String name) throws Exception { return Files.readString(dir.resolve("reports/"+name+".txt")); }
    void configure() throws Exception { h.configure(c); h.awaitWrites(); }
    void samples() throws Exception { received("Bob","100",1000); paid("Alice","200",2000); received("Cara","300",3000); }
    @Test void allFilesDefaultOn() { assertTrue(c.generatePaymentsToPlayersReport && c.generateTopCustomersReport && c.generateRecentPaymentsReport); }
    @Test void receivedUpdatesTopCustomers() throws Exception { received("Bob","50",1000); assertEquals("Bob | 50\n",read("top_customers")); }
    @Test void paidUpdatesPaymentsToPlayers() throws Exception { paid("Bob","75",1000); assertEquals("Bob | 75\n",read("payments_to_players")); }
    @Test void totalsSortDescending() throws Exception { received("Bob","50",1000); received("Alice","100",2000); assertEquals("Alice | 100\nBob | 50\n",read("top_customers")); }
    @Test void newestFirst() throws Exception { samples(); assertTrue(read("recent_payments").lines().findFirst().orElseThrow().contains("Cara")); }
    @Test void exactlyOneLinePerTransaction() throws Exception { samples(); assertEquals(3,read("recent_payments").lines().count()); assertTrue(read("recent_payments").lines().allMatch(s->s.split(" \\| ").length==4)); }
    @Test void receivedOnlyFilter() throws Exception { samples(); c.recentShowPaid=false; configure(); assertEquals(2,read("recent_payments").lines().count()); assertFalse(read("recent_payments").contains("PAID")); }
    @Test void paidOnlyFilter() throws Exception { samples(); c.recentShowReceived=false; configure(); assertEquals(1,read("recent_payments").lines().count()); assertTrue(read("recent_payments").contains("Alice")); }
    @Test void minimumFilterInclusive() throws Exception { samples(); c.recentMinimumAmount=new BigDecimal("200"); configure(); assertEquals(2,read("recent_payments").lines().count()); }
    @Test void maximumFilterInclusive() throws Exception { samples(); c.recentMaximumAmount=new BigDecimal("200"); configure(); assertEquals(2,read("recent_payments").lines().count()); }
    @Test void maximumLines() throws Exception { samples(); c.recentMaxLines=1; configure(); assertEquals(1,read("recent_payments").lines().count()); }
    @Test void disablingReportsPreservesFiles() throws Exception { samples(); String recent=read("recent_payments"),top=read("top_customers"),paid=read("payments_to_players"); c.generateRecentPaymentsReport=c.generateTopCustomersReport=c.generatePaymentsToPlayersReport=false; configure(); received("Doug","400",4000); paid("Doug","500",5000); assertEquals(recent,read("recent_payments")); assertEquals(top,read("top_customers")); assertEquals(paid,read("payments_to_players")); }
    @Test void totalsPersistAcrossReload() throws Exception { samples(); h.close(); h=new PaymentHistory(dir,c); h.awaitWrites(); assertEquals("Cara | 300\nBob | 100\n",read("top_customers")); assertEquals("Alice | 200\n",read("payments_to_players")); }
    @Test void historyPersistsAcrossReload() throws Exception { samples(); String before=read("recent_payments"); h.close(); h=new PaymentHistory(dir,c); h.awaitWrites(); assertEquals(before,read("recent_payments")); }
    @Test void duplicateIncomingNotCountedTwice() throws Exception {
        var g=new GambleManager(PaymentParser.inactive(),new PaymentQueue(),new ReceiptDeduplicator(),new OutgoingPaymentTracker(),new Random(),p->{});
        g.receivedObserver(p->h.record(PaymentHistory.Direction.RECEIVED,p.sender(),p.amount(),"SERVER"));
        var payment=new PaymentParser.IncomingPayment("Bob",BigDecimal.TEN,"Bob paid you 10");
        g.accept(payment,"Local",0,c); g.accept(payment,"Local",1,c); h.awaitWrites(); assertEquals("Bob | 10\n",read("top_customers"));
    }
    @Test void dryRunOutgoingNeverRecorded() throws Exception { PaymentExecution.execute(true,"Bob",BigDecimal.TEN,OutgoingPaymentTracker.Source.ADVERTISING,0,10000,new OutgoingPaymentTracker(),cmd->fail(),()->h.record(PaymentHistory.Direction.PAID,"Bob",BigDecimal.TEN,"TEST")); h.awaitWrites(); assertEquals("",read("payments_to_players")); }
    @Test void corruptDataBackedUpAndDoesNotCrash() throws Exception { h.close(); Files.writeString(dir.resolve("data/payment_totals.json"),"{bad"); h=new PaymentHistory(dir,c); h.awaitWrites(); assertEquals(0,h.snapshot().stored()); try(var files=Files.list(dir.resolve("data"))) { assertTrue(files.anyMatch(p->p.getFileName().toString().contains(".corrupt-"))); } }
    @Test void caseInsensitiveAggregation() throws Exception { received("Bob","100",1000); received("bOB","200",2000); assertEquals("bOB | 300\n",read("top_customers")); }
    @Test void filtersNeverDeleteStoredHistory() throws Exception { samples(); c.recentMinimumAmount=new BigDecimal("999"); configure(); assertEquals("",read("recent_payments")); assertEquals(3,h.snapshot().stored()); c.recentMinimumAmount=BigDecimal.ZERO; configure(); assertEquals(3,read("recent_payments").lines().count()); }
    @Test void retentionKeepsTotals() throws Exception { c.storedTransactionHistoryLimit=100; configure(); for(int i=0;i<110;i++) h.record(new PaymentHistory.Transaction(PaymentHistory.Direction.RECEIVED,"Bob",BigDecimal.ONE,i,"TEST")); h.awaitWrites(); assertEquals(100,h.snapshot().stored()); assertEquals("Bob | 110\n",read("top_customers")); }
    @Test void oldestFirstOption() throws Exception { samples(); c.recentNewestFirst=false; configure(); assertTrue(read("recent_payments").lines().findFirst().orElseThrow().contains("Bob")); }
    @Test void realOutgoingRecordedOnceAndTracked() throws Exception { var outgoing=new OutgoingPaymentTracker(); assertEquals(PaymentSender.Result.SENT,PaymentExecution.execute(false,"Bob",BigDecimal.TEN,OutgoingPaymentTracker.Source.BALANCE_RULE,0,10000,outgoing,cmd->{},()->h.record(PaymentHistory.Direction.PAID,"Bob",BigDecimal.TEN,"BALANCE_RULE"))); h.awaitWrites(); assertEquals(1,outgoing.snapshot().size()); assertEquals("Bob | 10\n",read("payments_to_players")); }
    @Test void outgoingEchoNotReceived() throws Exception {
        var out=new OutgoingPaymentTracker(); out.record("Bob",BigDecimal.TEN,0,OutgoingPaymentTracker.Source.ADVERTISING,10000);
        var g=new GambleManager(PaymentParser.inactive(),new PaymentQueue(),new ReceiptDeduplicator(),out,new Random(),p->{});
        g.receivedObserver(p->fail("Outgoing echo counted")); g.accept(new PaymentParser.IncomingPayment("Bob",BigDecimal.TEN,"echo"),"Local",1,c);
    }
    @Test void accountingDedupSurvivesGamblingSessionReset() throws Exception {
        var g=new GambleManager(PaymentParser.inactive(),new PaymentQueue(),new ReceiptDeduplicator(),new OutgoingPaymentTracker(),new Random(),p->{});
        g.receivedObserver(p->h.record(PaymentHistory.Direction.RECEIVED,p.sender(),p.amount(),"SERVER"));
        var payment=new PaymentParser.IncomingPayment("Bob",BigDecimal.TEN,"receipt");
        g.accept(payment,"Local",0,c); g.reset(); g.accept(payment,"Local",1,c); h.awaitWrites();
        assertEquals("Bob | 10\n",read("top_customers"));
    }
    @Test void reportsRefreshAutomaticallyWithoutManualFlush() throws Exception {
        h.record(PaymentHistory.Direction.RECEIVED,"Bob",BigDecimal.TEN,"SERVER");
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!read("top_customers").contains("Bob | 10") && System.nanoTime()<deadline) Thread.sleep(20);
        assertEquals("Bob | 10\n",read("top_customers"));
    }
    @Test void incompleteStructuredHistoryBackedUp() throws Exception {
        h.close(); Files.writeString(dir.resolve("data/payment_totals.json"),"{\"version\":1}");
        h=new PaymentHistory(dir,c); h.awaitWrites(); assertEquals(0,h.snapshot().stored()); assertFalse(h.snapshot().error().isEmpty());
    }
    @Test void malformedIncomingNeverRecorded() throws Exception {
        var g=new GambleManager(RegexPaymentParser.fromConfig(c),new PaymentQueue(),new ReceiptDeduplicator(),new OutgoingPaymentTracker(),new Random(),p->{});
        g.receivedObserver(p->fail("Malformed input recorded"));
        g.receive(new ReceivedMessage("Bob paid you NaN",ReceivedMessage.Channel.SYSTEM),"Local",0,c);
        g.receive(new ReceivedMessage("Bob paid you $ 10",ReceivedMessage.Channel.PLAYER_CHAT),"Local",1,c);
    }
    @Test void ambiguousOutgoingNotCounted() throws Exception {
        assertEquals(PaymentSender.Result.UNCERTAIN,PaymentExecution.execute(false,"Bob",BigDecimal.TEN,OutgoingPaymentTracker.Source.BALANCE_RULE,0,10000,new OutgoingPaymentTracker(),cmd->{throw new IllegalStateException("network failed");},()->fail("Ambiguous payment counted")));
        h.awaitWrites(); assertEquals("",read("payments_to_players"));
    }
}
