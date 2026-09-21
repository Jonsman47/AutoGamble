package com.jonsman.autogamble.baltop;

import com.jonsman.autogamble.config.MoneyValues;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Parses displayed in-game item text only; no server-specific NBT keys or website formats. */
public final class BaltopParser {
    private static final Pattern TITLE = Pattern.compile("(?i)^\\s*Most Money\\s*\\(\\s*Page\\s+(\\d+)\\s*\\)\\s*$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{2,16}");
    private static final Pattern LABELED_NAME = Pattern.compile("(?i)(?:player|username)\\s*[:：]\\s*([A-Za-z0-9_]{2,16})");
    private static final Pattern RANK = Pattern.compile("(?i)(?:rank\\s*[:：]?\\s*#?\\s*|^\\s*#\\s*)([0-9][0-9,]*)");
    private static final Pattern MONEY = Pattern.compile("(?i)(?:balance|money|bal)\\s*[:：]?\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*[KMBT]?)|\\$\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*[KMBT]?)");
    private BaltopParser() {}
    public record Item(int slot, String itemId, String name, List<String> lore, String components) {
        public Item { lore = lore == null ? List.of() : List.copyOf(lore); }
    }
    public record Page(int number, List<Item> items, int inventorySlots, String title) {
        public Page { items = items == null ? List.of() : List.copyOf(items); }
    }
    public record Parsed(List<BaltopEntry> entries, int nextSlot, int failures, String fingerprint) {}
    public static int pageNumber(String title) {
        Matcher m = TITLE.matcher(clean(title));
        if (!m.matches()) return -1;
        try { int n = Integer.parseInt(m.group(1)); return n > 0 ? n : -1; } catch (NumberFormatException ex) { return -1; }
    }
    public static String clean(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9a-fk-orx]", "").replaceAll("\\p{C}", "").trim();
    }
    public static Parsed parse(Page page, long now) {
        Map<String, BaltopEntry> found = new LinkedHashMap<>(); int next = -1, failures = 0;
        StringBuilder fingerprint = new StringBuilder();
        for (Item item : page.items()) {
            if (item == null || item.slot() < 0 || item.slot() >= page.inventorySlots()) continue;
            String display = clean(item.name());
            List<String> lore = item.lore().stream().map(BaltopParser::clean).toList();
            fingerprint.append(item.slot()).append('=').append(display).append('|').append(String.join(";",lore)).append(';');
            if (display.toLowerCase(Locale.ROOT).contains("next page")) { next = item.slot(); continue; }
            String username = username(display, lore);
            BigDecimal balance = balance(display, lore);
            if (username == null && balance == null) continue; // decoration/navigation
            if (username == null || balance == null) { failures++; continue; }
            Integer rank = rank(display, lore);
            try { BaltopEntry row = new BaltopEntry(username,balance,rank,page.number(),now); found.put(row.key(),row); }
            catch (IllegalArgumentException ex) { failures++; }
        }
        return new Parsed(List.copyOf(found.values()),next,failures,Integer.toHexString(fingerprint.toString().hashCode()));
    }
    public static String username(String name, List<String> lore) {
        for (String text : lore) { Matcher m = LABELED_NAME.matcher(clean(text)); if (m.find()) return m.group(1); }
        String display = clean(name).replaceFirst("^\\s*#?\\d+[.,)]?\\s*[-–:]?\\s*", "").trim();
        return NAME.matcher(display).matches() && !display.equalsIgnoreCase("Next Page") ? display : null;
    }
    public static Integer rank(String name, List<String> lore) {
        for (String text : concat(name,lore)) {
            Matcher m=RANK.matcher(clean(text));
            if (m.find()) try { return Integer.parseInt(m.group(1).replace(",","")); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
    public static BigDecimal balance(String name, List<String> lore) {
        for (String text : concat(name,lore)) {
            Matcher m=MONEY.matcher(clean(text));
            if (m.find()) try { return MoneyValues.parse((m.group(1)==null?m.group(2):m.group(1)).replace(",","").replace(" ","")); }
            catch (RuntimeException ignored) {}
        }
        return null;
    }
    private static List<String> concat(String name,List<String> lore) { List<String> all=new ArrayList<>(); all.add(name); if(lore!=null) all.addAll(lore); return all; }
}
