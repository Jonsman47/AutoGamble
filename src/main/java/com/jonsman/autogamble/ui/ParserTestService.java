package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.payment.*;

/** Intentionally has no manager, queue or dispatcher references. */
public final class ParserTestService {
    private ParserTestService() {}
    public static String test(AutoGambleConfig config, String message, String localUsername) {
        return RegexPaymentParser.fromConfig(config).parse(message, localUsername)
                .map(p -> "Matched: " + p.sender() + " — $" + AmountFormatter.format(p.amount()))
                .orElse("No pattern matched");
    }
}
