package com.jonsman.autogamble;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PrefixDiscoveryTest {
    final PrefixPlayerDiscovery d = new PrefixPlayerDiscovery(new Random(42));
    final PlayerSelectionManager h = new PlayerSelectionManager();
    final FailedTargetBlacklist f = new FailedTargetBlacklist();
    PrefixPlayerDiscovery.Request request(long now) { return d.poll(now, h, true).orElseThrow(); }
    void complete(PrefixPlayerDiscovery.Request r, List<String> names, long now) { assertTrue(d.complete(r.token(), names, "Local", true, f, h, true, now)); }
    @Test void onlyLetters() { for (int i=0;i<100;i++) { d.cancel(); assertTrue(request(i).prefix().matches("[a-z]")); } }
    @Test void defaultOne() { assertEquals(1, d.prefixLength()); }
    @Test void deterministicVariation() { var seen = new HashSet<String>(); for (int i=0;i<100;i++) { d.cancel(); seen.add(request(i).prefix()); } assertTrue(seen.size()>20); }
    @Test void payARequestAndResult() { var a = new PrefixPlayerDiscovery(new Random() { @Override public int nextInt(int bound) { return 0; } }); var r = a.poll(0,h,true).orElseThrow(); assertEquals("/pay a",r.command()); a.complete(r.token(),List.of("Alice"),"Local",true,f,h,true,1); assertEquals("Alice",a.candidates().getFirst().username()); }
    @Test void doesNotTakeFirst() { var r=request(0); complete(r,List.of(r.prefix()+"First",r.prefix()+"Second"),1); assertEquals(r.prefix()+"Second",h.select(d.candidates(),true,new Random(){@Override public int nextInt(int bound){return bound-1;}}).orElseThrow().username()); }
    @Test void randomResults() { var r=request(0); complete(r,List.of(r.prefix()+"First",r.prefix()+"Second"),1); var seen=new HashSet<String>(); var rng=new Random(5); for(int i=0;i<100;i++) seen.add(h.select(d.candidates(),false,rng).orElseThrow().username()); assertEquals(2,seen.size()); }
    @Test void numeric253000Rejected() { assertFalse(PrefixPlayerDiscovery.validName("253000","Local",true)); }
    @Test void numeric1058541Rejected() { assertFalse(PrefixPlayerDiscovery.validName("1058541","Local",true)); }
    @Test void bob123Accepted() { assertTrue(PrefixPlayerDiscovery.validName("Bob123","Local",true)); }
    @Test void x7Accepted() { assertTrue(PrefixPlayerDiscovery.validName("x7","Local",true)); assertEquals(PaymentSender.Result.SENT,PaymentExecution.execute(true,"x7",BigDecimal.ONE,OutgoingPaymentTracker.Source.ADVERTISING,0,10000,new OutgoingPaymentTracker(),cmd->fail())); }
    @Test void localExcluded() { assertFalse(PrefixPlayerDiscovery.validName("LOCAL","Local",true)); }
    @Test void duplicatesRemoved() { var r=request(0); String n=r.prefix()+"Bob"; complete(r,List.of(n,n,n.toUpperCase(Locale.ROOT)),1); assertEquals(1,d.candidates().size()); }
    @Test void paidHistoryRespected() { var r=request(0); String paid=r.prefix()+"Paid",newName=r.prefix()+"New"; h.markPaid(paid); complete(r,List.of(paid,newName),1); assertEquals(newName,d.candidates().getFirst().username()); assertTrue(h.wasPaid(paid)); }
    void blacklist(String name) { f.dispatched(name,OutgoingPaymentTracker.Source.ADVERTISING,0); assertEquals(name,f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.SYSTEM),1).orElseThrow()); }
    @Test void blacklistRespected() { var r=request(0); String name=r.prefix()+"Bad"; blacklist(name); complete(r,List.of(name),2); assertFalse(d.ready()); assertEquals(1,f.size(2)); }
    @Test void expiryRestores() { blacklist("Bob"); assertTrue(f.contains("Bob",2)); assertFalse(f.contains("Bob",FailedTargetBlacklist.DURATION+1)); }
    @Test void emptyTriesDifferent() { var r=request(0); complete(r,List.of(),1); assertTrue(d.poll(100,h,true).isEmpty()); assertNotEquals(r.prefix(),request(PrefixPlayerDiscovery.RETRY+1).prefix()); }
    @Test void noRepeatedFailedLetters() { var seen=new HashSet<String>(); long now=0; for(int i=0;i<26;i++){var r=request(now); assertTrue(seen.add(r.prefix())); complete(r,List.of(),now); now+=PrefixPlayerDiscovery.RETRY;} }
    @Test void maximum26() { long now=0; for(int i=0;i<26;i++){var r=request(now);complete(r,List.of(),now);now+=PrefixPlayerDiscovery.RETRY;} assertTrue(d.poll(now,h,true).isEmpty()); assertTrue(d.ready()); assertTrue(d.candidates().isEmpty()); }
    List<String> run(boolean dry) {
        var c=new AutoGambleConfig(); c.autoPayEnabled=true; c.dryRunMode=dry; c.minimumAutoPayDelaySeconds=c.maximumAutoPayDelaySeconds=1;
        var sent=new ArrayList<String>(); var manager=new AutoPayManager(new Random(1),new Random(2));
        AutoPayEnvironment env=new AutoPayEnvironment(){
            public boolean connected(){return true;} public boolean inputBlocked(){return false;}
            public boolean prepare(long now){d.poll(now,h,true).ifPresent(r->complete(r,List.of(r.prefix()+"Player"),now));return d.ready();}
            public List<PlayerSelectionManager.Candidate> eligiblePlayers(){return d.candidates();}
            public void finishDiscovery(){d.cancel();}
            public boolean dispatch(PlayerSelectionManager.Candidate target,String amount){d.selected(target.username());return PaymentExecution.execute(dry,target.username(),new BigDecimal(amount),OutgoingPaymentTracker.Source.ADVERTISING,1,10000,new OutgoingPaymentTracker(),sent::add)==PaymentSender.Result.SENT;}
        };
        manager.tick(0,c,env,h); manager.tick(1_000_000_000L,c,env,h);manager.tick(1_000_000_001L,c,env,h);
        assertEquals(1,h.paidUsernames().size());assertNotEquals("none",d.selected());return sent;
    }
    @Test void dryChoosesNoCommand(){assertTrue(run(true).isEmpty());}
    @Test void liveSendsExactlyOnce(){var sent=run(false);assertEquals(1,sent.size());assertEquals("pay "+d.selected()+" 1",sent.getFirst());}
    @Test void sessionClearsBlacklist(){blacklist("Bob");f.reset();assertEquals(0,f.size(2));}
    @Test void statusPreservedAfterCycle(){var r=request(0);complete(r,List.of(r.prefix()+"Player"),1);d.selected(r.prefix()+"Player");d.cancel();assertEquals(r.prefix(),d.prefix());assertEquals(1,d.count());assertEquals(r.prefix()+"Player",d.selected());}
    @Test void lateResponseIgnored(){var r=request(0);d.cancel();assertFalse(d.complete(r.token(),List.of(r.prefix()+"Player"),"Local",true,f,h,true,1));}
    @Test void timeoutRetriesBoundedly(){var r=request(0);assertTrue(d.poll(PrefixPlayerDiscovery.TIMEOUT,h,true).isEmpty());assertNotEquals(r.prefix(),request(PrefixPlayerDiscovery.TIMEOUT+PrefixPlayerDiscovery.RETRY).prefix());}
    @Test void winnerErrorNotAdvertising(){f.dispatched("Bob",OutgoingPaymentTracker.Source.ADVERTISING,0);f.dispatched("Alice",OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1);assertTrue(f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.SYSTEM),2).isEmpty());}
    @Test void manualCommandCancelsAttribution(){f.dispatched("Bob",OutgoingPaymentTracker.Source.ADVERTISING,0);f.unrelatedCommand();assertTrue(f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.SYSTEM),2).isEmpty());}
    @Test void playerChatCannotBlacklist(){f.dispatched("Bob",OutgoingPaymentTracker.Source.ADVERTISING,0);assertTrue(f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.PLAYER_CHAT),1).isEmpty());}
    @Test void oldErrorIgnored(){f.dispatched("Bob",OutgoingPaymentTracker.Source.ADVERTISING,0);assertTrue(f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.SYSTEM),FailedTargetBlacklist.RESPONSE_WINDOW+1).isEmpty());}
    @Test void optionalTwoLetters(){var two=new PrefixPlayerDiscovery(new Random(5),2);assertTrue(two.poll(0,h,true).orElseThrow().prefix().matches("[a-z]{2}"));}
    @Test void defaultFilterAndExistingConfig(){assertTrue(new AutoGambleConfig().excludeNumericOnlyNames);var loaded=new com.google.gson.Gson().fromJson("{\"configVersion\":4,\"autoPayAmount\":7}",AutoGambleConfig.class);assertTrue(loaded.excludeNumericOnlyNames);assertEquals(7,loaded.autoPayAmount);}
    @Test void responseMustMatchPrefix(){var r=request(0);complete(r,List.of("253000",r.prefix().equals("z")?"Alice":"Zelda"),1);assertFalse(d.ready());}
}
