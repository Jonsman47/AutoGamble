package com.jonsman.autogamble.targeting;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class DonutStatsOrgProvider implements LeaderboardProvider {
    private static final String BASE = "https://donutstats.org/leaderboards.php?type=";
    @Override public String id() { return "donutstats.org"; }
    @Override public LeaderboardSnapshot fetch(HttpClient client) throws Exception {
        long now = System.currentTimeMillis();
        var money = LeaderboardHtmlParser.parse(tryGet(client, "money"), id(), now);
        var economy = merge(LeaderboardHtmlParser.parse(tryGet(client, "sell"), id() + ":sell", now),
                LeaderboardHtmlParser.parse(tryGet(client, "shop"), id() + ":shop", now));
        if (money.isEmpty() && economy.isEmpty()) throw new IllegalStateException("No leaderboard rows found");
        return new LeaderboardSnapshot(money, economy, now);
    }
    private static String tryGet(HttpClient client, String type) { try { return get(client, type); } catch (Exception ex) { return ""; } }
    private static String get(HttpClient client, String type) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + type)).timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "AutoGamble-Fabric/1.2.4 (+https://github.com/Jonsman47/AutoGamble)").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 5_000_000)
            throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }
    static List<LeaderboardRecord> merge(List<LeaderboardRecord> first, List<LeaderboardRecord> second) {
        Map<String, LeaderboardRecord> result = new LinkedHashMap<>();
        for (LeaderboardRecord row : first) result.merge(row.key(), row, LeaderboardRecord::merge);
        for (LeaderboardRecord row : second) result.merge(row.key(), row, LeaderboardRecord::merge);
        return List.copyOf(result.values());
    }
}
