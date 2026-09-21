package com.jonsman.autogamble.payment;

import net.minecraft.client.multiplayer.ClientPacketListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The same Brigadier/custom-suggestion request used by the 1.2.4 prefix discovery. */
public final class PaySuggestionService {
    private PaySuggestionService() {}
    public static CompletableFuture<List<String>> request(ClientPacketListener connection, String prefix) {
        String command = "/pay " + prefix;
        var context = connection.getCommands().parse(command.substring(1), connection.getSuggestionsProvider())
                .getContext().build(command);
        return connection.getSuggestionsProvider().customSuggestion(context)
                .thenApply(result -> result.getList().stream().map(s -> s.getText()).toList());
    }
}
