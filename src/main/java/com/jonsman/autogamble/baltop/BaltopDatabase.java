package com.jonsman.autogamble.baltop;

import com.google.gson.*;
import com.jonsman.autogamble.config.MoneyValues;
import com.jonsman.autogamble.history.AtomicFiles;
import org.slf4j.Logger;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Client-thread owner; disk writes are coalesced on one daemon thread. No website cache is imported. */
public final class BaltopDatabase implements AutoCloseable {
    private static final Gson GSON = MoneyValues.gson().setPrettyPrinting().create();
    private static final int VERSION = 1;
    public record Snapshot(List<BaltopEntry> entries, int highestPage, long scanStarted, long lastScan,
                           boolean complete, long duplicates, String warning) {}
    private static final class Data {
        int version = VERSION, highestPage; long scanStarted, lastScan, duplicates;
        boolean complete; List<BaltopEntry> entries = new ArrayList<>();
    }
    private final Path path; private final Logger log; private final ScheduledExecutorService writer;
    private final Map<String, BaltopEntry> entries = new LinkedHashMap<>();
    private int highestPage; private long scanStarted, lastScan, duplicates;
    private boolean complete, dirty, scheduled; private String warning = "";
    public BaltopDatabase(Path path, Logger log) {
        this.path = path; this.log = log;
        writer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "AutoGamble-Baltop-Cache"); t.setDaemon(true); return t; });
        load();
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(entries.values()), highestPage, scanStarted, lastScan, complete, duplicates, warning);
    }
    /** Only a contiguous, nonempty page advances resume metadata; the newest observation wins for duplicate names. */
    public synchronized boolean recordPage(int page, List<BaltopEntry> rows, long now) {
        if (page < 1 || page > highestPage + 1 || rows == null || rows.isEmpty()) return false;
        if (scanStarted == 0) scanStarted = now;
        for (BaltopEntry row : rows) {
            if (row == null || row.page() != page) continue;
            if (entries.put(row.key(), row) != null) duplicates++;
        }
        highestPage = Math.max(highestPage, page); lastScan = now; complete = false; changed(); return true;
    }
    public synchronized void completed() { complete = true; changed(); }
    public boolean reset() {
        // Deletion is restricted to this one configured cache file; unrelated data is untouched.
        try { return writer.submit(() -> {
            synchronized (this) {
                Files.deleteIfExists(path);
                entries.clear(); highestPage = 0; scanStarted = lastScan = duplicates = 0;
                complete = dirty = false; warning = ""; return true;
            }
        }).get(5,TimeUnit.SECONDS); }
        catch (Exception ex) { synchronized (this) { warning = "Could not reset baltop cache"; } log.warn("[AutoGamble] Baltop reset failed", ex); return false; }
    }
    public static List<BaltopEntry> filter(Collection<BaltopEntry> rows, BigDecimal min, BigDecimal max, String search) {
        if (rows == null || min == null || min.signum() < 0 || max != null && max.compareTo(min) < 0) return List.of();
        String query = search == null ? "" : search.toLowerCase(Locale.ROOT);
        return rows.stream().filter(e -> e != null && e.balance().compareTo(min) >= 0
                && (max == null || e.balance().compareTo(max) <= 0)
                && e.key().contains(query)).toList();
    }
    private void load() {
        try {
            if (!Files.exists(path)) return;
            Data data = GSON.fromJson(Files.readString(path), Data.class);
            if (data == null || data.version != VERSION || data.entries == null || data.highestPage < 0) throw new IOException("Bad baltop cache");
            for (BaltopEntry row : data.entries) if (row != null && row.page() <= data.highestPage) entries.put(row.key(), row);
            highestPage = data.highestPage; scanStarted = data.scanStarted; lastScan = data.lastScan;
            duplicates = data.duplicates; complete = data.complete;
        } catch (Exception ex) {
            warning = "Baltop cache corrupt; original preserved"; log.warn("[AutoGamble] Cannot load baltop cache", ex);
            try { AtomicFiles.backup(path); } catch (IOException backup) { log.warn("[AutoGamble] Could not back up baltop cache", backup); }
            entries.clear(); highestPage = 0;
        }
    }
    private synchronized void changed() {
        dirty = true;
        if (!scheduled) { scheduled = true; writer.schedule(this::flush, 300, TimeUnit.MILLISECONDS); }
    }
    public void flush() {
        Data data;
        synchronized (this) {
            scheduled = false; if (!dirty) return; dirty = false;
            data = new Data(); data.highestPage = highestPage; data.scanStarted = scanStarted;
            data.lastScan = lastScan; data.duplicates = duplicates; data.complete = complete; data.entries = new ArrayList<>(entries.values());
        }
        try { AtomicFiles.write(path, GSON.toJson(data) + System.lineSeparator()); }
        catch (IOException | SecurityException ex) { synchronized (this) { dirty = true; warning = "Could not save baltop cache"; } log.warn("[AutoGamble] Baltop cache write failed", ex); }
    }
    @Override public void close() {
        try { writer.submit(this::flush).get(5,TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ex) { log.warn("[AutoGamble] Baltop cache shutdown flush failed",ex); }
        writer.shutdown();
    }
}
