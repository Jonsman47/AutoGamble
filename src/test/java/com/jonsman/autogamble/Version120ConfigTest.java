package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class Version120ConfigTest {
    @TempDir Path dir;
    ConfigManager manager() { return new ConfigManager(dir.resolve("autogamble.json"),org.slf4j.LoggerFactory.getLogger("test")); }
    @Test void schemaFiveMigrationPreservesExistingValues() throws Exception {
        Files.writeString(dir.resolve("autogamble.json"),"""
            {"configVersion":5,"dryRunMode":false,"gambleEnabled":true,"winChance":0.37,"firstTimeWinBonus":0.23,
             "spamPaymentThreshold":7,"spamWarningMessage":"Please wait!","autoPayAmount":19,"minimumPrefixLength":2,
             "incomingPaymentPatterns":[{"enabled":false,"regex":"(?<sender>Bob) paid (?<amount>[0-9]+)"}]}
            """);
        var m=manager();m.load();var c=m.snapshot(); assertEquals(7,c.configVersion); assertFalse(c.dryRunMode);
        assertTrue(c.gambleEnabled);assertEquals(.37,c.winChance);assertEquals(.23,c.firstTimeWinBonus);
        assertEquals(7,c.spamPaymentThreshold);assertEquals("Please wait!",c.spamWarningMessage);assertEquals(19,c.autoPayAmount);
        assertEquals(2,c.minimumPrefixLength);assertEquals(1,c.incomingPaymentPatterns.size());
        assertFalse(c.autoFollowGoodCustomersEnabled);assertFalse(c.automaticBalancePaymentsEnabled);
    }
    @Test void allNewSettingsAndRulesRoundTrip() {
        var m=manager();m.load();m.update(c->{c.autoFollowThreshold=MoneyValues.parse("1.5m");c.recentMaximumAmount=MoneyValues.parse("1b");
            c.recentNewestFirst=false;c.recentMaxLines=42;c.storedTransactionHistoryLimit=100;
            var r=new BalanceRule();r.player="Alice";r.threshold=MoneyValues.parse("500m");r.amount=MoneyValues.parse("200m");r.enabled=true;c.balancePaymentRules.add(r);});
        m.load();var c=m.snapshot();assertEquals(0,c.autoFollowThreshold.compareTo(new BigDecimal("1500000")));assertEquals(42,c.recentMaxLines);
        assertEquals(1,c.balancePaymentRules.size());assertTrue(c.balancePaymentRules.getFirst().enabled);assertFalse(c.recentNewestFirst);
    }
    @Test void unlimitedMaximumRoundTrips() { var m=manager();m.load();assertNull(m.snapshot().recentMaximumAmount);m.load();assertNull(m.snapshot().recentMaximumAmount); }
    @Test void draftSupportsSuffixesAndUnlimitedMaximum() { var d=new SettingsDraft(new AutoGambleConfig());d.text(SettingsDraft.Field.FOLLOW_THRESHOLD,"5M");d.text(SettingsDraft.Field.RECENT_MAX,"");assertTrue(d.validate().isEmpty());assertEquals(0,d.working.autoFollowThreshold.compareTo(new BigDecimal("5000000"))); }
    @Test void invalidRecentLimitsRejected() { var c=new AutoGambleConfig();c.recentMaxLines=0;assertFalse(SettingsValidation.errors(c).isEmpty());c.recentMaxLines=1;c.storedTransactionHistoryLimit=99;assertFalse(SettingsValidation.errors(c).isEmpty()); }
    @Test void invalidRulesCannotCommit() { var m=manager();m.load();var c=m.snapshot();c.balancePaymentRules.add(new BalanceRule());assertFalse(m.commit(c)); }
}
