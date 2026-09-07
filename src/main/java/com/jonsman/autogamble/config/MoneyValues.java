package com.jonsman.autogamble.config;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.Locale;

/** Strict settings money syntax; unlike incoming payments, zero is permitted. */
public final class MoneyValues {
    private MoneyValues() {}
    public static BigDecimal parse(String text) {
        if (text == null) throw new IllegalArgumentException("Enter an amount");
        String s = text.trim();
        if (s.length() > 64 || !s.matches("[0-9]+(?:\\.[0-9]{1,2})?[kKmMbBtT]?"))
            throw new IllegalArgumentException("Use a normal amount such as 5000000 or 5M");
        int power = switch (s.toUpperCase(Locale.ROOT).charAt(s.length()-1)) {
            case 'K' -> 3; case 'M' -> 6; case 'B' -> 9; case 'T' -> 12; default -> 0;
        };
        return new BigDecimal(power == 0 ? s : s.substring(0, s.length()-1)).scaleByPowerOfTen(power).stripTrailingZeros();
    }
    public static boolean valid(BigDecimal n, boolean positive) {
        return n != null && n.precision() <= 80 && n.scale() <= 2 && n.scale() >= -80 && (positive ? n.signum() > 0 : n.signum() >= 0);
    }
    public static String plain(BigDecimal n) { return n.stripTrailingZeros().toPlainString(); }
    public static String display(BigDecimal n) {
        if (n == null) return "UNKNOWN";
        String[] suffix = {"", "K", "M", "B", "T"};
        for (int i = 4; i > 0; i--) if (n.abs().compareTo(BigDecimal.TEN.pow(i*3)) >= 0)
            return plain(n.movePointLeft(i*3)) + suffix[i];
        return plain(n);
    }
    public static GsonBuilder gson() {
        return new GsonBuilder().registerTypeAdapter(BigDecimal.class, new MoneyAdapter());
    }
    private static final class MoneyAdapter implements JsonSerializer<BigDecimal>, JsonDeserializer<BigDecimal> {
        public JsonElement serialize(BigDecimal n, java.lang.reflect.Type t, JsonSerializationContext c) { return new JsonPrimitive(plain(n)); }
        public BigDecimal deserialize(JsonElement e, java.lang.reflect.Type t, JsonDeserializationContext c) {
            try { return parse(e.getAsString()); } catch (RuntimeException ex) { throw new JsonParseException("Invalid money", ex); }
        }
    }
}
