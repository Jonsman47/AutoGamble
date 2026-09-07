package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.AnalyticsEngine;
import com.jonsman.autogamble.manager.GambleManager;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsTest {
    @TempDir Path dir;
    private final List<AnalyticsEngine> engines = new ArrayList<>();
    private final AutoGambleConfig c = new AutoGambleConfig();
    private AnalyticsEngine engine(String name) { var e=new AnalyticsEngine(dir.resolve(name+".json"),org.slf4j.LoggerFactory.getLogger("test"));engines.add(e);return e; }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    @AfterEach void close() { engines.forEach(AnalyticsEngine::close); }

    @Test void fairTwoXAtFiftyPercentHasZeroEv() { assertEquals(0,AnalyticsEngine.expectedProfit(n("100"),.5,2).compareTo(BigDecimal.ZERO)); }
    @Test void unfavorableSettingsHavePositiveBotEv() { assertTrue(AnalyticsEngine.expectedProfit(n("100"),.4,2).signum()>0); }
    @Test void favorableSettingsHaveNegativeBotEv() { assertTrue(AnalyticsEngine.expectedProfit(n("100"),.6,2).signum()<0); }
    @Test void firstTimeBonusChangesEvSeparately() { assertNotEquals(AnalyticsEngine.expectedProfit(n("100"),.5,2),AnalyticsEngine.expectedProfit(n("100"),.6,2)); }
    @Test void expectedProfitScalesWithBet() { assertEquals(0,AnalyticsEngine.expectedProfit(n("200"),.4,2).compareTo(AnalyticsEngine.expectedProfit(n("100"),.4,2).multiply(n("2")))); }

    @Test void sessionStartsEmpty() { var s=engine("a").snapshot(1000);assertEquals(0,s.paymentsReceived());assertEquals(0,s.moneyReceived().signum()); }
    @Test void incomingUpdatesSessionTotals() { var e=engine("a");e.incoming("Bob",n("10"),1000,c);var s=e.snapshot(2000);assertEquals(1,s.paymentsReceived());assertEquals(n("10"),s.moneyReceived()); }
    @Test void outgoingUpdatesPaidAndNet() { var e=engine("a");e.incoming("Bob",n("10"),1000,c);e.outgoing("Alice",n("3"),OutgoingPaymentTracker.Source.BALANCE_RULE,1100);assertEquals(n("7"),e.snapshot(2000).trackedNetProfit()); }
    @Test void gambleLossAddsStakeProfit() { var e=engine("a");e.acceptedGamble("Bob",n("10"),true,false,n("20"),1000,c);var s=e.snapshot(2000);assertEquals(n("10"),s.gamblingProfit());assertEquals(1,s.losses()); }
    @Test void gambleWinSubtractsRealPayout() { var e=engine("a");e.acceptedGamble("Bob",n("10"),true,true,n("20"),1000,c);e.outgoing("Bob",n("20"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1100);var s=e.snapshot(2000);assertEquals(n("-10"),s.gamblingProfit());assertEquals(1,s.wins()); }
    @Test void advertisingCostUsesAdvertisingOnly() { var e=engine("a");e.outgoing("Bob",n("3"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.outgoing("Bob",n("4"),OutgoingPaymentTracker.Source.BALANCE_RULE,1001);assertEquals(n("3"),e.snapshot(2000).advertisingCost()); }
    @Test void betsAndAverageStakeInputsTracked() { var e=engine("a");e.acceptedGamble("Bob",n("10"),true,false,n("20"),1000,c);e.acceptedGamble("Alice",n("30"),true,false,n("60"),1100,c);var s=e.snapshot(2000);assertEquals(2,s.bets());assertEquals(n("40"),s.gamblingStake()); }
    @Test void uniqueCustomersAreCaseInsensitive() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.incoming("BOB",n("2"),1100,c);assertEquals(1,e.snapshot(2000).uniqueCustomers()); }
    @Test void returningCustomersUseFirstEverFlag() { var e=engine("a");e.acceptedGamble("Bob",n("1"),true,false,n("2"),1000,c);e.acceptedGamble("Bob",n("1"),false,false,n("2"),1100,c);assertEquals(1,e.snapshot(2000).returningCustomers()); }
    @Test void ratesUseElapsedTime() { var e=engine("a");e.resetSession(0);e.incoming("Bob",n("1"),1,c);assertEquals(60,e.snapshot(1000).paymentsPerMinute(),.001); }
    @Test void sessionResetClearsSessionNumbers() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.resetSession(2000);assertEquals(0,e.snapshot(3000).paymentsReceived()); }
    @Test void sessionResetKeepsCustomers() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.resetSession(2000);assertEquals(1,e.snapshot(3000).customers().size()); }
    @Test void zeroElapsedRatesAreSafe() { var e=engine("a");e.resetSession(1000);assertEquals(0,e.snapshot(1000).paymentsPerMinute()); }
    @Test void sessionStateDoesNotReturnAfterReload() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.close();var r=engine("a");assertEquals(0,r.snapshot(2000).paymentsReceived()); }

    @Test void customerPaidByAggregatesIncoming() { var e=engine("a");e.incoming("Bob",n("4"),1000,c);e.incoming("bob",n("6"),1100,c);assertEquals(n("10"),e.snapshot(2000).customers().getFirst().paidBy()); }
    @Test void customerPaidBackCountsOnlyGamblePayouts() { var e=engine("a");e.outgoing("Bob",n("4"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.outgoing("Bob",n("6"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1100);assertEquals(n("6"),e.snapshot(2000).customers().getFirst().paidBack()); }
    @Test void customerNetIsPaidByMinusPaidBack() { var e=engine("a");e.incoming("Bob",n("10"),1000,c);e.outgoing("Bob",n("6"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1100);assertEquals(n("4"),e.snapshot(2000).customers().getFirst().net()); }
    @Test void customerBetWinLossCountsUpdate() { var e=engine("a");e.acceptedGamble("Bob",n("1"),true,true,n("2"),1000,c);e.acceptedGamble("bob",n("1"),false,false,n("2"),1100,c);var x=e.snapshot(2000).customers().getFirst();assertEquals(2,x.bets());assertEquals(1,x.wins());assertEquals(1,x.losses()); }
    @Test void latestCapitalizationAndTimestampArePreserved() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.incoming("BOB",n("1"),2000,c);var x=e.snapshot(3000).customers().getFirst();assertEquals("BOB",x.player());assertEquals(2000,x.lastPayment()); }
    @Test void customerStatsPersistAfterReload() { var e=engine("a");e.incoming("Bob",n("5"),1000,c);e.close();var x=engine("a").snapshot(2000).customers().getFirst();assertEquals(n("5"),x.paidBy()); }
    @Test void caseInsensitiveCustomerPersistsAsOneRow() { var e=engine("a");e.incoming("Bob",n("1"),1000,c);e.incoming("bob",n("1"),1100,c);e.close();assertEquals(1,engine("a").snapshot(2000).customers().size()); }
    @Test void dryRunOutgoingCallbackDoesNotUpdateAnalytics() { var e=engine("a");var tracker=new OutgoingPaymentTracker();PaymentExecution.execute(true,"Bob",n("10"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,0,1000,tracker,x->{},()->e.outgoing("Bob",n("10"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1000));assertEquals(0,e.snapshot(2000).moneyPaid().signum()); }
    @Test void duplicateIncomingIsCountedOnceByCentralFlow() {
        var e=engine("a");var cfg=new AutoGambleConfig();var manager=new GambleManager(new RegexPaymentParser(List.of(new AutoGambleConfig.IncomingPattern(true,"(?<sender>.+) paid \\$(?<amount>.+)"))),new PaymentQueue(),new ReceiptDeduplicator(),new OutgoingPaymentTracker(),new Random(),p->{});
        manager.receivedObserver(p->e.incoming(p.sender(),p.amount(),1000,cfg));manager.receive(new ReceivedMessage("Bob paid $10",ReceivedMessage.Channel.SYSTEM),"Local",0,cfg);manager.receive(new ReceivedMessage("Bob paid $10",ReceivedMessage.Channel.SYSTEM),"Local",1,cfg);assertEquals(1,e.snapshot(2000).paymentsReceived());
    }
    @Test void corruptAnalyticsFileDoesNotCrashAndIsBackedUp() throws Exception { Path p=dir.resolve("a.json");Files.writeString(p,"bad");assertDoesNotThrow(()->engine("a"));assertTrue(Files.list(dir).anyMatch(x->x.getFileName().toString().startsWith("a.json.corrupt-"))); }

    @Test void advertisingSendCreatesTouchAndCost() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);var s=e.snapshot(2000);assertEquals(1,s.lifetimeAdvertisingPayments());assertEquals(n("2"),s.lifetimeAdvertisingSpend()); }
    @Test void paymentWithinWindowConverts() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),2000,c);assertEquals(1,e.snapshot(3000).lifetimeConverted()); }
    @Test void paymentAfterWindowDoesNotConvert() { var e=engine("a");c.autoPayConversionWindowSeconds=1;e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),2001,c);assertEquals(0,e.snapshot(3000).lifetimeConverted()); }
    @Test void differentPlayerDoesNotConvert() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Alice",n("10"),2000,c);assertEquals(0,e.snapshot(3000).lifetimeConverted()); }
    @Test void lastTouchWinsWithoutDuplicateUniqueCustomer() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.outgoing("bob",n("3"),OutgoingPaymentTracker.Source.ADVERTISING,1500);e.incoming("BOB",n("10"),2000,c);var s=e.snapshot(3000);assertEquals(2,s.lifetimeAdvertisingPayments());assertEquals(1,s.lifetimeUniqueAdvertised());assertEquals(1,s.lifetimeConverted()); }
    @Test void attributedRevenueContinuesDuringDuration() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),2000,c);e.incoming("Bob",n("5"),3000,c);assertEquals(n("15"),e.snapshot(4000).lifetimeAttributedRevenue()); }
    @Test void attributedRevenueStopsAfterDuration() { var e=engine("a");c.autoPayAttributionDurationSeconds=1;e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),1500,c);e.incoming("Bob",n("5"),2501,c);assertEquals(n("10"),e.snapshot(3000).lifetimeAttributedRevenue()); }
    @Test void attributedProfitIncludesRevenuePayoutAndAdvertisingCost() { c.dryRunMode=false;var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),1500,c);e.acceptedGamble("Bob",n("10"),true,true,n("20"),1500,c);e.outgoing("Bob",n("20"),OutgoingPaymentTracker.Source.GAMBLE_PAYOUT,1600);assertEquals(n("-12"),e.snapshot(2000).lifetimeAttributedProfit()); }
    @Test void nonConvertedGambleDoesNotAffectAttributedProfit() { var e=engine("a");e.acceptedGamble("Bob",n("10"),true,false,n("20"),1000,c);assertEquals(0,e.snapshot(2000).lifetimeAttributedProfit().signum()); }
    @Test void roiCalculationUsesProfitOverSpend() { assertEquals(0,n("50.0000").compareTo(AnalyticsEngine.roi(n("5"),n("10")))); }
    @Test void zeroSpendRoiIsSafe() { assertEquals(0,AnalyticsEngine.roi(n("10"),BigDecimal.ZERO).signum()); }
    @Test void roiLifetimePersistsAfterReload() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.incoming("Bob",n("10"),1500,c);e.acceptedGamble("Bob",n("10"),true,false,n("20"),1500,c);e.close();var s=engine("a").snapshot(2000);assertEquals(n("2"),s.lifetimeAdvertisingSpend());assertEquals(n("8"),s.lifetimeAttributedProfit()); }
    @Test void sessionAndLifetimeRoiAreSeparate() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.resetSession(2000);var s=e.snapshot(3000);assertEquals(0,s.sessionAdvertisingPayments());assertEquals(1,s.lifetimeAdvertisingPayments()); }
    @Test void persistedTouchCanConvertLifetimeWithoutPollutingNewSession() { var e=engine("a");e.outgoing("Bob",n("2"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.close();var r=engine("a");r.incoming("Bob",n("10"),1500,c);var s=r.snapshot(2000);assertEquals(1,s.lifetimeConverted());assertEquals(0,s.sessionConverted());assertEquals(0,s.sessionAttributedRevenue().signum()); }
    @Test void multipleAdvertisingTouchesRemainOneUniquePlayer() { var e=engine("a");e.outgoing("Bob",n("1"),OutgoingPaymentTracker.Source.ADVERTISING,1000);e.outgoing("BOB",n("1"),OutgoingPaymentTracker.Source.ADVERTISING,2000);assertEquals(1,e.snapshot(3000).lifetimeUniqueAdvertised()); }
}
