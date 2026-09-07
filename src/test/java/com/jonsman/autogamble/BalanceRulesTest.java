package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BalanceRulesTest {
    @TempDir Path dir;
    AutoGambleConfig c = new AutoGambleConfig();
    KnownBalance balance = new KnownBalance();
    BalanceRuleEngine engine;
    BalanceRule rule;
    List<String> sent = new ArrayList<>();
    @BeforeEach void setup() {
        c.automaticBalancePaymentsEnabled=true; c.dryRunMode=false;
        rule=rule("Bob","500","200"); c.balancePaymentRules.add(rule);
        engine=new BalanceRuleEngine(); engine.awaitLoaded();
    }
    @AfterEach void close() { engine.close(); }
    BalanceRule rule(String player,String threshold,String amount) {
        var r=new BalanceRule(); r.enabled=true; r.player=player; r.threshold=new BigDecimal(threshold); r.amount=new BigDecimal(amount); return r;
    }
    void tick(String amount,long seconds) {
        long now=seconds*1_000_000_000L;
        if(amount!=null) balance.observeVerified(new BigDecimal(amount),now,"TEST verified observation");
        engine.tick(c,balance,now,seconds*1000,(player,payment)->{sent.add(player+" "+MoneyValues.plain(payment));return PaymentSender.Result.SENT;});
    }
    @Test void defaultsOff() { assertFalse(new AutoGambleConfig().automaticBalancePaymentsEnabled); }
    @Test void noDefaultRules() { assertTrue(new AutoGambleConfig().balancePaymentRules.isEmpty()); assertFalse(new BalanceRule().enabled); }
    @Test void crossingTriggersExactlyOnce() { tick("499",0); tick("500",1); assertEquals(List.of("Bob 200"),sent); }
    @Test void stayingAboveDoesNotRetrigger() { tick("510",0); tick("510",31); tick("600",90); assertEquals(1,sent.size()); }
    @Test void fallingBelowRearms() { tick("500",0); tick("499",31); tick("500",32); assertEquals(2,sent.size()); }
    @Test void crossingAgainAfterRearm() { tick("500",0); tick("0",31); tick("500",32); tick("0",63); tick("500",64); assertEquals(3,sent.size()); }
    @Test void cooldownRespected() { tick("500",0); tick("400",1); tick("500",2); assertEquals(1,sent.size()); tick("500",30); assertEquals(2,sent.size()); }
    @Test void disabledRuleNeverFires() { rule.enabled=false; tick("500",0); assertTrue(sent.isEmpty()); }
    @Test void masterOffNeverFires() { c.automaticBalancePaymentsEnabled=false; tick("500",0); assertTrue(sent.isEmpty()); }
    @Test void insufficientBalanceNeverFires() { rule.amount=new BigDecimal("600"); tick("500",0); assertTrue(sent.isEmpty()); }
    @Test void invalidUsernameRejected() { for(String name:List.of("","a b","Bob\nAlice","/pay","a")) { rule.player=name; assertFalse(rule.valid()); } }
    @Test void invalidThresholdRejected() { for(BigDecimal n:List.of(BigDecimal.ZERO,new BigDecimal("-1"))) { rule.threshold=n; assertFalse(rule.valid()); } for(String s:List.of("NaN","1e9","-1","5mm","Infinity")) assertThrows(IllegalArgumentException.class,()->MoneyValues.parse(s)); }
    @Test void invalidAmountRejected() { rule.amount=BigDecimal.ZERO; assertFalse(rule.valid()); rule.amount=new BigDecimal("-1"); assertFalse(rule.valid()); }
    @Test void dryRunNeverSendsPay() { c.dryRunMode=true; tick("500",0); assertTrue(sent.isEmpty()); }
    @Test void dryRunDoesNotDisarmRealRule() { c.dryRunMode=true; tick("500",0); c.dryRunMode=false; tick("500",1); assertEquals(1,sent.size()); }
    @Test void realRuleFeedsHistoryAndOutgoingTracker() throws Exception {
        try(var history=new PaymentHistory(dir,c)) {
            var outgoing=new OutgoingPaymentTracker(); balance.observeVerified(new BigDecimal("500"),0,"TEST");
            engine.tick(c,balance,0,0,(name,amount)->PaymentExecution.execute(false,name,amount,OutgoingPaymentTracker.Source.BALANCE_RULE,0,10000,outgoing,cmd->{},
                ()->history.record(PaymentHistory.Direction.PAID,name,amount,"BALANCE_RULE")));
            history.awaitWrites(); assertEquals(1,history.snapshot().stored()); assertEquals(1,outgoing.snapshot().size());
        }
    }
    @Test void twoRulesOperateIndependentlyWithFreshObservations() { c.balancePaymentRules.add(rule("Alice","1000","100")); tick("500",0); tick("1000",31); assertEquals(List.of("Bob 200","Alice 100"),sent); }
    @Test void unknownBalanceNeverFires() { tick(null,0); assertTrue(sent.isEmpty()); }
    @Test void staleBalanceNeverFires() { balance.observeVerified(new BigDecimal("500"),0,"TEST"); tick(null,6); assertTrue(sent.isEmpty()); }
    @Test void eachDispatchConsumesObservation() { c.balancePaymentRules.add(rule("Alice","500","100")); tick("500",0); tick(null,1); assertEquals(1,sent.size()); tick("500",2); assertEquals(2,sent.size()); }
    @Test void persistedDisarmedRuleSurvivesRestart() {
        engine.close(); engine=new BalanceRuleEngine(dir.resolve("state.json")); engine.awaitLoaded(); tick("500",0); engine.close();
        engine=new BalanceRuleEngine(dir.resolve("state.json")); engine.awaitLoaded(); tick("500",31); assertEquals(1,sent.size());
        tick("400",32); tick("500",33); assertEquals(2,sent.size());
    }
    @Test void ambiguousDispatchDisarmsWithoutRetry() {
        balance.observeVerified(new BigDecimal("500"),0,"TEST"); engine.tick(c,balance,0,0,(n,a)->PaymentSender.Result.UNCERTAIN); tick("500",31); assertTrue(sent.isEmpty());
    }
    @Test void retryLaterKeepsArmed() {
        balance.observeVerified(new BigDecimal("500"),0,"TEST"); engine.tick(c,balance,0,0,(n,a)->PaymentSender.Result.RETRY_LATER); tick("500",1); assertEquals(1,sent.size());
    }
    @Test void amountCannotExceedExistingDispatchCap() { rule.amount=new BigDecimal("1000000000001"); assertFalse(rule.valid()); }
    @Test void zeroCooldownStillRequiresFallingBelowThreshold() { rule.cooldownSeconds=0; tick("500",0); tick("500",1); assertEquals(1,sent.size()); tick("499",2); tick("500",3); assertEquals(2,sent.size()); }
}
