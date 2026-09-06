package com.jonsman.autogamble;
import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.GambleManager;
import com.jonsman.autogamble.payment.*;
import java.util.*; import java.math.BigDecimal; import java.nio.file.*;
import org.junit.jupiter.api.Test; import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FirstPayerTest {
 @TempDir Path dir;
 final AutoGambleConfig c=new AutoGambleConfig(); final PayerHistory h=new PayerHistory(); final OutgoingPaymentTracker o=new OutgoingPaymentTracker();
 double roll=.45; int rolls;
 final GambleManager g=new GambleManager(PaymentParser.inactive(),new PaymentQueue(),new ReceiptDeduplicator(),o,new Random(){public double nextDouble(){rolls++;return roll;}},p->{});
 FirstPayerTest(){c.gambleEnabled=true;c.winChance=.4;g.history(h);}
 GambleManager.Outcome bet(String name,String amount,long now){return g.accept(new PaymentParser.IncomingPayment(name,new BigDecimal(amount),name+amount),"Local",now,c);}
 double chance(double base,double bonus){return GambleManager.effectiveChance(base,bonus,true,true);}
 @Test void newBonus(){assertEquals(GambleManager.Outcome.WIN,bet("Bob","100",0));}
 @Test void returningBase(){h.add("Bob");assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",0));}
 @Test void percentagePoints(){assertEquals(.6,chance(.4,.2),1e-12);}
 @Test void fortyPlusTen(){assertEquals(.5,chance(.4,.1),1e-12);}
 @Test void fractionalBase(){assertEquals(.544,chance(.394,.15),1e-12);}
 @Test void clamps(){assertEquals(1,chance(.9,.2));}
 @Test void lossConsumes(){roll=.9;assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",0));assertTrue(h.contains("Bob"));}
 @Test void winConsumes(){bet("Bob","100",0);assertTrue(h.contains("Bob"));}
 @Test void duplicate(){bet("Bob","100",0);assertEquals(GambleManager.Outcome.DUPLICATE,bet("Bob","100",1));assertEquals(1,h.size());assertEquals(1,rolls);}
 @Test void belowMinimum(){c.minimumBet=101;bet("Bob","100",0);assertEquals(0,h.size());assertEquals(0,rolls);}
 @Test void aboveMaximum(){c.maximumBet=99;bet("Bob","100",0);assertEquals(0,h.size());assertEquals(0,rolls);}
 @Test void disabled(){c.gambleEnabled=false;bet("Bob","100",0);assertEquals(0,h.size());}
 @Test void outgoing(){o.record("Bob",new BigDecimal("100"),0,OutgoingPaymentTracker.Source.ADVERTISING,10000);bet("Bob","100",1);assertEquals(0,h.size());}
 @Test void caseInsensitive(){h.add("BOB");assertTrue(h.contains("bob"));assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",0));}
 @Test void reload(){var path=dir.resolve("payers.json");new PayerHistory(path).add("Bob");assertTrue(new PayerHistory(path).contains("BOB"));}
 @Test void disconnect(){bet("Bob","100",0);g.reset();assertEquals(1,g.knownPayers());assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",1));}
 @Test void resetClears(){h.add("Bob");assertTrue(h.reset());assertEquals(0,h.size());}
 @Test void resetPersists(){var path=dir.resolve("payers.json");var history=new PayerHistory(path);history.add("Bob");assertTrue(history.reset());assertEquals(0,new PayerHistory(path).size());}
 @Test void dryConsumes(){assertTrue(c.dryRunMode);bet("Bob","100",0);assertTrue(h.contains("Bob"));}
 @Test void rapidBets(){assertEquals(GambleManager.Outcome.WIN,bet("Bob","100",0));assertEquals(GambleManager.Outcome.LOSS,bet("Bob","101",1));assertEquals(2,rolls);}
 @Test void bonusDisabled(){c.firstTimePayerBonusEnabled=false;assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",0));assertTrue(h.contains("Bob"));}
 @Test void bonusZero(){c.firstTimeWinBonus=0;assertEquals(GambleManager.Outcome.LOSS,bet("Bob","100",0));}
 @Test void statusCount(){bet("Bob","100",0);assertEquals(1,g.knownPayers());}
 @Test void corruptRecovers() throws Exception {var path=dir.resolve("payers.json");Files.writeString(path,"broken");var history=assertDoesNotThrow(()->new PayerHistory(path));history.add("Alice");assertTrue(new PayerHistory(path).contains("Alice"));assertTrue(Files.list(dir).anyMatch(p->p.getFileName().toString().contains("corrupt")));}
 @Test void defaultsAndMigration() throws Exception {var path=dir.resolve("config.json");Files.writeString(path,"{\"configVersion\":4,\"winChance\":0.394}");var m=new ConfigManager(path,org.slf4j.LoggerFactory.getLogger("test"));m.load();assertEquals(.394,m.snapshot().winChance);assertTrue(m.snapshot().firstTimePayerBonusEnabled);assertEquals(.1,m.snapshot().firstTimeWinBonus);}
 @Test void invalidBonusRejected(){c.firstTimeWinBonus=1.1;assertFalse(SettingsValidation.errors(c).isEmpty());}
}
