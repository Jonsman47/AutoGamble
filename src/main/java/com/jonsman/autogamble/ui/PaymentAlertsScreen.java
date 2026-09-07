package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Editor for the five ordered payment-alert tiers. Changes remain in the shared draft. */
public final class PaymentAlertsScreen extends Screen {
    private final Screen parent; private final SettingsDraft draft;
    private int tier, left, panel, row; private String error = "";
    public PaymentAlertsScreen(Screen parent, SettingsDraft draft) {
        super(Component.literal("Payment Sound Alerts")); this.parent = parent; this.draft = draft;
    }
    @Override protected void init() {
        panel = Math.min(420, width - 24); left = (width - panel) / 2; row = 40;
        button("Payment Sound Alerts: " + on(draft.working.paymentSoundAlertsEnabled), () -> {
            draft.working.paymentSoundAlertsEnabled = !draft.working.paymentSoundAlertsEnabled; rebuildWidgets();
        });
        integer("Minimum Alert Spacing (ms)", Integer.toString(draft.working.minimumAlertSpacingMs), 0, 5000,
                v -> draft.working.minimumAlertSpacingMs = v);
        addRenderableWidget(Button.builder(Component.literal("Previous Tier"), b -> { tier = Math.floorMod(tier - 1, 5); rebuildWidgets(); })
                .bounds(left, row, panel / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next Tier"), b -> { tier = (tier + 1) % 5; rebuildWidgets(); })
                .bounds(left + panel / 2 + 3, row, panel / 2 - 3, 20).build()); row += 21;
        PaymentAlertTier value = draft.working.paymentAlertTiers.get(tier);
        button("Tier " + (tier + 1) + " Enabled: " + on(value.enabled), () -> { value.enabled = !value.enabled; rebuildWidgets(); });
        text("Threshold", MoneyValues.plain(value.threshold), s -> {
            try { value.threshold = MoneyValues.parse(s); error = ""; } catch (RuntimeException ex) { error = ex.getMessage(); }
        });
        text("Sound ID", value.sound, s -> {
            if (s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) { value.sound = s; error = ""; }
            else error = "Use a namespaced sound such as minecraft:entity.player.levelup";
        });
        decimal("Volume", Float.toString(value.volume), .0f, 4.0f, v -> value.volume = v);
        decimal("Pitch", Float.toString(value.pitch), .5f, 2.0f, v -> value.pitch = v);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, height - 28, panel, 20).build());
    }
    private void button(String label, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(left, row, panel, 20).build()); row += 21;
    }
    private void text(String label, String initial, java.util.function.Consumer<String> action) {
        int w = Math.min(230, panel / 2); EditBox box = addRenderableWidget(new EditBox(font, left + panel - w, row, w, 20, Component.literal(label)));
        box.setMaxLength(96); box.setValue(initial); box.setResponder(action); row += 21;
    }
    private void integer(String label, String initial, int min, int max, java.util.function.IntConsumer action) {
        text(label, initial, s -> { try { int v = Integer.parseInt(s); if (v < min || v > max) throw new NumberFormatException(); action.accept(v); error = ""; }
            catch (NumberFormatException ex) { error = label + " must be " + min + "–" + max; } });
    }
    private void decimal(String label, String initial, float min, float max, java.util.function.Consumer<Float> action) {
        text(label, initial, s -> { try { float v = Float.parseFloat(s); if (!Float.isFinite(v) || v < min || v > max) throw new NumberFormatException(); action.accept(v); error = ""; }
            catch (NumberFormatException ex) { error = label + " must be " + min + "–" + max; } });
    }
    private static String on(boolean value) { return value ? "ON" : "OFF"; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta); g.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        g.centeredText(font, "The highest matching enabled tier plays once per payment.", width / 2, 31, 0xFFAAAAAA);
        PaymentAlertTier value = draft.working.paymentAlertTiers.get(tier);
        g.text(font, "Minimum Alert Spacing (ms)", left, 67, 0xFFE0E0E0);
        g.text(font, "Threshold", left, 130, 0xFFE0E0E0); g.text(font, "Sound ID", left, 151, 0xFFE0E0E0);
        g.text(font, "Volume", left, 172, 0xFFE0E0E0); g.text(font, "Pitch", left, 193, 0xFFE0E0E0);
        if (!error.isEmpty()) g.centeredText(font, font.plainSubstrByWidth(error, panel), width / 2, height - 43, 0xFFFF8888);
    }
}
