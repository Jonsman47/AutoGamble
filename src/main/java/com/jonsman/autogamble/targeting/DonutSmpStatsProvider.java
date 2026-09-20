package com.jonsman.autogamble.targeting;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class DonutSmpStatsProvider implements LeaderboardProvider {
    private static final URI MONEY = URI.create("https://donutsmpstats.net/leaderboards/money?limit=250");
    private static final URI SELL = URI.create("https://donutsmpstats.net/leaderboards/sell?limit=250");
    @Override public String id() { return "donutsmpstats.net"; }
    @Override public LeaderboardSnapshot fetch(HttpClient client) throws Exception {
        long now = System.currentTimeMillis();
        String money = tryGet(client, MONEY), sell = tryGet(client, SELL);
        var moneyRows = LeaderboardHtmlParser.parse(money, id(), now);
        var economyRows = LeaderboardHtmlParser.parse(sell, id() + ":sell", now);
        if (moneyRows.isEmpty() && economyRows.isEmpty()) throw new IllegalStateException("No leaderboard rows found");
        return new LeaderboardSnapshot(moneyRows, economyRows, now);
    }
    private static String tryGet(HttpClient client, URI uri) { try { return get(client, uri); } catch (Exception ex) { return ""; } }
    private static String get(HttpClient client, URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "AutoGamble-Fabric/1.2.4 (+https://github.com/Jonsman47/AutoGamble)").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 5_000_000)
            throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }
}
