package com.jonsman.autogamble.targeting;

import com.google.gson.*;
import com.jonsman.autogamble.config.MoneyValues;
import com.jonsman.autogamble.history.AtomicFiles;
import org.slf4j.Logger;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Background-only network/cache owner. Snapshot reads are lock-free and safe on the client thread. */
public final class LeaderboardService implements AutoCloseable {
    public static final long REFRESH_INTERVAL_MS = Duration.ofMinutes(30).toMillis();
    public static final long FAILURE_BACKOFF_MS = Duration.ofMinutes(5).toMillis();
    private static final Gson GSON = MoneyValues.gson().setPrettyPrinting().create();
    public record ProviderStatus(String state, String detail, long lastSuccess) {}
    public record Status(ProviderStatus primary, ProviderStatus secondary, int moneyPlayers,
                         int economyPlayers, long lastRefresh, boolean refreshing) {}
    private static final class CacheData {
        int version = 1; long fetchedAt;
        List<LeaderboardRecord> money = new ArrayList<>(), economy = new ArrayList<>();
    }

    private final Path cachePath;
    private final Logger log;
    private final HttpClient http;
    private final List<LeaderboardProvider> providers;
    private final ExecutorService executor;
    private volatile LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty();
    private volatile ProviderStatus primary = new ProviderStatus("Not refreshed", "", 0);
    private volatile ProviderStatus secondary = new ProviderStatus("Not refreshed", "", 0);
    private volatile boolean refreshing;
    private volatile long nextRefresh;

    public LeaderboardService(Path cachePath, Logger log) {
        this(cachePath, log, List.of(new DonutSmpStatsProvider(), new DonutStatsOrgProvider()),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }
    LeaderboardService(Path cachePath, Logger log, List<LeaderboardProvider> providers, HttpClient http) {
        this.cachePath = cachePath; this.log = log; this.providers = List.copyOf(providers); this.http = http;
        this.executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "AutoGamble-Leaderboards"); t.setDaemon(true); return t; });
        loadCache();
    }
    public LeaderboardSnapshot snapshot() { return snapshot; }
    public Status status() { return new Status(primary, secondary, snapshot.money().size(), snapshot.economy().size(), snapshot.fetchedAt(), refreshing); }
    public void tick(long now) { if (!refreshing && now >= nextRefresh) refresh(); }
    public boolean refresh() {
        synchronized (this) {
            if (refreshing) return false;
            refreshing = true; nextRefresh = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
            primary = new ProviderStatus("Refreshing", "", primary.lastSuccess());
            secondary = new ProviderStatus("Refreshing", "", secondary.lastSuccess());
        }
        executor.execute(this::refreshInBackground);
        return true;
    }
    private void refreshInBackground() {
        List<LeaderboardSnapshot> successes = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            LeaderboardProvider provider = providers.get(i);
            try {
                LeaderboardSnapshot value = provider.fetch(http);
                successes.add(value);
                setStatus(i, new ProviderStatus("Online", value.money().size() + " money, " + value.economy().size() + " economy", value.fetchedAt()));
            } catch (Exception ex) {
                String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                setStatus(i, new ProviderStatus("Error", detail.length() > 100 ? detail.substring(0, 100) : detail, statusFor(i).lastSuccess()));
                log.warn("[AutoGamble] Leaderboard provider {} failed; continuing with other providers/cache: {}", provider.id(), detail);
            }
        }
        try {
            if (!successes.isEmpty()) {
                LeaderboardSnapshot merged = merge(successes);
                snapshot = merged; saveCache(merged); nextRefresh = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
            } else nextRefresh = System.currentTimeMillis() + FAILURE_BACKOFF_MS;
        } finally { refreshing = false; }
    }
    private ProviderStatus statusFor(int index) { return index == 0 ? primary : secondary; }
    private void setStatus(int index, ProviderStatus status) { if (index == 0) primary = status; else secondary = status; }
    static LeaderboardSnapshot merge(List<LeaderboardSnapshot> values) {
        Map<String, LeaderboardRecord> money = new LinkedHashMap<>(), economy = new LinkedHashMap<>();
        long fetched = 0;
        for (LeaderboardSnapshot value : values) {
            fetched = Math.max(fetched, value.fetchedAt());
            for (LeaderboardRecord row : value.money()) money.merge(row.key(), row, LeaderboardRecord::merge);
            for (LeaderboardRecord row : value.economy()) economy.merge(row.key(), row, LeaderboardRecord::merge);
        }
        return new LeaderboardSnapshot(List.copyOf(money.values()), List.copyOf(economy.values()), fetched);
    }
    private void loadCache() {
        try {
            if (!Files.exists(cachePath)) { nextRefresh = 0; return; }
            CacheData cache = GSON.fromJson(Files.readString(cachePath), CacheData.class);
            if (cache == null || cache.version != 1 || cache.money == null || cache.economy == null) throw new IllegalStateException("Invalid cache");
            snapshot = merge(List.of(new LeaderboardSnapshot(cache.money, cache.economy, cache.fetchedAt)));
            nextRefresh = 0; // cached data is immediately usable, and refresh begins on the next tick
        } catch (Exception ex) {
            log.warn("[AutoGamble] Ignoring malformed leaderboard cache", ex); snapshot = LeaderboardSnapshot.empty(); nextRefresh = 0;
        }
    }
    private void saveCache(LeaderboardSnapshot value) {
        try {
            CacheData cache = new CacheData(); cache.fetchedAt = value.fetchedAt();
            cache.money = new ArrayList<>(value.money()); cache.economy = new ArrayList<>(value.economy());
            AtomicFiles.write(cachePath, GSON.toJson(cache) + System.lineSeparator());
        } catch (Exception ex) { log.warn("[AutoGamble] Could not save leaderboard cache", ex); }
    }
    @Override public void close() { executor.shutdownNow(); }
}
