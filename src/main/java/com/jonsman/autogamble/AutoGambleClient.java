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
    private ConfigManager configs;
    private AutoGambleConfig activeConfig;
    private KeyMapping toggle, settings;
    private Object lastConnection, lastWorld;
    private long configRevision = -1;
    private MinecraftPaymentDispatcher dispatcher;
    private boolean openSettingsRequested;

    @Override public void onInitializeClient() {
        configs = new ConfigManager(FabricLoader.getInstance().getConfigDir().resolve("autogamble.json"), LOGGER);
        configs.load();
        activeConfig = configs.snapshot();
        refreshConfig();
        dispatcher = new MinecraftPaymentDispatcher(Minecraft.getInstance(), selection, outgoing, () -> activeConfig);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autogamble", "main"));
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autogamble.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        settings = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autogamble.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSession());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> resetSession());
        ClientSendMessageEvents.ALLOW_COMMAND.register(command ->
                !SettingsCommandRouter.intercept(command, () -> openSettingsRequested = true));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            var client = Minecraft.getInstance();
            // Fabric delivers this on the client thread. Fail closed if another caller violates that contract.
            if (!client.isSameThread() || !dispatcher.connected()) return;
            syncSession(client);
            refreshConfig();
            gamble.receive(new ReceivedMessage(message.getString(), overlay ? ReceivedMessage.Channel.OVERLAY : ReceivedMessage.Channel.SYSTEM),
                    client.player.getGameProfile().name(), System.nanoTime(), activeConfig);
        });
        ClientCommandRegistrationCallback.EVENT.register((commands, registryAccess) -> commands.register(
                literal("autogamble").then(literal("settings").executes(context -> { openSettingsRequested = true; return 1; }))
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
                    return 1;
                }))));
        LOGGER.info("[AutoGamble] 1.0.0 initialized; {} incoming patterns enabled; dry run={}", parser.enabledCount(), activeConfig.dryRunMode);
    }
    private void refreshConfig() {
        if (configRevision != configs.revision()) {
            var previous = activeConfig;
            activeConfig = configs.snapshot();
            configRevision = configs.revision();
            parser = new RegexPaymentParser(activeConfig.incomingPaymentPatterns);
            gamble.parser(parser);
            var change = RuntimeSettingsChange.between(previous, activeConfig);
            if (change.clearPayouts()) payouts.cancel();
            if (change.resetSimulation()) { selection.reset(); receipts.reset(); }
            if (change.resetAutoPay()) autoPay.reset();
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
        autoPay.tick(now, config, dispatcher, selection);
    }
    private void cancelWork() { autoPay.reset(); payouts.cancel(); selection.reset(); }
    private void openSettings(Minecraft client) {
        if (client.gui.screen() instanceof AutoGambleSettingsScreen) return;
        var context = new SettingsContext(configs, this::refreshConfig, selection::reset,
                () -> "TAB: " + dispatcher.eligiblePlayers().size() + "  •  Paid: " + selection.paidUsernames().size()
                        + "  •  Payouts: " + payments.size() + "  •  Patterns: " + parser.enabledCount(),
                () -> client.player == null ? "" : client.player.getGameProfile().name());
        client.gui.setScreen(new AutoGambleSettingsScreen(null, context));
    }
    private void resetSession() { cancelWork(); gamble.reset(); lastConnection = null; lastWorld = null; }
}
