package com.jonsman.autogamble;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.manager.*;
import com.jonsman.autogamble.payment.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import com.jonsman.autogamble.ui.*;

public final class AutoGambleClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("autogamble");
    private final AutoPayManager autoPay = new AutoPayManager();
    private final PaymentQueue payments = new PaymentQueue();
    private final ReceiptDeduplicator receipts = new ReceiptDeduplicator();
    private final OutgoingPaymentTracker outgoing = new OutgoingPaymentTracker();
    private final GambleManager gamble = new GambleManager(PaymentParser.inactive(), payments, receipts, outgoing, new java.util.Random(), payment -> {});
    private final WinnerPayoutProcessor payouts = new WinnerPayoutProcessor(payments, new java.util.Random());
    private RegexPaymentParser parser;
    private final PlayerSelectionManager selection = new PlayerSelectionManager();
    private com.jonsman.autogamble.history.PaymentHistory history;
    private com.jonsman.autogamble.history.AnalyticsEngine analytics;
    private final PaymentSoundAlerts soundAlerts = new PaymentSoundAlerts();
    private final HelpCommandRouter help = new HelpCommandRouter();
    private GoodCustomerFollow follow;
    private BalanceRuleEngine balanceRules;
    private final KnownBalance knownBalance = new KnownBalance();
    private ConfigManager configs;
    private PayerHistory payerHistory;
    private AutoGambleConfig activeConfig;
    private KeyMapping toggle, settings;
    private Object lastConnection, lastWorld;
    private long configRevision = -1;
    private MinecraftPaymentDispatcher dispatcher;
    private boolean openSettingsRequested;
    private boolean diagnostics;
    private int debugRemaining;

    @Override public void onInitializeClient() {
        configs = new ConfigManager(FabricLoader.getInstance().getConfigDir().resolve("autogamble.json"), LOGGER);
        configs.load();
        payerHistory = new PayerHistory(FabricLoader.getInstance().getConfigDir().resolve("autogamble-payers.json"));
        gamble.history(payerHistory);
        activeConfig = configs.snapshot();
        var dataRoot = FabricLoader.getInstance().getConfigDir().resolve("autogamble");
        history = new com.jonsman.autogamble.history.PaymentHistory(dataRoot, activeConfig);
        analytics = new com.jonsman.autogamble.history.AnalyticsEngine(dataRoot.resolve("data/analytics.json"), LOGGER);
        follow = new GoodCustomerFollow(history);
        balanceRules = new BalanceRuleEngine(dataRoot.resolve("data/balance_rule_state.json"));
        gamble.receivedObserver(payment -> {
            history.record(com.jonsman.autogamble.history.PaymentHistory.Direction.RECEIVED, payment.sender(), payment.amount(), "SERVER_PAYMENT");
            analytics.incoming(payment.sender(), payment.amount(), System.currentTimeMillis(), activeConfig);
            soundAlerts.select(payment.amount(), System.currentTimeMillis(), activeConfig).ifPresent(this::playPaymentSound);
        });
        gamble.acceptedObserver((payment, first, won, payout, now, config) ->
                analytics.acceptedGamble(payment.sender(), payment.amount(), first, won, payout, System.currentTimeMillis(), config));
        refreshConfig();
        dispatcher = new MinecraftPaymentDispatcher(Minecraft.getInstance(), selection, outgoing, () -> activeConfig);
        dispatcher.history(history, knownBalance);
        dispatcher.analytics(analytics);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autogamble", "main"));
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autogamble.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        settings = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autogamble.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSession());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { resetSession(); analytics.close(); history.close(); balanceRules.close(); });
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            var client = Minecraft.getInstance();
            if (help.intercept(command, text -> { if (client.player != null) client.player.sendSystemMessage(Component.literal(text)); })) return false;
            knownBalance.invalidate(); dispatcher.manualCommand();
            return !SettingsCommandRouter.intercept(command, () -> openSettingsRequested = true);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> receive(message,
                overlay ? ReceivedMessage.Channel.OVERLAY : ReceivedMessage.Channel.SYSTEM));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, timestamp) -> receive(message,
                signed == null && sender == null ? ReceivedMessage.Channel.SERVER_CHAT : ReceivedMessage.Channel.PLAYER_CHAT));
        ClientCommandRegistrationCallback.EVENT.register((commands, registryAccess) -> commands.register(
                literal("autogamble").then(literal("settings").executes(context -> { openSettingsRequested = true; return 1; }))
                .then(literal("debug").then(literal("on").executes(context -> { diagnostics = true; debugRemaining = 100; context.getSource().sendFeedback(Component.literal("[AutoGamble] Receive diagnostics ON (next 100 messages; logs/latest.log)")); return 1; }))
                        .then(literal("off").executes(context -> { diagnostics = false; return 1; })))
                .then(literal("reports")
                    .then(literal("refresh").executes(context -> { history.refresh(); context.getSource().sendFeedback(Component.literal("[AutoGamble] Enabled report refresh scheduled.")); return 1; }))
                    .then(literal("status").executes(context -> { context.getSource().sendFeedback(Component.literal(reportStatus())); return 1; })))
                .then(literal("status").executes(context -> {
                    var c = configs.snapshot();
                    context.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "[AutoGamble] enabled=%s, autoPay=%s, eligible=%d, history=%d, delay=%.3f–%.3fs, next≈%.2fs (0 if inactive)",
                            c.enabled, c.autoPayEnabled, dispatcher.eligiblePlayers().size(), selection.paidUsernames().size(),
                            c.minimumAutoPayDelaySeconds, c.maximumAutoPayDelaySeconds,
                            autoPay.remainingNanos(System.nanoTime()) / 1_000_000_000.0)));
                    context.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "[AutoGamble] gamble=%s, chance=%.2f%%, multiplier=%s, bets=%s–%s, payouts=%d, receipts=%d, patterns=%d, dryRun=%s",
                            c.gambleEnabled, c.winChance * 100, c.payoutMultiplier, c.minimumBet, c.maximumBet,
                            payments.size(), receipts.size(), parser.enabledCount(), c.dryRunMode)));
                    context.getSource().sendFeedback(Component.literal("[AutoGamble] Player source=" + dispatcher.playerSource()
                            + ", candidates=" + dispatcher.eligiblePlayers().size() + ", connected=" + dispatcher.connected()
                            + ", screenBlocked=" + dispatcher.inputBlocked() + ", autoPayState=" + autoPay.state()
                            + ", Donut parser=" + c.donutSmpIncomingEnabled));
                    context.getSource().sendFeedback(Component.literal("[AutoGamble] Last parsed: " + gamble.lastIncoming()));
                    context.getSource().sendFeedback(Component.literal("[AutoGamble] " + dispatcher.discoveryStatus()));
                    context.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "[AutoGamble] Base Win Chance: %.1f%%, First-Time Bonus: %s, First-Time Win Bonus: +%.1f%%, Known Payers: %d",
                            c.winChance * 100, c.firstTimePayerBonusEnabled ? "ON" : "OFF", c.firstTimeWinBonus * 100, payerHistory.size())));
                    context.getSource().sendFeedback(Component.literal("[AutoGamble] Spam Warning: " + (c.spamPaymentWarningEnabled ? "ON" : "OFF")
                            + ", Spam Threshold: " + c.spamPaymentThreshold + ", Spam Window: " + c.spamPaymentWindowSeconds
                            + "s, Warning Cooldown: " + c.spamWarningCooldownSeconds + "s, Tracked Recent Payers: " + gamble.spam.size()));
                    context.getSource().sendFeedback(Component.literal(automationStatus()));
                    var stats = analytics.snapshot(System.currentTimeMillis());
                    context.getSource().sendFeedback(Component.literal("[AutoGamble] Payment Sounds: " + (c.paymentSoundAlertsEnabled ? "ON" : "OFF")
                            + ", Session Received: " + stats.paymentsReceived() + ", Session Net: " + MoneyValues.display(stats.trackedNetProfit())
                            + ", Lifetime Customers: " + stats.customers().size()));
                    return 1;
                }))));
        LOGGER.info("[AutoGamble] 1.2.1 initialized; {} incoming patterns enabled; dry run={}", parser.enabledCount(), activeConfig.dryRunMode);
    }
    private void receive(Component message, ReceivedMessage.Channel channel) {
        var client = Minecraft.getInstance();
        if (!client.isSameThread()) { client.execute(() -> receive(message, channel)); return; }
        if (diagnostics && debugRemaining-- > 0)
            LOGGER.info("[AutoGamble] Receive {}: {}", channel, ReceivedMessage.normalize(message.getString()).substring(0, Math.min(1024, ReceivedMessage.normalize(message.getString()).length())));
        if (!dispatcher.connected()) return;
        syncSession(client);
        refreshConfig();
        dispatcher.receiveFailure(new ReceivedMessage(message.getString(), channel));
        var outcome = gamble.receive(new ReceivedMessage(message.getString(), channel), client.player.getGameProfile().name(), System.nanoTime(), activeConfig);
        if (diagnostics && outcome != GambleManager.Outcome.IGNORED)
            client.player.sendSystemMessage(Component.literal("[AutoGamble] " + gamble.lastIncoming()));
    }
    private void refreshConfig() {
        if (configRevision != configs.revision()) {
            var previous = activeConfig;
            activeConfig = configs.snapshot();
            configRevision = configs.revision();
            if (history != null) history.configure(activeConfig);
            parser = RegexPaymentParser.fromConfig(activeConfig);
            gamble.parser(parser);
            if (!activeConfig.enabled || !activeConfig.gambleEnabled || !activeConfig.spamPaymentWarningEnabled
                    || previous != null && previous.dryRunMode != activeConfig.dryRunMode) gamble.spam.reset();
            var change = RuntimeSettingsChange.between(previous, activeConfig);
            if (change.clearPayouts()) payouts.cancel();
            if (change.resetSimulation()) { selection.reset(); receipts.reset(); }
            if (change.resetAutoPay()) { autoPay.reset(); if (dispatcher != null) dispatcher.cancelDiscovery(); }
        }
    }
    private void syncSession(Minecraft client) {
        Object connection = client.getConnection();
        if (connection != lastConnection || client.level != lastWorld) {
            resetSession();
            lastConnection = connection;
            lastWorld = client.level;
        }
    }
    private void tick(Minecraft client) {
        refreshConfig();
        syncSession(client);
        Object connection = client.getConnection();
        boolean connected = client.player != null && connection != null && client.level != null;
        while (toggle.consumeClick()) {
            configs.update(c -> c.enabled = !c.enabled);
            refreshConfig();
            cancelWork();
            boolean enabled = activeConfig.enabled;
            LOGGER.info("[AutoGamble] {}", enabled ? "Enabled" : "Disabled");
            if (connected) client.player.sendSystemMessage(Component.literal("[AutoGamble] " + (enabled ? "Enabled" : "Disabled")));
        }
        while (settings.consumeClick()) {
            openSettingsRequested = true;
        }
        if (openSettingsRequested) { openSettingsRequested = false; openSettings(client); }
        if (!connected) { resetSession(); return; }
        var config = activeConfig;
        long now = System.nanoTime();
        gamble.tick(now, config);
        if (!config.enabled) { cancelWork(); return; }
        dispatcher.beginTick();
        payouts.tick(now, config, dispatcher);
        gamble.spam.tick(now, config, dispatcher::sendWarning);
        follow.tick(config, now, dispatcher::sendFollow);
        balanceRules.tick(config, knownBalance, now, System.currentTimeMillis(),
                (player, amount) -> dispatcher.sendPayment(player, amount, OutgoingPaymentTracker.Source.BALANCE_RULE));
        autoPay.tick(now, config, dispatcher, selection);
    }
    private void cancelWork() { if (dispatcher != null) dispatcher.cancelDiscovery(); autoPay.reset(); payouts.cancel(); selection.reset(); }
    private void openSettings(Minecraft client) {
        if (client.gui.screen() instanceof AutoGambleSettingsScreen) return;
        var context = new SettingsContext(configs, this::refreshConfig, selection::reset,
                () -> "Candidates: " + dispatcher.eligiblePlayers().size() + "  •  Paid: " + selection.paidUsernames().size()
                        + "  •  Payouts: " + payments.size() + "  •  Patterns: " + parser.enabledCount(),
                () -> client.player == null ? "" : client.player.getGameProfile().name(), payerHistory::reset, follow::clearHistory,
                this::automationStatus, () -> analytics.snapshot(System.currentTimeMillis()));
        client.gui.setScreen(new AutoGambleSettingsScreen(null, context));
    }
    private String automationStatus() {
        var c = activeConfig; var data = history.snapshot();
        return "[AutoGamble] Auto Follow Customers: " + (c.autoFollowGoodCustomersEnabled ? "ON" : "OFF")
            + ", Follow Threshold: " + MoneyValues.display(c.autoFollowThreshold) + ", Followed Players: " + data.followed().size()
            + "\nAutomatic Balance Payments: " + (c.automaticBalancePaymentsEnabled ? "ON" : "OFF")
            + ", Configured Balance Rules: " + c.balancePaymentRules.size() + ", Enabled Balance Rules: " + c.balancePaymentRules.stream().filter(r -> r.enabled).count()
            + ", Current Known Balance: " + MoneyValues.display(knownBalance.current(System.nanoTime()))
            + "\nBalance source: " + knownBalance.source() + (data.error().isEmpty() ? "" : "\nData warning: " + data.error());
    }
    private String reportStatus() {
        var c = activeConfig; var data = history.snapshot();
        return "[AutoGamble] Reports: " + history.reports().toAbsolutePath()
            + "\nPayments-To-Players: " + c.generatePaymentsToPlayersReport + ", Top-Customers: " + c.generateTopCustomersReport
            + ", Recent-Payments: " + c.generateRecentPaymentsReport + "\nStored transactions: " + data.stored()
            + ", Received players: " + data.receivedPlayers() + ", Paid players: " + data.paidPlayers()
            + (data.error().isEmpty() ? "" : "\nData warning: " + data.error());
    }
    private void resetSession() {
        knownBalance.invalidate(); soundAlerts.resetSession();
        if (analytics != null) analytics.resetSession(System.currentTimeMillis());
        if (follow != null) follow.resetSession(); if (dispatcher != null) dispatcher.resetSession();
        cancelWork(); gamble.reset(); lastConnection = null; lastWorld = null;
    }
    private void playPaymentSound(PaymentAlertTier tier) {
        var client = Minecraft.getInstance();
        if (client.player == null) return;
        Identifier id = Identifier.tryParse(tier.sound);
        if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
            LOGGER.warn("[AutoGamble] Unknown payment alert sound id: {}", tier.sound); return;
        }
        client.player.playSound(BuiltInRegistries.SOUND_EVENT.getValue(id), tier.volume, tier.pitch);
    }
}
