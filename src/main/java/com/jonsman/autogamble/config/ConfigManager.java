package com.jonsman.autogamble.config;

import com.google.gson.*;
import org.slf4j.Logger;
import java.nio.file.*;
import java.io.IOException;
import java.util.function.Consumer;

/** Client-thread owner. Failed writes retain usable in-memory settings. */
public final class ConfigManager {
    private static final Gson GSON = MoneyValues.gson().setPrettyPrinting().create();
    private final Path path;
    private final Logger log;
    private AutoGambleConfig config = new AutoGambleConfig();
    private boolean futureVersion;
    private long revision;
    public long revision() { return revision; }
    public boolean isReadOnly() { return futureVersion; }
    public Path path() { return path; }
    public ConfigManager(Path path, Logger log) { this.path = path; this.log = log; }
    public AutoGambleConfig snapshot() { return GSON.fromJson(GSON.toJson(config), AutoGambleConfig.class); }
    public void load() {
        revision++;
        config = new AutoGambleConfig();
        futureVersion = false;
        try {
            if (Files.exists(path)) {
                JsonElement root = JsonParser.parseString(Files.readString(path));
                if (!root.isJsonObject()) throw new JsonParseException("Expected a JSON object");
                JsonObject object = root.getAsJsonObject();
                // Reject coercions such as strings used as booleans or numbers.
                JsonObject defaults = GSON.toJsonTree(config).getAsJsonObject();
                for (var entry : defaults.entrySet()) {
                    if (!object.has(entry.getKey())) continue;
                    JsonElement value = object.get(entry.getKey());
                    if (entry.getKey().equals("balancePaymentRules")) {
                        if (!value.isJsonArray()) throw new JsonParseException("Rules must be an array");
                        continue;
                    }
                    if (entry.getKey().equals("paymentAlertTiers")) {
                        if (!value.isJsonArray()) throw new JsonParseException("Alert tiers must be an array");
                        for (JsonElement item : value.getAsJsonArray()) {
                            if (!item.isJsonObject()) throw new JsonParseException("Alert tier must be an object");
                            JsonObject tier = item.getAsJsonObject();
                            if (!tier.has("enabled") || !tier.getAsJsonPrimitive("enabled").isBoolean()
                                    || !tier.has("threshold") || !tier.get("threshold").isJsonPrimitive()
                                    || !tier.has("sound") || !tier.getAsJsonPrimitive("sound").isString()
                                    || !tier.has("volume") || !tier.getAsJsonPrimitive("volume").isNumber()
                                    || !tier.has("pitch") || !tier.getAsJsonPrimitive("pitch").isNumber())
                                throw new JsonParseException("Invalid alert tier");
                            MoneyValues.parse(tier.get("threshold").getAsString());
                        }
                        continue;
                    }
                    if (entry.getKey().equals("incomingPaymentPatterns")) {
                        if (!value.isJsonArray()) throw new JsonParseException("Patterns must be an array");
                        for (JsonElement item : value.getAsJsonArray()) {
                            if (!item.isJsonObject()) throw new JsonParseException("Pattern must be an object");
                            JsonObject pattern = item.getAsJsonObject();
                            if (!pattern.has("regex") || !pattern.get("regex").isJsonPrimitive()
                                    || !pattern.getAsJsonPrimitive("regex").isString()) throw new JsonParseException("Missing pattern regex");
                            if (pattern.has("enabled") && (!pattern.get("enabled").isJsonPrimitive()
                                    || !pattern.getAsJsonPrimitive("enabled").isBoolean())) throw new JsonParseException("Invalid pattern enabled");
                        }
                        continue;
                    }
                    if (entry.getKey().equals("autoFollowThreshold") || entry.getKey().equals("recentMinimumAmount")) {
                        MoneyValues.parse(value.getAsString()); continue;
                    }
                    if (!value.isJsonPrimitive()) throw new JsonParseException("Invalid " + entry.getKey());
                    JsonPrimitive p = value.getAsJsonPrimitive();
                    if (entry.getValue().getAsJsonPrimitive().isBoolean() ? !p.isBoolean() : (entry.getValue().getAsJsonPrimitive().isString() ? !p.isString() : !p.isNumber()))
                        throw new JsonParseException("Wrong type: " + entry.getKey());
                }
                int version = object.has("configVersion") ? object.get("configVersion").getAsBigDecimal().intValueExact() : 0;
                for (String key : new String[]{"recentMaxLines", "storedTransactionHistoryLimit", "spamPaymentThreshold", "spamPaymentWindowSeconds", "spamWarningCooldownSeconds", "minimumPrefixLength", "maximumPrefixLength", "winnerDelayMinimumMs", "winnerDelayMaximumMs", "receiptDeduplicationWindowMs", "outgoingPaymentTrackingWindowMs", "minimumAlertSpacingMs", "autoPayConversionWindowSeconds", "autoPayAttributionDurationSeconds"}) {
                    if (object.has(key)) object.get(key).getAsBigDecimal().longValueExact();
                }
                if (version > 7) {
                    futureVersion = true;
                    config.enabled = false;
                    log.warn("[AutoGamble] Newer config version {}; using disabled defaults without overwriting", version);
                    return;
                }
                migrate(object, version);
                config = GSON.fromJson(object, AutoGambleConfig.class);
            }
        } catch (Exception e) {
            log.warn("[AutoGamble] Cannot load config; restoring defaults", e);
            config = new AutoGambleConfig();
            try { if (Files.isRegularFile(path)) Files.copy(path, path.resolveSibling("autogamble.json.invalid-" + System.currentTimeMillis())); }
            catch (IOException | SecurityException backupError) { log.warn("[AutoGamble] Could not back up invalid config", backupError); }
        }
        config.validate();
        save();
    }
    private void migrate(JsonObject object, int version) {
        if (version < 0) throw new JsonParseException("Negative configVersion");
        // Version 0 means an unversioned file. Missing fields retain constructor defaults.
        if (version <= 6) object.addProperty("configVersion", 7);
    }
    public void update(Consumer<AutoGambleConfig> editor) {
        if (futureVersion) {
            log.warn("[AutoGamble] Settings are read-only until the newer configuration is replaced or the mod is upgraded");
            return;
        }
        AutoGambleConfig next = snapshot();
        editor.accept(next);
        next.validate();
        config = next;
        revision++;
        save();
    }
    /** Strict UI commit: rejected drafts never touch memory or disk. */
    public boolean commit(AutoGambleConfig candidate) {
        if (futureVersion || !SettingsValidation.errors(candidate).isEmpty()) return false;
        config = GSON.fromJson(GSON.toJson(candidate), AutoGambleConfig.class);
        config.validate(); revision++;
        return save();
    }
    public boolean save() {
        if (futureVersion) return false;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(temporary, GSON.toJson(config) + System.lineSeparator());
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException | SecurityException e) {
            log.warn("[AutoGamble] Cannot save config; continuing with in-memory settings", e);
            return false;
        }
    }
}
