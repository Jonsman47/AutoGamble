package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import net.minecraft.client.Minecraft;
import java.util.List;

/** Uses the vanilla command API only; never constructs custom packets. */
public final class MinecraftPaymentDispatcher implements AutoPayEnvironment, PaymentSender {
    private final Minecraft client;
    private final PlayerSelectionManager selection;
    private final OutgoingPaymentTracker outgoing;
    private final java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config;
    private final DispatchGate gate = new DispatchGate();
    public MinecraftPaymentDispatcher(Minecraft client, PlayerSelectionManager selection, OutgoingPaymentTracker outgoing,
                                     java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config) {
        this.client = client; this.selection = selection; this.outgoing = outgoing; this.config = config;
    }
    public void beginTick() { gate.beginTick(); }
    @Override public boolean connected() {
        return client != null && client.player != null && client.level != null
                && client.getConnection() != null && client.getConnection().getConnection().isConnected();
    }
    @Override public boolean inputBlocked() { return client == null || client.gui.screen() != null || client.gui.overlay() != null; }
    @Override public List<Candidate> eligiblePlayers() {
        if (!connected()) return List.of();
        var entries = client.getConnection().getListedOnlinePlayers().stream()
                .filter(info -> info != null && info.getProfile() != null)
                .map(info -> new Candidate(info.getProfile().id(), info.getProfile().name())).toList();
        return selection.eligible(entries, client.player.getUUID(), client.player.getGameProfile().name());
    }
    @Override public boolean dispatch(Candidate target, String amount) {
        if (!connected() || inputBlocked() || !client.isSameThread() || !eligiblePlayers().contains(target)) return false;
        if (amount == null || !amount.matches("[0-9]+(?:\\.[0-9]{1,2})?")) return false;
        return sendPayment(target.username(), new java.math.BigDecimal(amount), OutgoingPaymentTracker.Source.ADVERTISING) == Result.SENT;
    }
    @Override public Result sendPayment(String username, java.math.BigDecimal amount, OutgoingPaymentTracker.Source source) {
        var c = config.get();
        if (!c.enabled || (source == OutgoingPaymentTracker.Source.ADVERTISING ? !c.autoPayEnabled : !c.gambleEnabled)
                || !connected() || inputBlocked() || !client.isSameThread()) return Result.RETRY_LATER;
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")
                || eligiblePlayers().stream().noneMatch(p -> p.username().equalsIgnoreCase(username))) return Result.RETRY_LATER;
        String formatted = AmountFormatter.format(amount);
        if (!gate.reserve()) return Result.RETRY_LATER;
        return PaymentExecution.execute(c.dryRunMode, username, new java.math.BigDecimal(formatted), source,
                System.nanoTime(), c.outgoingPaymentTrackingWindowMs, outgoing, command -> client.getConnection().sendCommand(command));
    }
}
