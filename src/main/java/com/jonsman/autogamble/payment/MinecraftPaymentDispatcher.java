package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import org.slf4j.LoggerFactory;

import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import net.minecraft.client.Minecraft;
import java.util.List;

/** Uses the vanilla command API only; never constructs custom packets. */
public final class MinecraftPaymentDispatcher implements AutoPayEnvironment, PaymentSender {
    private final Minecraft client;
    private com.jonsman.autogamble.history.PaymentHistory history;
    private com.jonsman.autogamble.manager.KnownBalance balance;
    public void history(com.jonsman.autogamble.history.PaymentHistory history, com.jonsman.autogamble.manager.KnownBalance balance) { this.history = history; this.balance = balance; }
    public Result sendFollow(String username) {
        var c = config.get();
        if (!c.enabled || !c.autoFollowGoodCustomersEnabled || c.dryRunMode || !connected() || inputBlocked() || !client.isSameThread()
                || username == null || !username.matches("[A-Za-z0-9_]{2,16}") || username.equalsIgnoreCase(client.player.getGameProfile().name()) || !gate.reserve()) return Result.RETRY_LATER;
        try { client.getConnection().sendCommand("follow " + username); return Result.SENT; }
        catch (RuntimeException ex) { LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Ambiguous follow dispatch; suppressing retry", ex); return Result.UNCERTAIN; }
    }
    private final PlayerSelectionManager selection;
    private final OutgoingPaymentTracker outgoing;
    private final java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config;
    private final java.util.Random prefixRandom = new java.util.Random();
    private PrefixPlayerDiscovery discovery = new PrefixPlayerDiscovery(prefixRandom, 1, 3);
    private int prefixMin = 1, prefixMax = 3;
    private final FailedTargetBlacklist failed = new FailedTargetBlacklist();
    private boolean sendingPayment;
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
    public void resetSession() { discovery.reset(); failed.reset(); }
    public void cancelDiscovery() { discovery.cancel(); }
    @Override public void finishDiscovery() { discovery.cancel(); }
    public String playerSource() { return "RANDOM_PREFIX_SUGGESTIONS"; }
    public String discoveryStatus() {
        return "Prefix Length Range: " + config.get().minimumPrefixLength + "\u2013" + config.get().maximumPrefixLength
            + ", Last Prefix Length: " + discovery.lastLength() + ", Prefix Attempts This Cycle: " + discovery.attempts()
            + ", Last Auto Pay Prefix: " + discovery.prefix() + ", Last Candidate Count: " + discovery.count()
            + ", Last Selected Player: " + discovery.selected() + ", Numeric-Only Filter: "
            + (config.get().excludeNumericOnlyNames ? "ON" : "OFF") + ", Failed Target Blacklist: " + failed.size(System.nanoTime());
    }
    public void manualCommand() { if (!sendingPayment) failed.unrelatedCommand(); }
    public void receiveFailure(ReceivedMessage message) {
        failed.receive(message, System.nanoTime()).ifPresent(name ->
            org.slf4j.LoggerFactory.getLogger("autogamble").info("[AutoGamble] Temporarily excluding failed Auto Pay target {} for 10 minutes", name));
    }
    @Override public boolean prepare(long now) {
        if (!connected() || inputBlocked()) return false;
        var c = config.get();
        if (prefixMin != c.minimumPrefixLength || prefixMax != c.maximumPrefixLength) {
            discovery.cancel(); prefixMin = c.minimumPrefixLength; prefixMax = c.maximumPrefixLength;
            discovery = new PrefixPlayerDiscovery(prefixRandom, prefixMin, prefixMax);
        }
        var activeDiscovery = discovery;
        discovery.poll(now, selection, c.preferUnpaidPlayers).ifPresent(request -> {
            var connection = client.getConnection();
            String local = client.player.getGameProfile().name();
            try {
                var context = connection.getCommands().parse(request.command().substring(1), connection.getSuggestionsProvider())
                        .getContext().build(request.command());
                connection.getSuggestionsProvider().customSuggestion(context).whenComplete((result, error) ->
                    client.execute(() -> {
                        if (client.getConnection() != connection || discovery != activeDiscovery) return;
                        var current = config.get();
                        if (!current.enabled || !current.autoPayEnabled) { discovery.cancel(); return; }
                        discovery.complete(request.token(), error == null ? result.getList().stream().map(s -> s.getText()).toList() : List.of(),
                            local, current.excludeNumericOnlyNames, failed, selection, current.preferUnpaidPlayers, System.nanoTime());
                    }));
            } catch (RuntimeException ex) {
                discovery.complete(request.token(), List.of(), local, c.excludeNumericOnlyNames, failed, selection, c.preferUnpaidPlayers, now);
            }
        });
        return discovery.ready();
    }
    @Override public List<Candidate> eligiblePlayers() {
        if (!connected()) return List.of();
        return discovery.candidates().stream().filter(p -> PrefixPlayerDiscovery.validName(p.username(), client.player.getGameProfile().name(), config.get().excludeNumericOnlyNames)
                && !failed.contains(p.username(), System.nanoTime())).toList();
    }
    @Override public boolean dispatch(Candidate target, String amount) {
        if (!connected() || inputBlocked() || !client.isSameThread() || !eligiblePlayers().contains(target)) return false;
        if (amount == null || !amount.matches("[0-9]+(?:\\.[0-9]{1,2})?")) return false;
        discovery.selected(target.username());
        return sendPayment(target.username(), new java.math.BigDecimal(amount), OutgoingPaymentTracker.Source.ADVERTISING) == Result.SENT;
    }
   public boolean sendWarning(String username, String message) {
      AutoGambleConfig c = this.config.get();
      if (!c.enabled || !c.gambleEnabled || !c.spamPaymentWarningEnabled || !this.connected() || this.inputBlocked() || !this.client.isSameThread()) {
         return false;
      } else if (SpamWarningCommand.validText(message)
         && username != null
         && username.matches("[A-Za-z0-9_]{3,16}")
         && !username.equalsIgnoreCase(this.client.player.getGameProfile().name())
         && this.gate.reserve()) {
         try {
            SpamWarningCommand.execute(c.dryRunMode, username, message, cmd -> this.client.getConnection().sendCommand(cmd));
         } catch (RuntimeException var5) {
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Warning dispatch failed; not retrying ambiguous send", var5);
         }

         return true;
      } else {
         return false;
      }
   }


    @Override public Result sendPayment(String username, java.math.BigDecimal amount, OutgoingPaymentTracker.Source source) {
        var c = config.get();
        if (!c.enabled || (source == OutgoingPaymentTracker.Source.ADVERTISING ? !c.autoPayEnabled : source == OutgoingPaymentTracker.Source.BALANCE_RULE ? !c.automaticBalancePaymentsEnabled : !c.gambleEnabled)
                || !connected() || inputBlocked() || !client.isSameThread()) return Result.RETRY_LATER;
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}")
                || username.equalsIgnoreCase(client.player.getGameProfile().name())
                || (source == OutgoingPaymentTracker.Source.ADVERTISING
                    && eligiblePlayers().stream().noneMatch(p -> p.username().equalsIgnoreCase(username)))) return Result.RETRY_LATER;
        if (source == OutgoingPaymentTracker.Source.BALANCE_RULE && (balance == null || balance.current(System.nanoTime()) == null
                || balance.current(System.nanoTime()).compareTo(amount) < 0)) return Result.RETRY_LATER;
        String formatted = AmountFormatter.format(amount);
        if (!gate.reserve()) return Result.RETRY_LATER;
        return PaymentExecution.execute(c.dryRunMode, username, new java.math.BigDecimal(formatted), source,
                System.nanoTime(), c.outgoingPaymentTrackingWindowMs, outgoing, command -> {
                    failed.dispatched(username, source, System.nanoTime());
                    sendingPayment = true;
                    try { client.getConnection().sendCommand(command); }
                    finally { sendingPayment = false; if (balance != null) balance.invalidate(); }
                }, () -> { if (history != null) history.record(com.jonsman.autogamble.history.PaymentHistory.Direction.PAID, username, new java.math.BigDecimal(formatted), source.name()); });
    }
}
