package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.SettingsDraft;
import com.jonsman.autogamble.payment.TippingManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Public tipping disclosure and the explicit two-step permanent-disable purchase flow. */
public final class TippingScreens {
    private TippingScreens() {}

    public static boolean minimumLayoutFits(int width, int height) {
        return width >= 320 && height >= 240 && height - 28 > 171;
    }

    public static Screen disclosure(SettingsContext context) {
        return new TippingDisclosureScreen(context);
    }

    public static Screen disableInfo(Screen parent, SettingsContext context, SettingsDraft draft) {
        return new TippingDisableInfoScreen(parent, context, draft);
    }

    public static Screen confirmation(Screen parent, SettingsContext context, SettingsDraft draft) {
        return new TippingDisableConfirmationScreen(parent, context, draft);
    }
}

final class TippingDisclosureScreen extends Screen {
    private final SettingsContext context;
    private int left, panel;

    TippingDisclosureScreen(SettingsContext context) {
        super(Component.literal("AutoGamble Tipping"));
        this.context = context;
    }

    @Override protected void init() {
        panel = Math.min(420, width - 24);
        left = (width - panel) / 2;
        addRenderableWidget(Button.builder(Component.literal("I Understand"), ignored -> {
            context.configs().update(config -> config.tippingDisclosureAcknowledged = true);
            context.changed().run();
            minecraft.gui.setScreen(null);
        }).bounds(left, height - 28, panel, 20).build());
    }

    @Override public void onClose() {
        // Acknowledgement is required before automatic gambling can start.
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, Component.literal(
                "The public version of AutoGamble sends 5% of losing gamble bets to the mod owner, Mac10HeatInciden.\n\n"
                + "Winning bets are not included.\n\n"
                + "You can permanently disable tipping from Settings -> Tipping with a one-time 500M payment to the mod owner."),
                left, 43, panel, 0xFFE0E0E0);
    }
}

final class TippingDisableInfoScreen extends Screen {
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private int left, panel;

    TippingDisableInfoScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Disable Tipping"));
        this.parent = parent;
        this.context = context;
        this.draft = draft;
    }

    @Override protected void init() {
        panel = Math.min(420, width - 24);
        left = (width - panel) / 2;
        addRenderableWidget(Button.builder(Component.literal("Pay 500M & Disable Tipping"), ignored ->
                minecraft.gui.setScreen(new TippingDisableConfirmationScreen(this, context, draft)))
                .bounds(left, 151, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(left, height - 28, panel, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, Component.literal(
                "AutoGamble normally sends 5% of losing gamble bets to the mod owner. Winning bets do not generate a tip.\n\n"
                + "You can permanently disable tipping on this installation by making a one-time 500M payment to Mac10HeatInciden."),
                left, 43, panel, 0xFFE0E0E0);
    }
}

final class TippingDisableConfirmationScreen extends Screen {
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private int left, panel;
    private String error = "";

    TippingDisableConfirmationScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Confirm Permanent Tipping Disable"));
        this.parent = parent;
        this.context = context;
        this.draft = draft;
    }

    @Override protected void init() {
        panel = Math.min(440, width - 24);
        left = (width - panel) / 2;
        int half = panel / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(left, height - 28, half - 3, 20).build());
        Button confirm = addRenderableWidget(Button.builder(Component.literal("Confirm 500M Payment"), ignored -> confirm())
                .bounds(left + half + 3, height - 28, panel - half - 3, 20).build());
        confirm.active = !draft.working.dryRunMode;
        if (draft.working.dryRunMode)
            error = "Disable Dry Run before making the 500M permanent-disable payment.";
    }

    private void confirm() {
        TippingManager.QueueResult result = context.disableTipping().get();
        if (result == TippingManager.QueueResult.QUEUED) {
            minecraft.gui.setScreen(null);
        } else {
            error = result.message();
        }
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, Component.literal(
                "This will send 500M to Mac10HeatInciden, the owner of AutoGamble.\n\n"
                + "Tipping will only be permanently disabled after the server confirms that the 500M payment succeeded."),
                left, 50, panel, 0xFFE0E0E0);
        if (!error.isEmpty())
            graphics.centeredText(font, font.plainSubstrByWidth(error, panel), width / 2, 151, 0xFFFF8888);
    }
}
