package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.payment.*;
import com.jonsman.autogamble.targeting.ExperimentalFeatures;
import com.jonsman.autogamble.ui.TargetingSettingsScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyPaymentRestorationTest {
    @TempDir Path directory;
    private static Candidate player(String name){return new Candidate(null,name);}
    @Test void activeModeIsLegacyAndPrefixSuggestionsSupplyRecipients() {
        assertFalse(ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED);
        var config=new AutoGambleConfig();config.smartRandomWeight=0;config.moneyLeaderboardWeight=100;
        var history=new PlayerSelectionManager();var discovery=new PrefixPlayerDiscovery(new Random(1),1,3);
        var request=discovery.poll(0,history,true).orElseThrow();
        assertTrue(request.command().startsWith("/pay "));
        String online=request.prefix()+"Player";
        assertTrue(discovery.complete(request.token(),List.of(online),"Local",true,new FailedTargetBlacklist(),history,true,1));
        assertEquals(online,history.selectLegacy(discovery.candidates(),true,new Random()).orElseThrow().username());
        history.markPaid(online); // Legacy mode has no new ten-minute payment cooldown.
        assertEquals(online,history.selectLegacy(discovery.candidates(),true,new Random()).orElseThrow().username());
    }
    @Test void zeroMinimumIsExactLegacyPassThroughWithoutAnyBalanceLookup() {
        var names=List.of(player("Bob"),player("Alice"));
        assertSame(names,PaymentBalanceFilter.eligible(names,BigDecimal.ZERO,name->{throw new AssertionError("No cache lookup at zero");},new Random()));
        assertSame(names,PaymentBalanceFilter.eligible(names,MoneyValues.parse("0"),null,null));
    }
    @Test void positiveMinimumAllowsKnownAndSkipsLowOrUnknown() {
        var names=List.of(player("Rich"),player("Low"),player("Unknown"));
        var balances=Map.of("Rich",MoneyValues.parse("20M"),"Low",MoneyValues.parse("2M"));
        assertEquals(List.of(player("Rich")),PaymentBalanceFilter.eligible(names,MoneyValues.parse("5M"),balances::get,new Random(2)));
        assertTrue(PaymentBalanceFilter.eligible(List.of(player("Unknown")),MoneyValues.parse("5M"),balances::get,new Random()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->PaymentBalanceFilter.eligible(names,BigDecimal.ONE.negate(),balances::get,new Random()));
    }
    @Test void balanceChecksAreBoundedAndCycleCanContinueLater() {
        var names=new ArrayList<Candidate>();for(int i=0;i<1000;i++)names.add(player("Player"+i));
        int[] checks={0};
        var result=PaymentBalanceFilter.eligible(names,BigDecimal.ONE,name->{checks[0]++;return null;},new Random(7));
        assertTrue(result.isEmpty());assertEquals(30,checks[0]);
        checks[0]=0;
        PaymentBalanceFilter.eligible(names,BigDecimal.ONE,name->{checks[0]++;return BigDecimal.TEN;},new Random(8));
        assertEquals(30,checks[0]);
    }
    @Test void configPersistsMinimumAndOldBaltopWeightCannotEnableFeature() throws Exception {
        Path path=directory.resolve("autogamble.json");
        Files.writeString(path,"{\"configVersion\":10,\"smartRandomWeight\":0,\"moneyLeaderboardWeight\":100,\"autoPayAmount\":67}");
        var manager=new ConfigManager(path,LoggerFactory.getLogger("test"));manager.load();
        assertEquals(11,manager.snapshot().configVersion);
        assertEquals(BigDecimal.ZERO,manager.snapshot().minimumPaymentBalance);
        assertEquals(100,manager.snapshot().moneyLeaderboardWeight);assertFalse(ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED);
        manager.update(c->c.minimumPaymentBalance=MoneyValues.parse("5M"));manager.load();
        assertEquals(0,manager.snapshot().minimumPaymentBalance.compareTo(new BigDecimal("5000000")));
        assertEquals(67,manager.snapshot().autoPayAmount);
    }
    @Test void comingSoonActionShowsClearMessage() {
        assertEquals(ExperimentalFeatures.UNAVAILABLE_MESSAGE,TargetingSettingsScreen.unavailablePaymentMessage());
        assertTrue(TargetingSettingsScreen.unavailablePaymentMessage().contains("Coming soon"));
    }
}
