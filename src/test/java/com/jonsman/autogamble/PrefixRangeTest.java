package com.jonsman.autogamble;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.config.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrefixRangeTest {
 final PrefixPlayerDiscovery d=new PrefixPlayerDiscovery(new Random(42),1,3);
 final PlayerSelectionManager h=new PlayerSelectionManager(); final FailedTargetBlacklist f=new FailedTargetBlacklist();
 PrefixPlayerDiscovery.Request next(long now){return d.poll(now,h,true).orElseThrow();}
 void response(PrefixPlayerDiscovery.Request r,List<String> names,long now){d.complete(r.token(),names,"Local",true,f,h,true,now);}
 @Test void minimumLength(){for(int i=0;i<100;i++){d.cancel();assertTrue(next(i).prefix().length()>=1);}}
 @Test void maximumLength(){for(int i=0;i<100;i++){d.cancel();assertTrue(next(i).prefix().length()<=3);}}
 @Test void defaults(){var c=new AutoGambleConfig();assertEquals(1,c.minimumPrefixLength);assertEquals(3,c.maximumPrefixLength);}
 @Test void lowercase(){for(int i=0;i<100;i++){d.cancel();assertTrue(next(i).prefix().matches("[a-z]{1,3}"));}}
 @Test void everyLength(){var lengths=new HashSet<Integer>();for(int i=0;i<100;i++){d.cancel();lengths.add(next(i).prefix().length());}assertEquals(Set.of(1,2,3),lengths);}
 @Test void newPrefixAfterEmpty(){var r=next(0);response(r,List.of(),0);assertNotEquals(r.prefix(),next(PrefixPlayerDiscovery.RETRY).prefix());}
 @Test void noDuplicates(){var seen=new HashSet<String>();for(int i=0;i<10;i++){long now=i*PrefixPlayerDiscovery.RETRY;var r=next(now);assertTrue(seen.add(r.prefix()));response(r,List.of(),now);}}
 @Test void limitTen(){for(int i=0;i<10;i++){long now=i*PrefixPlayerDiscovery.RETRY;var r=next(now);response(r,List.of(),now);}assertTrue(d.poll(10*PrefixPlayerDiscovery.RETRY,h,true).isEmpty());assertTrue(d.ready());assertEquals(0,d.attempts());}
 @Test void randomCandidate(){var r=next(0);response(r,List.of(r.prefix()+"One",r.prefix()+"Two"),0);var seen=new HashSet<String>();var rng=new Random(4);for(int i=0;i<100;i++)seen.add(h.select(d.candidates(),true,rng).orElseThrow().username());assertEquals(2,seen.size());}
 @Test void paidAcrossPrefixes(){h.markPaid("Alice");var one=new PrefixPlayerDiscovery(new Random(){public int nextInt(int b){return 0;}},1,1);var r=one.poll(0,h,true).orElseThrow();one.complete(r.token(),List.of("Alice","Aaron"),"Local",true,f,h,true,0);assertEquals("Aaron",one.candidates().getFirst().username());assertTrue(h.wasPaid("Alice"));}
 @Test void numericStillExcluded(){assertFalse(PrefixPlayerDiscovery.validName("253000","Local",new AutoGambleConfig().excludeNumericOnlyNames));}
 @Test void blacklistStillExcluded(){var r=next(0);String name=r.prefix()+"Player";f.dispatched(name,OutgoingPaymentTracker.Source.ADVERTISING,0);f.receive(new ReceivedMessage("That player does not exist",ReceivedMessage.Channel.SYSTEM),1);response(r,List.of(name),2);assertFalse(d.ready());}
 void dispatch(boolean dry){var r=next(0);response(r,List.of(r.prefix()+"Player"),0);var name=h.select(d.candidates(),true,new Random(2)).orElseThrow().username();var sent=new ArrayList<String>();PaymentExecution.execute(dry,name,BigDecimal.ONE,OutgoingPaymentTracker.Source.ADVERTISING,0,10000,new OutgoingPaymentTracker(),sent::add);assertEquals(dry?List.of():List.of("pay "+name+" 1"),sent);}
 @Test void dryNoCommand(){dispatch(true);}
 @Test void liveOneCommand(){dispatch(false);}
 @Test void migration() throws Exception {var path=java.nio.file.Files.createTempDirectory("prefix-config").resolve("autogamble.json");java.nio.file.Files.writeString(path,"{\"configVersion\":4,\"autoPayAmount\":7,\"excludeNumericOnlyNames\":false}");var manager=new ConfigManager(path,org.slf4j.LoggerFactory.getLogger("test"));manager.load();var c=manager.snapshot();assertEquals(7,c.autoPayAmount);assertFalse(c.excludeNumericOnlyNames);assertEquals(1,c.minimumPrefixLength);assertEquals(3,c.maximumPrefixLength);}
 @Test void invalidRangesRejected(){var c=new AutoGambleConfig();c.minimumPrefixLength=3;c.maximumPrefixLength=1;assertFalse(SettingsValidation.errors(c).isEmpty());assertThrows(IllegalArgumentException.class,()->new PrefixPlayerDiscovery(new Random(),0,3));var draft=new SettingsDraft(new AutoGambleConfig());draft.text(SettingsDraft.Field.PREFIX_MAX,"3.5");assertFalse(draft.validate().isEmpty());}
 @Test void status(){var r=next(0);assertEquals("1–3",d.range());assertEquals(r.prefix().length(),d.lastLength());assertEquals(1,d.attempts());assertEquals(r.prefix(),d.prefix());}
 @Test void fixedThree(){var three=new PrefixPlayerDiscovery(new Random(1),3,3);assertEquals(3,three.poll(0,h,true).orElseThrow().prefix().length());}
}
