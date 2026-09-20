package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.config.MoneyValues;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Small tolerant table parser. It looks for row-local rank, username and amount instead of CSS classes. */
public final class LeaderboardHtmlParser {
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern PRIMARY_NAME = Pattern.compile("(?is)searchPlayer\\(\\s*['\"]([A-Za-z0-9_]{2,16})['\"]\\s*\\)");
    private static final Pattern SECONDARY_NAME = Pattern.compile("(?is)player\\.php\\?user=([^&\"']+)");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:\\$\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*[KMBT]?)");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private LeaderboardHtmlParser() {}

    public static List<LeaderboardRecord> parse(String html, String source, long observedAt) {
        if (html == null || html.length() > 5_000_000) return List.of();
        Map<String, LeaderboardRecord> records = new LinkedHashMap<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            String username = name(row);
            if (username == null) continue;
            String text = TAG.matcher(row).replaceAll(" ").replace("&nbsp;", " ").replace("&#36;", "$ ");
            List<BigDecimal> amounts = new ArrayList<>();
            Matcher values = AMOUNT.matcher(text);
            while (values.find()) try { amounts.add(parseAmount(values.group(1))); } catch (IllegalArgumentException ignored) {}
            if (amounts.size() < 2) continue; // rank plus value
            int rank;
            try { rank = amounts.getFirst().intValueExact(); } catch (ArithmeticException ex) { continue; }
            BigDecimal value = amounts.getLast();
            try {
                LeaderboardRecord record = new LeaderboardRecord(username, value, rank, observedAt, Set.of(source));
                records.merge(record.key(), record, LeaderboardRecord::merge);
            } catch (IllegalArgumentException ignored) {}
        }
        return List.copyOf(records.values());
    }

    private static String name(String row) {
        Matcher primary = PRIMARY_NAME.matcher(row);
        if (primary.find()) return primary.group(1);
        Matcher secondary = SECONDARY_NAME.matcher(row);
        if (!secondary.find()) return null;
        String value = URLDecoder.decode(secondary.group(1), StandardCharsets.UTF_8);
        return value.matches("[A-Za-z0-9_]{2,16}") ? value : null;
    }
    public static BigDecimal parseAmount(String text) {
        if (text == null) throw new IllegalArgumentException("Missing amount");
        return MoneyValues.parse(text.replace(",", "").replace("$", "").replaceAll("\\s+", ""));
    }
}
