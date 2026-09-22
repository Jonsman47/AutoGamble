package com.jonsman.autogamble;

import com.jonsman.autogamble.baltop.*;
import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end fake command sink: no Minecraft client or server commands are used. */
class MinimumPaymentPipelineTest {
    @TempDir Path folder;
    private static Candidate player(String name) { return new Candidate(null,name); }
    private static BaltopEntry row(String name,String money,long observed) {
        return new BaltopEntry(name,MoneyValues.parse(money),null,1,observed);
    }
    private static final class Harness implements AutoPayEnvironment {
        final AutoGambleConfig config=new AutoGambleConfig();
        final AutoPayManager manager=new AutoPayManager(new Random(7),new Random(8));
        final PlayerSelectionManager selection=new PlayerSelectionManager();
        final EligibleRecipientQueue queue=new EligibleRecipientQueue();
        final OutgoingPaymentTracker outgoing=new OutgoingPaymentTracker();
        final Map<String,BaltopEntry> balances=new HashMap<>();
        final List<Candidate> source=new ArrayList<>();
        final List<String> commands=new ArrayList<>(), balanceTrace=new ArrayList<>(), paymentTrace=new ArrayList<>();
        long now;
        Harness(String minimum) {
            config.minimumPaymentBalance=MoneyValues.parse(minimum);
            config.minimumAutoPayDelaySeconds=.2;config.maximumAutoPayDelaySeconds=1.6;
            config.autoPayEnabled=true;config.dryRunMode=false;config.autoPayAmount=1;
        }
        void candidates(String... names) { for(String name:names)source.add(player(name)); }
        void balance(String name,String value) { balances.put(name.toLowerCase(Locale.ROOT),row(name,value,1)); }
        EligibleRecipientQueue.Refill refill() {
            int before=queue.snapshot(now).size();
            var result=queue.addApproved(source,config.minimumPaymentBalance,name->{
                var entry=balances.get(name.toLowerCase(Locale.ROOT));
                balanceTrace.add(name+"="+(entry==null?"UNKNOWN":entry.balance().toPlainString())
                        +" minimum="+config.minimumPaymentBalance.toPlainString()
                        +" eligible="+PaymentBalanceFilter.approved(entry,config.minimumPaymentBalance));
                return entry;
            },now);
            balanceTrace.add("queue="+before+"->"+queue.snapshot(now).size());
            return result;
        }
        void tick(long time) {now=time;manager.tick(now,config,this,selection);}
        long dueAfter(long time) { return time+manager.remainingNanos(time); }
        public boolean connected(){return true;}
        public boolean inputBlocked(){return false;}
        public boolean prepare(long time){return config.minimumPaymentBalance.signum()==0 || !queue.snapshot(time).isEmpty();}
        public List<Candidate> eligiblePlayers(){return config.minimumPaymentBalance.signum()==0?List.copyOf(source):queue.snapshot(now);}
        public Optional<Candidate> nextQueuedRecipient(long time,boolean preferUnpaid){return queue.next(time,preferUnpaid,selection::wasPaid);}
        public boolean dispatch(Candidate target,String amount){
            paymentTrace.add("candidate="+target.username()+" queueAtPayment="+queue.snapshot(now).size()+" at="+now);
            if(!eligiblePlayers().contains(target))return false;
            var sent=PaymentExecution.execute(false,target.username(),new BigDecimal(amount),
                    OutgoingPaymentTracker.Source.ADVERTISING,now,10_000,outgoing,commands::add)==PaymentSender.Result.SENT;
            if(sent && config.minimumPaymentBalance.signum()>0)queue.paid(target.username());
            return sent;
        }
    }
    @Test void zeroMinimumUsesNormalRecipientsWithoutBalanceLookups() {
        var h=new Harness("0");h.candidates("Alice","Bob","Charlie");h.tick(0);h.tick(h.dueAfter(0));
        assertEquals(1,h.commands.size());assertTrue(h.commands.getFirst().matches("pay (Alice|Bob|Charlie) 1"));
        assertTrue(h.balanceTrace.isEmpty());
    }
    @Test void hundredMillionRejectsLowAndActuallyPaysQualifiedPlayers() {
        var h=new Harness("100M");h.candidates("Alice","Bob","Charlie");
        h.balance("Alice","50M");h.balance("Bob","120M");h.balance("Charlie","300M");
        assertEquals(new EligibleRecipientQueue.Refill(3,2),h.refill());
        assertEquals(List.of(player("Bob"),player("Charlie")),h.queue.snapshot(0));
        assertTrue(h.balanceTrace.contains("Alice=50000000 minimum=100000000 eligible=false"));
        assertTrue(h.balanceTrace.contains("Bob=120000000 minimum=100000000 eligible=true"));
        assertTrue(h.balanceTrace.contains("queue=0->2"));
        h.tick(0);long first=h.dueAfter(0);h.tick(first);
        assertEquals("pay Bob 1",h.commands.getFirst());assertEquals(1,h.queue.snapshot(first).size());
        assertTrue(h.paymentTrace.getFirst().startsWith("candidate=Bob queueAtPayment=2 at="));
        h.tick(h.dueAfter(first));assertEquals(List.of("pay Bob 1","pay Charlie 1"),h.commands);
    }
    @Test void unknownBalanceDoesNotBlockKnownRecipient() {
        var h=new Harness("100M");h.candidates("Alice","Bob");h.balance("Bob","150M");
        assertEquals(1,h.refill().accepted());h.tick(0);h.tick(h.dueAfter(0));
        assertEquals(List.of("pay Bob 1"),h.commands);
        assertTrue(h.balanceTrace.contains("Alice=UNKNOWN minimum=100000000 eligible=false"));
    }
    @Test void twentyLowCandidatesDoNotPreventTwentyFirstFromPaying() {
        var h=new Harness("100M");
        for(int i=0;i<20;i++){String name="Low"+i;h.candidates(name);h.balance(name,"20M");}
        h.candidates("Rich21");h.balance("Rich21","250M");
        assertEquals(new EligibleRecipientQueue.Refill(21,1),h.refill());
        h.tick(0);h.tick(h.dueAfter(0));assertEquals(List.of("pay Rich21 1"),h.commands);
    }
    @Test void emptyQueueKeepsDuePaymentReadyUntilRefilled() {
        var h=new Harness("100M");h.candidates("Unknown","Rich");h.balance("Rich","150M");
        h.tick(0);long due=h.dueAfter(0);h.tick(due);
        assertTrue(h.commands.isEmpty());assertEquals("DISCOVERING",h.manager.state());
        h.now=due+50_000_000L;h.refill();h.tick(h.now);
        assertEquals(List.of("pay Rich 1"),h.commands);
    }
    @Test void readyQueuePaymentsFollowConfiguredDelayAndCanRefill() {
        var h=new Harness("100M");h.candidates("Bob","Charlie","Diana");
        h.balance("Bob","120M");h.balance("Charlie","300M");h.balance("Diana","1B");
        h.refill();h.tick(0);long now=0;
        for(int i=0;i<3;i++){
            long due=h.dueAfter(now);assertTrue(due-now>=200_000_000L && due-now<=1_600_000_000L);
            h.tick(due);assertEquals(i+1,h.commands.size());now=due;
        }
        assertTrue(h.queue.low(now));h.source.clear();h.candidates("Elena");h.balance("Elena","200M");
        assertEquals(1,h.refill().accepted());h.tick(h.dueAfter(now));
        assertEquals("pay Elena 1",h.commands.getLast());
    }
    @Test void persistedScanUsesCaseInsensitiveNamesAndOldBalances() {
        Path file=folder.resolve("baltop-cache.json");
        try(var db=new BaltopDatabase(file,LoggerFactory.getLogger("test"))){
            assertTrue(db.recordPage(1,List.of(row("BoB","250M",1)),1));
        }
        try(var db=new BaltopDatabase(file,LoggerFactory.getLogger("test"))){
            assertTrue(db.hasBalanceAtLeast(MoneyValues.parse("100M")));
            var h=new Harness("100M");h.candidates("bob");
            assertEquals(1,h.queue.addApproved(h.source,h.config.minimumPaymentBalance,db::entryOf,0).accepted());
            h.tick(0);h.tick(h.dueAfter(0));assertEquals(List.of("pay bob 1"),h.commands);
        }
    }
    @Test void legacyPrefixSuggestionsReachTheSameBalanceQueueAndCommandSink() {
        var h=new Harness("100M");
        var discovery=new PrefixPlayerDiscovery(new Random(3),1,3);
        var request=discovery.poll(0,h.selection,false).orElseThrow();
        String suggested=request.prefix()+"Rich";
        assertTrue(discovery.complete(request.token(),List.of(suggested),"Local",true,
                new FailedTargetBlacklist(),h.selection,false,1));
        h.source.addAll(discovery.candidates());h.balance(suggested.toUpperCase(Locale.ROOT),"250M");
        assertEquals(1,h.refill().accepted());
        h.tick(0);h.tick(h.dueAfter(0));
        assertEquals(List.of("pay "+suggested+" 1"),h.commands);
    }
    @Test void largeAmountsAndFormattedInputsCompareExactly() {
        String[] values={"0","100","100,000","100,000,000","2,000,000,000","10,000,000,000","1,000,000,000,000"};
        for(String value:values)assertEquals(0,new BigDecimal(value.replace(",","")).compareTo(MoneyValues.parse(value)));
        assertEquals(MoneyValues.parse("100000000"),MoneyValues.parse("100M"));
        assertEquals(MoneyValues.parse("100M"),MoneyValues.parse("100m"));
        assertEquals(MoneyValues.parse("1B"),MoneyValues.parse("1,000,000,000"));
        assertEquals(MoneyValues.parse("1.5B"),MoneyValues.parse("1,500,000,000"));
        assertTrue(MoneyValues.parse("1T").compareTo(MoneyValues.parse("10B"))>0);
    }
}
