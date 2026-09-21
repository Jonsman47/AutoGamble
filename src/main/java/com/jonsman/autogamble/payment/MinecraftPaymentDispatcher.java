package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import org.slf4j.LoggerFactory;

import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.targeting.*;
import com.jonsman.autogamble.baltop.BaltopDatabase;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Uses the vanilla command API only; never constructs custom packets. */
public final class MinecraftPaymentDispatcher implements AutoPayEnvironment, PaymentSender {
    private final Minecraft client;
    private com.jonsman.autogamble.history.PaymentHistory history;
    private com.jonsman.autogamble.history.AnalyticsEngine analytics;
    private com.jonsman.autogamble.manager.KnownBalance balance;
    private TippingManager tipping;
    public void history(com.jonsman.autogamble.history.PaymentHistory history, com.jonsman.autogamble.manager.KnownBalance balance) { this.history = history; this.balance = balance; }
    public void analytics(com.jonsman.autogamble.history.AnalyticsEngine analytics) { this.analytics = analytics; }
    public void tipping(TippingManager tipping) { this.tipping = tipping; }
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
    private final BaltopDatabase baltop;
    private final java.util.Random targetingRandom = new java.util.Random();
    private TargetMethod activeMethod;
    private List<String> externalCandidates = List.of();
    private int externalIndex;
    private long verificationGeneration, verificationRequestedAt, nextVerificationAt;
    private boolean verificationPending;
    private Candidate verifiedTarget;
    private long verifiedAt;
    private boolean targetingCycleComplete;
    private final EnumSet<TargetMethod> exhaustedMethods = EnumSet.noneOf(TargetMethod.class);
    private static final long VERIFICATION_TIMEOUT = 3_000_000_000L, VERIFICATION_INTERVAL = 250_000_000L;
    private boolean sendingPayment;
    private final DispatchGate gate = new DispatchGate();
    public MinecraftPaymentDispatcher(Minecraft client, PlayerSelectionManager selection, OutgoingPaymentTracker outgoing,
                                     java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config) {
        this(client, selection, outgoing, config, null);
    }
    public MinecraftPaymentDispatcher(Minecraft client, PlayerSelectionManager selection, OutgoingPaymentTracker outgoing,
                                     java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config,
                                     BaltopDatabase baltop) {
        this.client = client; this.selection = selection; this.outgoing = outgoing; this.config = config;
        this.baltop = baltop;
    }
    public void beginTick() { gate.beginTick(); }
    @Override public boolean connected() {
        return client != null && client.player != null && client.level != null
                && client.getConnection() != null && client.getConnection().getConnection().isConnected();
    }
    @Override public boolean inputBlocked() { return client == null || client.gui.screen() != null || client.gui.overlay() != null; }
    public void resetSession() { discovery.reset(); failed.reset(); resetTargetingCycle(); }
    public void cancelDiscovery() { discovery.cancel(); resetTargetingCycle(); }
    @Override public void finishDiscovery() { discovery.cancel(); resetTargetingCycle(); }
    public String playerSource() { return activeMethod == null ? "WEIGHTED_TARGETING" : activeMethod.name(); }
    public String discoveryStatus() {
        return "Method: " + (activeMethod == null ? "waiting" : activeMethod.label()) + ", Prefix Length Range: " + config.get().minimumPrefixLength + "\u2013" + config.get().maximumPrefixLength
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
        if (targetingCycleComplete) return true;
        if (prefixMin != c.minimumPrefixLength || prefixMax != c.maximumPrefixLength) {
            discovery.cancel(); prefixMin = c.minimumPrefixLength; prefixMax = c.maximumPrefixLength;
            discovery = new PrefixPlayerDiscovery(prefixRandom, prefixMin, prefixMax);
        }
        if (activeMethod == null && !chooseMethod(c, now)) return targetingCycleComplete;
        if (activeMethod != TargetMethod.SMART_RANDOM || !externalCandidates.isEmpty()) return prepareExternal(now, c);
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
        if (discovery.ready() && discovery.candidates().isEmpty()) {
            exhaustedMethods.add(TargetMethod.SMART_RANDOM); discovery.cancel(); activeMethod = null;
            return !chooseMethod(c, now) && targetingCycleComplete;
        }
        if (discovery.ready()) {
            externalCandidates = discovery.candidates().stream().map(Candidate::username).distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            Collections.shuffle(externalCandidates,targetingRandom);
            return prepareExternal(now,c);
        }
        return false;
    }
    @Override public List<Candidate> eligiblePlayers() {
        if (!connected()) return List.of();
        return verifiedTarget == null || System.nanoTime()-verifiedAt > 2_000_000_000L ? List.of() : List.of(verifiedTarget);
    }
    @Override public boolean dispatch(Candidate target, String amount) {
        if (!connected() || inputBlocked() || !client.isSameThread() || !eligiblePlayers().contains(target)) return false;
        if (amount == null || !amount.matches("[0-9]+(?:\\.[0-9]{1,2})?")) return false;
        if (activeMethod == TargetMethod.SMART_RANDOM) discovery.selected(target.username());
        TargetMethod suppliedBy = activeMethod;
        boolean sent = sendPayment(target.username(), new java.math.BigDecimal(amount), OutgoingPaymentTracker.Source.ADVERTISING) == Result.SENT;
        if (sent && analytics != null && !config.get().dryRunMode)
            analytics.targetingPayment(suppliedBy, target.username(), new java.math.BigDecimal(amount), System.currentTimeMillis());
        return sent;
    }

