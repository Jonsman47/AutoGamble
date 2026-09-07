package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentSoundAlertsTest {
    private final AutoGambleConfig c = new AutoGambleConfig();
    private final PaymentSoundAlerts alerts = new PaymentSoundAlerts();
    @Test void defaultsEnabled() { assertTrue(c.paymentSoundAlertsEnabled); }
    @Test void defaultThresholdsAreCorrect() { assertEquals(List.of("20000000","50000000","100000000","250000000","500000000"), c.paymentAlertTiers.stream().map(t -> t.threshold.toPlainString()).toList()); }
    @Test void belowFirstThresholdIsSilent() { assertTrue(alerts.select(new BigDecimal("19999999"), 0, c).isEmpty()); }
    @Test void exactThresholdTriggers() { assertEquals("20000000", alerts.select(new BigDecimal("20000000"), 0, c).orElseThrow().threshold.toPlainString()); }
    @Test void highestMatchingTierWins() { assertEquals("250000000", alerts.select(new BigDecimal("300000000"), 0, c).orElseThrow().threshold.toPlainString()); }
    @Test void onlyOneTierReturnsPerPayment() { assertTrue(alerts.select(new BigDecimal("999000000"), 0, c).isPresent()); assertTrue(alerts.select(new BigDecimal("999000000"), 0, c).isEmpty()); }
    @Test void globalToggleDisablesPlayback() { c.paymentSoundAlertsEnabled=false;assertTrue(alerts.select(new BigDecimal("500000000"),0,c).isEmpty()); }
    @Test void perTierToggleWorks() { c.paymentAlertTiers.get(4).enabled=false;assertEquals("250000000",alerts.select(new BigDecimal("500000000"),0,c).orElseThrow().threshold.toPlainString()); }
    @Test void customThresholdWorks() { c.paymentAlertTiers.get(0).threshold=MoneyValues.parse("1m");assertTrue(alerts.select(MoneyValues.parse("1m"),0,c).isPresent()); }
    @Test void alertSpacingSuppressesRapidPlayback() { assertTrue(alerts.select(MoneyValues.parse("20m"),1000,c).isPresent());assertTrue(alerts.select(MoneyValues.parse("20m"),1249,c).isEmpty());assertTrue(alerts.select(MoneyValues.parse("20m"),1250,c).isPresent()); }
    @Test void defaultsUseNamespacedSoundIds() { assertTrue(c.paymentAlertTiers.stream().allMatch(t -> t.sound.matches("minecraft:[a-z0-9_/.-]+"))); }
    @Test void defaultSoundsExistInMinecraftRegistry() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); assertTrue(c.paymentAlertTiers.stream().allMatch(t -> net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.containsKey(net.minecraft.resources.Identifier.parse(t.sound)))); }
    @Test void volumeAndPitchSurviveValidation() { c.paymentAlertTiers.get(0).volume=1.7f;c.paymentAlertTiers.get(0).pitch=.8f;c.validate();assertEquals(1.7f,c.paymentAlertTiers.get(0).volume);assertEquals(.8f,c.paymentAlertTiers.get(0).pitch); }
    @Test void validIncomingCanAlertWhileGamblingDisabled() { c.gambleEnabled=false;assertTrue(alerts.select(MoneyValues.parse("20m"),0,c).isPresent()); }
    @Test void dryRunDoesNotSuppressLocalSound() { c.dryRunMode=true;assertTrue(alerts.select(MoneyValues.parse("20m"),0,c).isPresent()); }
    @Test void malformedMessageNeverReachesAlertObserver() {
        var manager=new GambleManager(new RegexPaymentParser(List.of(new AutoGambleConfig.IncomingPattern(true,"(?<sender>.+) paid \\$(?<amount>.+)"))),new PaymentQueue(),new ReceiptDeduplicator(),new OutgoingPaymentTracker(),new Random(),p->{});
        int[] seen={0};manager.receivedObserver(p->seen[0]++);assertEquals(GambleManager.Outcome.IGNORED,manager.receive(new ReceivedMessage("Bob paid $NaN",ReceivedMessage.Channel.SYSTEM),"Local",0,c));assertEquals(0,seen[0]);
    }
    @Test void validatedIncomingOnlyAlertsOnceAndOutsideBetLimits() {
        var queue=new PaymentQueue();var receipts=new ReceiptDeduplicator();var outgoing=new OutgoingPaymentTracker();
        var manager=new GambleManager(new RegexPaymentParser(List.of(new AutoGambleConfig.IncomingPattern(true,"(?<sender>.+) paid \\$(?<amount>.+)"))),queue,receipts,outgoing,new Random(),p->{});
        c.gambleEnabled=true;c.minimumBet=1;c.maximumBet=100;List<BigDecimal> seen=new ArrayList<>();
        manager.receivedObserver(p -> alerts.select(p.amount(), System.currentTimeMillis()+seen.size()*250L,c).ifPresent(t -> seen.add(p.amount())));
        assertEquals(GambleManager.Outcome.INVALID,manager.receive(new ReceivedMessage("Bob paid $20000000",ReceivedMessage.Channel.SYSTEM),"Local",0,c));
        assertEquals(GambleManager.Outcome.DUPLICATE,manager.receive(new ReceivedMessage("Bob paid $20000000",ReceivedMessage.Channel.SYSTEM),"Local",1,c));
        outgoing.record("Alice",MoneyValues.parse("20m"),2,OutgoingPaymentTracker.Source.ADVERTISING,10000);
        assertEquals(GambleManager.Outcome.OUTGOING,manager.receive(new ReceivedMessage("Alice paid $20000000",ReceivedMessage.Channel.SYSTEM),"Local",2,c));
        assertEquals(1,seen.size());
    }
}
