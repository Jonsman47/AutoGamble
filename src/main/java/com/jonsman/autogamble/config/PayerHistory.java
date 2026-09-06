package com.jonsman.autogamble.config;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Client-thread persistent data, independent of session and config resets. */
public final class PayerHistory {
    private final Set<String> players = new HashSet<>();
    private final Path path;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("autogamble");
    public PayerHistory() { this.path = null; }
    public PayerHistory(Path path) { this.path = path; load(); }
    private String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public boolean contains(String name) { return players.contains(key(name)); }
    public int size() { return players.size(); }
    public void add(String name) { if (players.add(key(name))) save(); }
    public boolean reset() { var old = new HashSet<>(players); players.clear(); if (save()) return true; players.addAll(old); return false; }
    private void load() {
        if (path == null || !Files.exists(path)) return;
        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported payer data version");
            var loaded = new HashSet<String>();
            for (var entry : root.getAsJsonArray("players")) {
                String name = entry.getAsString();
                if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid payer name");
                loaded.add(key(name));
            }
            players.addAll(loaded);
        } catch (Exception e) {
            LOG.error("[AutoGamble] Cannot load payer history; recovering empty history", e);
            try { Files.copy(path, path.resolveSibling(path.getFileName()+".corrupt-"+System.currentTimeMillis())); save(); }
            catch (Exception backupError) { LOG.error("[AutoGamble] Could not back up payer history", backupError); }
        }
    }
    private boolean save() {
        if (path == null) return true;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            var root = new JsonObject(); root.addProperty("version", 1);
            var values = new JsonArray(); players.stream().sorted().forEach(values::add); root.add("players", values);
            var temp = path.resolveSibling(path.getFileName()+".tmp");
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception e) { LOG.error("[AutoGamble] Could not persist payer history; in-memory history retained", e); return false; }
    }
}
