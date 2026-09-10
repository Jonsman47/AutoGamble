package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.*;
import com.jonsman.autogamble.manager.GambleManager;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.ui.TippingScreens;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TippingTest {
 @TempDir Path dir;
 private final List<AnalyticsEngine> engines=new ArrayList<>();
 private static BigDecimal n(String v){return new BigDecimal(v);}
 private static ReceivedMessage confirmation(String p,String a){return new ReceivedMessage("You paid "+p+" $ "+a,ReceivedMessage.Channel.SYSTEM);}
 @AfterEach void close(){engines.forEach(AnalyticsEngine::close);}
 static final class FixedRandom extends Random{double value=.99;int calls;@Override public double nextDouble(){calls++;return value;}}
 static final class Fixture{
  final AutoGambleConfig c=new AutoGambleConfig();final PaymentQueue q=new PaymentQueue();final ReceiptDeduplicator receipts=new ReceiptDeduplicator();
  final OutgoingPaymentTracker outgoing=new OutgoingPaymentTracker();final FixedRandom random=new FixedRandom();final List<TippingManager.Pending> confirmed=new ArrayList<>();
  final TippingManager tips=new TippingManager((p,a,s)->confirmed.add(new TippingManager.Pending(p,a,s,0)));
  final GambleManager gamble=new GambleManager(PaymentParser.inactive(),q,receipts,outgoing,random,p->{});
  Fixture(){c.enabled=true;c.gambleEnabled=true;c.dryRunMode=false;c.minimumBet=0;c.maximumBet=1_000_000_000;c.firstTimePayerBonusEnabled=false;c.winChance=0;c.winnerDelayMinimumMs=c.winnerDelayMaximumMs=0;c.tippingDisclosureAcknowledged=true;gamble.tipping(tips,()->c.tippingDisclosureAcknowledged);}
  GambleManager.Outcome bet(String p,String a,String id,long now){return gamble.accept(new PaymentParser.IncomingPayment(p,n(a),id),"Local",now,c);}
  void dispatch(PaymentSender sender){var x=new WinnerPayoutProcessor(q,new Random(1));x.tipping(tips);x.tick(0,c,sender);x.tick(1,c,sender);}
 }
 private AnalyticsEngine engine(String name){var e=new AnalyticsEngine(dir.resolve(name+".json"),org.slf4j.LoggerFactory.getLogger("test"));engines.add(e);return e;}

 @Test void migrationFrom123AddsTippingDefaultsAndPreservesValues()throws Exception{Path p=dir.resolve("c.json");Files.writeString(p,"{\"configVersion\":7,\"enabled\":false,\"autoPayAmount\":77,\"generateRecentPaymentsReport\":false}");var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();var c=m.snapshot();assertEquals(8,c.configVersion);assertFalse(c.enabled);assertEquals(77,c.autoPayAmount);assertFalse(c.generateRecentPaymentsReport);assertFalse(c.tippingDisclosureAcknowledged);assertFalse(c.tippingPermanentlyDisabled);}
 @Test void tippingDefaultsEnabled(){assertFalse(new AutoGambleConfig().tippingPermanentlyDisabled);}
 @Test void disclosureDefaultsUnacknowledged(){assertFalse(new AutoGambleConfig().tippingDisclosureAcknowledged);}
 @Test void acknowledgementPersists(){Path p=dir.resolve("ack.json");var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();m.update(c->c.tippingDisclosureAcknowledged=true);var r=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));r.load();assertTrue(r.snapshot().tippingDisclosureAcknowledged);}
 @Test void recipientIsExact(){assertEquals("Mac10HeatInciden",TippingManager.RECIPIENT);}
 @Test void rateIsExactlyFivePercent(){assertEquals(0,n("0.05").compareTo(TippingManager.RATE));}
 @Test void losing100mTips5m(){assertEquals(n("5000000"),TippingManager.tipAmount(n("100000000")));}
 @Test void losing20mTips1m(){assertEquals(n("1000000"),TippingManager.tipAmount(n("20000000")));}
 @Test void losing500mTips25m(){assertEquals(n("25000000"),TippingManager.tipAmount(n("500000000")));}
 @Test void losing1bTips50m(){assertEquals(n("50000000"),TippingManager.tipAmount(n("1000000000")));}
 @Test void winningGambleCreatesNoTip(){var f=new Fixture();f.c.winChance=1;f.random.value=0;assertEquals(GambleManager.Outcome.WIN,f.bet("Bob","100","w",0));assertEquals(PaymentQueue.Purpose.WINNER_PAYOUT,f.q.peek().orElseThrow().purpose());}
 @Test void gambleOutcomeRolledOnce(){var f=new Fixture();assertEquals(GambleManager.Outcome.LOSS,f.bet("Bob","100","r",0));assertEquals(1,f.random.calls);}
 @Test void duplicateIncomingCreatesOneTipMaximum(){var f=new Fixture();assertEquals(GambleManager.Outcome.LOSS,f.bet("Bob","100","same",0));assertEquals(GambleManager.Outcome.DUPLICATE,f.bet("Bob","100","same",1));assertEquals(1,f.q.size());}
 @Test void rejectedGambleCreatesNoTip(){var f=new Fixture();f.c.minimumBet=101;assertEquals(GambleManager.Outcome.INVALID,f.bet("Bob","100","bad",0));assertEquals(0,f.q.size());}
 @Test void malformedIncomingCreatesNoTip(){var f=new Fixture();assertEquals(GambleManager.Outcome.IGNORED,f.gamble.receive(new ReceivedMessage("not a payment",ReceivedMessage.Channel.SYSTEM),"Local",0,f.c));assertEquals(0,f.q.size());}
 @Test void outgoingEchoCreatesNoTip(){var f=new Fixture();f.outgoing.record("Bob",n("100"),0,OutgoingPaymentTracker.Source.ADVERTISING,10000);assertEquals(GambleManager.Outcome.OUTGOING,f.bet("Bob","100","echo",1));assertEquals(0,f.q.size());}
 @Test void roundedZeroTipCreatesNoCommand(){var f=new Fixture();assertEquals(GambleManager.Outcome.LOSS,f.bet("Bob","1","tiny",0));assertEquals(0,f.q.size());}
 @Test void calculationUsesBigDecimalAndRoundsDown(){assertEquals(n("5"),TippingManager.tipAmount(n("100.99")));assertEquals(n("0"),TippingManager.tipAmount(n("19.99")));}
 @Test void dryRunLossCreatesNoRealTip(){var f=new Fixture();f.c.dryRunMode=true;assertEquals(GambleManager.Outcome.LOSS,f.bet("Bob","100","dry",0));assertEquals(0,f.q.size());}
 @Test void losingTipClassificationIsDistinct(){var f=new Fixture();f.bet("Bob","100","type",0);assertEquals(PaymentQueue.Purpose.LOSING_BET_TIP,f.q.peek().orElseThrow().purpose());List<OutgoingPaymentTracker.Source> types=new ArrayList<>();f.dispatch((p,a,s)->{types.add(s);return PaymentSender.Result.SENT;});assertEquals(List.of(OutgoingPaymentTracker.Source.LOSING_BET_TIP),types);}
 @Test void failedTipNotCountedAndNotRetriedForever(){var f=new Fixture();f.bet("Bob","100","fail",0);int[] calls={0};f.dispatch((p,a,s)->{calls[0]++;return PaymentSender.Result.RETRY_LATER;});assertEquals(1,calls[0]);assertTrue(f.confirmed.isEmpty());assertEquals(0,f.q.size());}
 @Test void confirmedTipUpdatesAnalytics(){var e=engine("analytics");var t=new TippingManager((p,a,s)->e.outgoing(p,a,s,10));t.dispatched(TippingManager.RECIPIENT,n("5"),OutgoingPaymentTracker.Source.LOSING_BET_TIP,0);assertTrue(t.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"5"),1));var s=e.snapshot(20);assertEquals(1,s.sessionTipsPaid());assertEquals(n("5"),s.sessionTipAmount());assertEquals(1,s.lifetimeTipsPaid());assertEquals(n("5"),s.lifetimeTipAmount());}
 @Test void purchaseInfoButtonAloneCannotDisable(){var c=new AutoGambleConfig();assertNotNull(TippingScreens.class);assertFalse(c.tippingPermanentlyDisabled);}
 @Test void firstConfirmationScreenDoesNotQueuePayment(){var f=new Fixture();assertDoesNotThrow(()->Class.forName("com.jonsman.autogamble.ui.TippingDisableConfirmationScreen"));assertEquals(0,f.q.size());}
 @Test void secondConfirmationQueuesExactly500m(){var f=new Fixture();assertEquals(TippingManager.QueueResult.QUEUED,f.tips.requestPermanentDisable(f.c,f.q,0));assertEquals(n("500000000"),f.q.peek().orElseThrow().amount());assertEquals(1,f.q.size());}
 @Test void purchaseRecipientCorrect(){var f=new Fixture();f.tips.requestPermanentDisable(f.c,f.q,0);assertEquals("Mac10HeatInciden",f.q.peek().orElseThrow().username());}
 @Test void purchaseClassificationDistinct(){var f=new Fixture();f.tips.requestPermanentDisable(f.c,f.q,0);assertEquals(PaymentQueue.Purpose.TIP_DISABLE_PURCHASE,f.q.peek().orElseThrow().purpose());}
 @Test void matchedServerConfirmationDisables(){var f=new Fixture();var t=new TippingManager((p,a,s)->{if(s==OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE)f.c.tippingPermanentlyDisabled=true;});t.dispatched(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);assertTrue(t.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"500000000"),1));assertTrue(f.c.tippingPermanentlyDisabled);}
 @Test void failedPurchaseLeavesEnabled(){var f=new Fixture();f.tips.requestPermanentDisable(f.c,f.q,0);f.tips.dispatchFailed(OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE);assertFalse(f.c.tippingPermanentlyDisabled);assertTrue(f.tips.snapshot().message().contains("remains enabled"));}
 @Test void purchaseTimeoutLeavesEnabled(){var f=new Fixture();f.tips.dispatched(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);f.tips.tick(TippingManager.CONFIRMATION_TIMEOUT_MS+1);assertFalse(f.c.tippingPermanentlyDisabled);assertFalse(f.tips.snapshot().purchasePending());}
 @Test void manual500mCannotDisable(){var f=new Fixture();assertFalse(f.tips.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"500000000"),1));assertFalse(f.c.tippingPermanentlyDisabled);}
 @Test void wrongRecipientDoesNotDisable(){var f=new Fixture();f.tips.dispatched(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);assertFalse(f.tips.receiveConfirmation(confirmation("SomeoneElse","500000000"),1));assertFalse(f.c.tippingPermanentlyDisabled);}
 @Test void wrongAmountDoesNotDisable(){var f=new Fixture();f.tips.dispatched(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);assertFalse(f.tips.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"499999999"),1));assertFalse(f.c.tippingPermanentlyDisabled);}
 @Test void disabledStatePersistsAcrossRestart(){Path p=dir.resolve("persist.json");var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();m.update(c->c.tippingPermanentlyDisabled=true);var r=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));r.load();assertTrue(r.snapshot().tippingPermanentlyDisabled);}
 @Test void disabledStateSurvivesMigration(){Path p=dir.resolve("migrate.json");try{Files.writeString(p,"{\"configVersion\":7,\"tippingPermanentlyDisabled\":true,\"gambleEnabled\":true}");}catch(Exception e){fail(e);}var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();assertTrue(m.snapshot().tippingPermanentlyDisabled);assertTrue(m.snapshot().gambleEnabled);}
 @Test void permanentlyDisabledLossCreatesNoTip(){var f=new Fixture();f.c.tippingPermanentlyDisabled=true;assertEquals(GambleManager.Outcome.LOSS,f.bet("Bob","100","disabled",0));assertEquals(0,f.q.size());}
 @Test void purchaseDoesNotCountAsLosingTip(){var e=engine("purchase");e.outgoing(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);assertEquals(0,e.snapshot(1).sessionTipsPaid());}
 @Test void purchaseDoesNotCountAsGamblePayout(){var e=engine("purchase2");e.outgoing(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,0);assertEquals(0,e.snapshot(1).gamblingProfit().signum());}
 @Test void tipConfirmationRemainsVisible(){var m=confirmation(TippingManager.RECIPIENT,"5M");assertTrue(TippingManager.OutgoingConfirmation.parse(m).isPresent());assertEquals("You paid Mac10HeatInciden $ 5M",m.text());}
 @Test void layoutsFit320x240(){assertTrue(TippingScreens.minimumLayoutFits(320,240));}
 @Test void disclosureBlocksGamblingUntilAcknowledged(){var f=new Fixture();f.c.tippingDisclosureAcknowledged=false;assertEquals(GambleManager.Outcome.IGNORED,f.bet("Bob","100","blocked",0));assertEquals(0,f.random.calls);}
 @Test void dryRunCannotQueueDisablePurchase(){var f=new Fixture();f.c.dryRunMode=true;assertEquals(TippingManager.QueueResult.DRY_RUN,f.tips.requestPermanentDisable(f.c,f.q,0));assertEquals(0,f.q.size());}
 @Test void purchaseWaitsForConfirmationAfterDispatch(){var f=new Fixture();f.tips.requestPermanentDisable(f.c,f.q,0);f.dispatch((p,a,s)->{f.tips.dispatched(p,a,s,0);return PaymentSender.Result.SENT;});assertFalse(f.c.tippingPermanentlyDisabled);assertTrue(f.tips.snapshot().purchasePending());}
 @Test void sourcesAppearInCanonicalHistory()throws Exception{try(var h=new PaymentHistory(dir.resolve("history"),new AutoGambleConfig())){var t=new TippingManager((p,a,s)->h.record(PaymentHistory.Direction.PAID,p,a,s.name()));t.dispatched(TippingManager.RECIPIENT,n("5"),OutgoingPaymentTracker.Source.LOSING_BET_TIP,0);assertTrue(t.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"5"),1));t.dispatched(TippingManager.RECIPIENT,TippingManager.DISABLE_PRICE,OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE,2);assertTrue(t.receiveConfirmation(confirmation(TippingManager.RECIPIENT,"500000000"),3));h.awaitWrites();assertEquals(Set.of("LOSING_BET_TIP","TIP_DISABLE_PURCHASE"),h.snapshot().transactions().stream().map(PaymentHistory.Transaction::source).collect(java.util.stream.Collectors.toSet()));}}
 @Test void tipIsSeparateExpenseInNetProfit(){var e=engine("net");var c=new AutoGambleConfig();e.incoming("Bob",n("100"),0,c);e.acceptedGamble("Bob",n("100"),true,false,n("200"),0,c);e.outgoing(TippingManager.RECIPIENT,n("5"),OutgoingPaymentTracker.Source.LOSING_BET_TIP,1);assertEquals(n("100"),e.snapshot(2).gamblingProfit());assertEquals(n("95"),e.snapshot(2).trackedNetProfit());}
 @Test void lifetimeTipsPersist(){Path p=dir.resolve("tips.json");var first=new AnalyticsEngine(p,org.slf4j.LoggerFactory.getLogger("test"));first.outgoing(TippingManager.RECIPIENT,n("5"),OutgoingPaymentTracker.Source.LOSING_BET_TIP,0);first.close();var second=new AnalyticsEngine(p,org.slf4j.LoggerFactory.getLogger("test"));engines.add(second);assertEquals(1,second.snapshot(1).lifetimeTipsPaid());assertEquals(n("5"),second.snapshot(1).lifetimeTipAmount());}
 @Test void purchaseExecutionCreatesExactCommand(){var f=new Fixture();f.tips.requestPermanentDisable(f.c,f.q,0);List<String> commands=new ArrayList<>();f.dispatch((p,a,source)->PaymentExecution.execute(false,p,a,source,0,10000,f.outgoing,commands::add,()->f.tips.dispatched(p,a,source,0)));assertEquals(List.of("pay Mac10HeatInciden 500000000"),commands);}
 @Test void losingTipExecutionCreatesExactCommand(){var f=new Fixture();f.bet("Bob","100000000","command",0);List<String> commands=new ArrayList<>();f.dispatch((p,a,source)->PaymentExecution.execute(false,p,a,source,0,10000,f.outgoing,commands::add,()->f.tips.dispatched(p,a,source,0)));assertEquals(List.of("pay Mac10HeatInciden 5000000"),commands);}}