    private boolean chooseMethod(AutoGambleConfig c, long now) {
        String local = client.player.getGameProfile().name();
        List<String> money = baltop == null ? List.of() : TargetingCandidates.money(baltop.snapshot(), c, local, selection, now);
        EnumSet<TargetMethod> available = EnumSet.noneOf(TargetMethod.class);
        if (c.smartRandomWeight > 0) available.add(TargetMethod.SMART_RANDOM);
        if (c.moneyLeaderboardWeight > 0 && !money.isEmpty()) available.add(TargetMethod.MONEY_LEADERBOARD);
        available.removeAll(exhaustedMethods);
        var selected = WeightedTargetSelector.select(c, available, targetingRandom);
        if (selected.isEmpty()) { targetingCycleComplete = true; return false; }
        activeMethod = selected.get();
        if (analytics != null) analytics.targetingAttempt(activeMethod);
        externalCandidates = switch (activeMethod) {
            case MONEY_LEADERBOARD -> new ArrayList<>(money);
            case ECONOMY_ACTIVE, EXPERIMENTAL -> List.of();
            case SMART_RANDOM -> List.of();
        };
        if (!externalCandidates.isEmpty()) Collections.shuffle(externalCandidates, targetingRandom);
        return true;
    }
    private boolean prepareExternal(long now, AutoGambleConfig c) {
        if (verifiedTarget != null) return true;
        if (verificationPending) {
            if (now - verificationRequestedAt < VERIFICATION_TIMEOUT) return false;
            verificationPending = false; verificationGeneration++; nextVerificationAt = now + VERIFICATION_INTERVAL;
            if (analytics != null) analytics.targetingOfflineRejected(activeMethod);
        }
        if (now < nextVerificationAt) return false;
        while (externalIndex < externalCandidates.size()) {
            String name = externalCandidates.get(externalIndex++);
            if (selection.recentlyPaid(name, now)) { if (analytics != null) analytics.targetingRepeatPrevented(activeMethod); continue; }
            if (failed.contains(name, now) || name.equalsIgnoreCase(client.player.getGameProfile().name())) continue;
            requestExactVerification(name, now, c); return false;
        }
        // The selected pool was entirely offline; reselect from the remaining enabled methods.
        exhaustedMethods.add(activeMethod); activeMethod = null; externalCandidates = List.of(); externalIndex = 0;
        return !chooseMethod(c, now) && targetingCycleComplete;
    }
    private void requestExactVerification(String name, long now, AutoGambleConfig c) {
        var connection = client.getConnection(); long token = ++verificationGeneration;
        verificationPending = true; verificationRequestedAt = now;
        String command = "/pay " + name;
        try {
            var context = connection.getCommands().parse(command.substring(1), connection.getSuggestionsProvider()).getContext().build(command);
            connection.getSuggestionsProvider().customSuggestion(context).whenComplete((result, error) -> client.execute(() -> {
                if (token != verificationGeneration || client.getConnection() != connection) return;
                verificationPending = false; nextVerificationAt = System.nanoTime() + VERIFICATION_INTERVAL;
                boolean exact = error == null && OnlineVerification.exactUsername(
                        result.getList().stream().map(s -> s.getText()).toList(), name, client.player.getGameProfile().name());
                if (exact && PrefixPlayerDiscovery.validName(name, client.player.getGameProfile().name(), c.excludeNumericOnlyNames)
                        && !failed.contains(name, System.nanoTime()) && !selection.recentlyPaid(name, System.nanoTime())) {
                    verifiedTarget = new Candidate(null, name); verifiedAt=System.nanoTime();
                    if (analytics != null) analytics.targetingValidCandidate(activeMethod);
                } else if (analytics != null) analytics.targetingOfflineRejected(activeMethod);
            }));
        } catch (RuntimeException ex) {
            verificationPending = false; nextVerificationAt = now + VERIFICATION_INTERVAL;
            if (analytics != null) analytics.targetingOfflineRejected(activeMethod);
        }
    }
    private void resetTargetingCycle() {
        verificationGeneration++; verificationPending = false; verifiedTarget = null; verifiedAt=0; activeMethod = null;
        externalCandidates = List.of(); externalIndex = 0; nextVerificationAt = 0;
        targetingCycleComplete = false; exhaustedMethods.clear();
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
        boolean sourceEnabled = switch (source) {
            case ADVERTISING -> c.enabled && c.autoPayEnabled;
            case BALANCE_RULE -> c.enabled && c.automaticBalancePaymentsEnabled;
            case GAMBLE_PAYOUT -> c.enabled && c.gambleEnabled;
            case LOSING_BET_TIP -> c.enabled && c.gambleEnabled && !c.tippingPermanentlyDisabled;
            case TIP_DISABLE_PURCHASE -> !c.dryRunMode;
        };
        if (!sourceEnabled || !connected() || inputBlocked() || !client.isSameThread()) return Result.RETRY_LATER;
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
                }, () -> {
                    var paidAmount = new java.math.BigDecimal(formatted);
                    if ((source == OutgoingPaymentTracker.Source.LOSING_BET_TIP
                            || source == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) && tipping != null) {
                        tipping.dispatched(username, paidAmount, source, System.currentTimeMillis());
                    } else {
                        if (history != null) history.record(com.jonsman.autogamble.history.PaymentHistory.Direction.PAID, username, paidAmount, source.name());
                        if (analytics != null) analytics.outgoing(username, paidAmount, source, System.currentTimeMillis());
                    }
                });
    }
}
