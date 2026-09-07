package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class Version121ConfigTest {
    @TempDir Path dir;
    @Test void newDefaultsAreSafeAndDocumented() {
        var c=new AutoGambleConfig();assertEquals(7,c.configVersion);assertTrue(c.paymentSoundAlertsEnabled);
        assertEquals(250,c.minimumAlertSpacingMs);assertEquals(1800,c.autoPayConversionWindowSeconds);assertEquals(1800,c.autoPayAttributionDurationSeconds);
        assertEquals(5,c.paymentAlertTiers.size());assertTrue(c.dryRunMode);assertFalse(c.autoPayEnabled);assertFalse(c.gambleEnabled);
    }
    @Test void versionSixMigrationPreservesSettingsAndAddsDefaults() throws Exception {
        Path p=dir.resolve("autogamble.json");Files.writeString(p,"{\"configVersion\":6,\"dryRunMode\":false,\"autoPayAmount\":77,\"winChance\":0.31,\"autoFollowGoodCustomersEnabled\":true}");
        var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();var c=m.snapshot();
        assertEquals(7,c.configVersion);assertFalse(c.dryRunMode);assertEquals(77,c.autoPayAmount);assertEquals(.31,c.winChance);assertTrue(c.autoFollowGoodCustomersEnabled);assertTrue(c.paymentSoundAlertsEnabled);
    }
    @Test void soundAndRoiSettingsPersist() {
        Path p=dir.resolve("autogamble.json");var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));m.load();m.update(c->{
            c.paymentSoundAlertsEnabled=false;c.minimumAlertSpacingMs=999;c.autoPayConversionWindowSeconds=45;c.autoPayAttributionDurationSeconds=90;
            c.paymentAlertTiers.get(2).sound="minecraft:block.note_block.bell";c.paymentAlertTiers.get(2).volume=2;c.paymentAlertTiers.get(2).pitch=.7f;
        });m.load();var c=m.snapshot();assertFalse(c.paymentSoundAlertsEnabled);assertEquals(999,c.minimumAlertSpacingMs);assertEquals(45,c.autoPayConversionWindowSeconds);assertEquals(90,c.autoPayAttributionDurationSeconds);assertEquals(2,c.paymentAlertTiers.get(2).volume);
    }
    @Test void malformedTierDataRecoversSafely() throws Exception {
        Path p=dir.resolve("autogamble.json");Files.writeString(p,"{\"configVersion\":7,\"paymentAlertTiers\":[{\"enabled\":\"yes\"}]}");
        var m=new ConfigManager(p,org.slf4j.LoggerFactory.getLogger("test"));assertDoesNotThrow(m::load);assertEquals(5,m.snapshot().paymentAlertTiers.size());
    }
}
