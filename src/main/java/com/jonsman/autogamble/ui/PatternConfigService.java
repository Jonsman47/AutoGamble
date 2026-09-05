package com.jonsman.autogamble.ui;

import com.google.gson.*;
import com.jonsman.autogamble.config.AutoGambleConfig.IncomingPattern;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Imports only patterns into an unsaved draft; never imports enabled/payment-mode flags. */
public final class PatternConfigService {
    private PatternConfigService() {}
    public static void validate(String regex) {
        if (regex == null || regex.length() > 512) throw new IllegalArgumentException("Pattern exceeds 512 characters");
        if (!Pattern.compile(regex).namedGroups().keySet().containsAll(Set.of("sender", "amount")))
            throw new IllegalArgumentException("Pattern needs named sender and amount groups");
    }
    public static List<IncomingPattern> read(Path path) throws java.io.IOException {
        if (Files.size(path) > 65536) throw new IllegalArgumentException("Config is too large");
        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var array = root.getAsJsonArray("incomingPaymentPatterns");
        if (array == null || array.size() > 16) throw new IllegalArgumentException("Expected up to 16 incomingPaymentPatterns");
        List<IncomingPattern> result = new ArrayList<>();
        for (var item : array) {
            var p = item.getAsJsonObject();
            if (!p.has("regex") || !p.get("regex").isJsonPrimitive() || !p.getAsJsonPrimitive("regex").isString())
                throw new IllegalArgumentException("Pattern regex must be text");
            if (p.has("enabled") && (!p.get("enabled").isJsonPrimitive() || !p.getAsJsonPrimitive("enabled").isBoolean()))
                throw new IllegalArgumentException("Pattern enabled must be true or false");
            String regex = p.get("regex").getAsString(); validate(regex);
            result.add(new IncomingPattern(p.has("enabled") && p.get("enabled").getAsBoolean(), regex));
        }
        return result;
    }
}
