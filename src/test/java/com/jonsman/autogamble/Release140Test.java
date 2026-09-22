package com.jonsman.autogamble;

import com.google.gson.JsonParser;
import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.ui.AutoGambleSettingsScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Release140Test {
    @TempDir Path folder;
    private ConfigManager manager() { return new ConfigManager(folder.resolve("autogamble.json"),LoggerFactory.getLogger("test")); }
    @Test void freshInstallDefaultsAndReload() {
        var m=manager();m.load();var c=m.snapshot();
        assertEquals(12,c.configVersion);
        assertEquals(1,c.autoPayAmount);assertEquals(.2,c.minimumAutoPayDelaySeconds);assertEquals(1.8,c.maximumAutoPayDelaySeconds);
        assertEquals(0,new BigDecimal("25000000").compareTo(c.minimumPaymentBalance));
        assertTrue(c.preferUnpaidPlayers);assertTrue(c.excludeNumericOnlyNames);
        assertEquals(.425,c.winChance);assertEquals(.15,c.firstTimeWinBonus);assertEquals(100_000_000,c.maximumBet);
        assertEquals(250,c.winnerDelayMinimumMs);assertEquals(1000,c.winnerDelayMaximumMs);
        assertEquals(AutoGambleConfig.defaultQuickCommands(),c.quickCommands);
        var restored=manager();restored.load();assertEquals(c.accentColor,restored.snapshot().accentColor);
    }
    @Test void existing124And13ValuesSurviveMigration() throws Exception {
        Path path=folder.resolve("autogamble.json");
        for (int version:new int[]{8,11}) {
            Files.writeString(path,"{\"configVersion\":"+version+",\"autoPayAmount\":7,\"minimumAutoPayDelaySeconds\":0.5,\"maximumAutoPayDelaySeconds\":3.1,\"minimumPaymentBalance\":\"100M\",\"winChance\":0.37,\"firstTimeWinBonus\":0.19,\"maximumBet\":9000000,\"winnerDelayMinimumMs\":400,\"winnerDelayMaximumMs\":900}");
            var m=manager();m.load();var c=m.snapshot();
            assertEquals(12,c.configVersion);assertEquals(7,c.autoPayAmount);assertEquals(.5,c.minimumAutoPayDelaySeconds);
            assertEquals(3.1,c.maximumAutoPayDelaySeconds);assertEquals(MoneyValues.parse("100M"),c.minimumPaymentBalance);
            assertEquals(.37,c.winChance);assertEquals(.19,c.firstTimeWinBonus);assertEquals(9_000_000,c.maximumBet);
            assertEquals(400,c.winnerDelayMinimumMs);assertEquals(900,c.winnerDelayMaximumMs);
            assertEquals(AutoGambleConfig.defaultQuickCommands(),c.quickCommands);
        }
        Files.writeString(path,"{\"configVersion\":11,\"minimumPaymentBalance\":\"0\",\"minimumAutoPayDelaySeconds\":0.5,\"maximumBet\":2000000}");
        var previous=manager();previous.load();
        assertEquals(0,previous.snapshot().minimumPaymentBalance.signum());
        assertEquals(.5,previous.snapshot().minimumAutoPayDelaySeconds);
        assertEquals(2_000_000,previous.snapshot().maximumBet);
    }
    @Test void customizationPersistsAndQuickCommandsRequireExplicitValidInput() {
        var m=manager();m.load();
        m.update(c->{c.quickCommands=List.of("/bal","/baltop");c.compactMenu=true;c.showLessUsedSections=false;c.accentColor="ff8b80";});
        var loaded=manager();loaded.load();var c=loaded.snapshot();
        assertEquals(List.of("/bal","/baltop"),c.quickCommands);assertTrue(c.compactMenu);assertFalse(c.showLessUsedSections);
        assertEquals("FF8B80",c.accentColor);
        assertEquals("bal",QuickCommands.wireCommand("/bal"));
        var sent=new java.util.ArrayList<String>();
        QuickCommands.executeClicked("/baltop",sent::add);
        assertEquals(List.of("baltop"),sent);
        assertFalse(QuickCommands.valid("/pay Bob; stop"));assertFalse(QuickCommands.valid("/pay\nBob"));
        assertThrows(IllegalArgumentException.class,()->QuickCommands.wireCommand("bad"));
        loaded.update(edit->{edit.quickCommands.clear();edit.compactMenu=false;edit.showLessUsedSections=true;edit.accentColor="55DDBB";});
        loaded.load();assertTrue(loaded.snapshot().quickCommands.isEmpty());assertFalse(loaded.snapshot().compactMenu);
        c.resetCustomization();assertEquals(AutoGambleConfig.defaultQuickCommands(),c.quickCommands);
        assertFalse(c.compactMenu);assertTrue(c.showLessUsedSections);assertEquals("55DDBB",c.accentColor);
    }
    @Test void navigationHasExactlyFourMajorCategories() throws Exception {
        var field=AutoGambleSettingsScreen.class.getDeclaredField("CATEGORIES");field.setAccessible(true);
        assertArrayEquals(new String[]{"Automation","Gambling","Data & Advanced","Customization"},(String[])field.get(null));
        assertEquals(0,AutoGambleSettingsScreen.categoryFor(15));assertEquals(1,AutoGambleSettingsScreen.categoryFor(17));
        assertEquals(2,AutoGambleSettingsScreen.categoryFor(22));assertEquals(3,AutoGambleSettingsScreen.categoryFor(14));
    }
    @Test void metadataIncludesIconAndAuthor() throws Exception {
        try(var stream=getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(stream);
            var root=JsonParser.parseString(new String(stream.readAllBytes())).getAsJsonObject();
            assertEquals("AutoGamble",root.get("name").getAsString());
            assertEquals("Jonsman",root.getAsJsonArray("authors").get(0).getAsString());
            assertEquals("1.4.0",root.get("version").getAsString());
            assertEquals("assets/autogamble/icon.png",root.get("icon").getAsString());
            assertNotNull(getClass().getResourceAsStream("/"+root.get("icon").getAsString()));
        }
    }
}
