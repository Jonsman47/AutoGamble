package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.config.SettingsDraft.Field;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Standard Minecraft widgets, paged to fit the minimum supported GUI size. */
public final class AutoGambleSettingsScreen extends Screen {
    private static final String[] PAGES = {"General", "Auto Pay", "Gamble", "Timing", "Advanced", "Keys"};
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private final Map<Field, EditBox> fields = new EnumMap<>(Field.class);
    private int page, left, panel, row, ticks;
    private Button save;
    private String error = "", status = "";
    public AutoGambleSettingsScreen(Screen parent, SettingsContext context) {
        super(Component.literal("AutoGamble Settings")); this.parent = parent; this.context = context;
        draft = new SettingsDraft(context.configs().snapshot());
    }
    @Override protected void init() {
        fields.clear(); panel = Math.min(420, width - 24); left = (width - panel) / 2; row = 76;
        for (int i = 0; i < PAGES.length; i++) {
            final int target = i; int tabWidth = panel / PAGES.length;
            Button tab = button(PAGES[i], left + i * tabWidth, 46, tabWidth - 2, () -> { page = target; rebuildWidgets(); });
            tab.active = i != page;
        }
        switch (page) {
            case 0 -> {
                toggle("Dry Run Mode", () -> draft.working.dryRunMode, v -> draft.working.dryRunMode = v,
                        "Simulates payments. Disabling this allows real /pay commands.");
                toggle("AutoGamble Enabled", () -> draft.working.enabled, v -> draft.working.enabled = v, "Master switch for both payment systems.");
                toggle("Auto Pay Enabled", () -> draft.working.autoPayEnabled, v -> draft.working.autoPayEnabled = v, "Pays random visible TAB players while enabled.");
                toggle("Gambling Enabled", () -> draft.working.gambleEnabled, v -> draft.working.gambleEnabled = v, "Processes incoming bets only when a verified pattern matches.");
            }
            case 1 -> {
                number(Field.AMOUNT, "Amount per advertising payment. At least $0.01.");
                number(Field.PAY_MIN, "Lower bound for a fresh random delay after each attempt.");
                number(Field.PAY_MAX, "Must be at least the minimum delay.");
                toggle("Prefer Unpaid Players", () -> draft.working.preferUnpaidPlayers, v -> draft.working.preferUnpaidPlayers = v,
                        "Tries to pay each visible TAB player once before repeating.");
                button("Reset Paid Player History…", left, row, panel, () -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                    if (yes) context.resetPaid().run(); minecraft.gui.setScreen(this);
                }, Component.literal("Reset paid player history?"), Component.literal("This clears the current cycle immediately."))));
            }
            case 2 -> {
                var slider = addRenderableWidget(new WinChanceSlider(left, row, panel, draft.working.winChance, v -> { draft.working.winChance = v; validateDraft(); }));
                slider.setTooltip(Tooltip.create(Component.literal("Chance that an accepted bet wins. Adjusts in 0.1% steps."))); row += 24;
                number(Field.MULTIPLIER, "Amount returned to a winner relative to their bet.");
                number(Field.BET_MIN, "Smaller bets are ignored without an automatic refund.");
                number(Field.BET_MAX, "Larger bets are ignored without an automatic refund.");
            }
            case 3 -> {
                number(Field.WIN_MIN, "Minimum wait before each queued winner payout.");
                number(Field.WIN_MAX, "A fresh random wait is chosen for each winner.");
            }
            case 4 -> {
                number(Field.DEDUP, "Prevents the same server payment from being processed more than once.");
                number(Field.TRACK, "Rejects incoming candidates matching recently sent payments.");
                button("Parser Setup & Test…", left, row + 8, panel, () -> minecraft.gui.setScreen(new AdvancedParserScreen(this, context, draft)));
            }
            case 5 -> button("Open Minecraft Key Binds…", left, row + 42, panel,
                    () -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)));
            default -> throw new IllegalStateException();
        }
        save = button("Save & Done", left, height - 28, panel / 2 - 3, this::save);
        button("Cancel", left + panel / 2 + 3, height - 28, panel / 2 - 3, this::onClose);
        status = context.status().get(); validateDraft();
    }
    private Button button(String title, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(title), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void toggle(String label, BooleanSupplier get, Consumer<Boolean> set, String tip) {
        button(label + ": " + (get.getAsBoolean() ? "ON" : "OFF"), left, row, panel, () -> {
            set.accept(!get.getAsBoolean()); rebuildWidgets();
        }).setTooltip(Tooltip.create(Component.literal(tip))); row += 24;
    }
    private void number(Field field, String tooltip) {
        int fieldWidth = Math.min(130, panel / 3);
        EditBox box = addRenderableWidget(new EditBox(font, left + panel - fieldWidth, row, fieldWidth, 20, Component.literal(field.label)));
        box.setMaxLength(32); box.setValue(draft.text(field));
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(value -> { draft.text(field, value); validateDraft(); });
        fields.put(field, box); row += 24;
    }
    private void validateDraft() {
        var errors = draft.validate(); error = errors.isEmpty() ? "" : errors.getFirst();
        if (context.configs().isReadOnly()) error = "Newer config version: settings are read-only.";
        if (save != null) save.active = error.isEmpty();
        fields.values().forEach(f -> f.setTextColor(error.isEmpty() ? 0xFFE0E0E0 : 0xFFFF8888));
    }
    private void save() {
        validateDraft(); if (!error.isEmpty()) return;
        if (RuntimeSettingsChange.requiresRealPaymentConfirmation(context.configs().snapshot(), draft.working)) {
            minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) commit(); else minecraft.gui.setScreen(this);
            }, Component.literal("Enable real payments?"), Component.literal("AutoGamble will be allowed to send /pay commands automatically."),
                    Component.literal("Enable Real Payments"), Component.literal("Cancel")));
        } else commit();
    }
    private void commit() {
        boolean saved = context.configs().commit(draft.working); context.changed().run();
        if (saved) minecraft.gui.setScreen(parent);
        else { minecraft.gui.setScreen(this); error = "Could not save to disk. Check the log; settings may be active."; }
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void tick() { if (++ticks % 20 == 0) status = context.status().get(); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        g.centeredText(font, "1.0.0  •  " + (draft.working.dryRunMode ? "DRY RUN — no real payments" : "REAL PAYMENTS") + "  •  Save to apply", width / 2, 30, draft.working.dryRunMode ? 0xFF99DDCC : 0xFFFFBB66);
        fields.forEach((field, box) -> g.text(font, field.label, left, box.getY() + 6, 0xFFE0E0E0));
        if (page == 0) g.centeredText(font, font.plainSubstrByWidth(status, panel), width / 2, 178, 0xFFAAAAAA);
        if (page == 3) g.textWithWordWrap(font, Component.literal("Winners are paid one at a time. Disabling gambling clears pending payouts."), left, 132, panel, 0xFFAAAAAA);
        if (page == 4) g.textWithWordWrap(font, Component.literal("Patterns stay in JSON. Import and test authentic messages before enabling live gambling."), left, 166, panel, 0xFFAAAAAA);
        if (page == 5) {
            g.centeredText(font, "Toggle AutoGamble — default F8", width / 2, 80, 0xFFE0E0E0);
            g.centeredText(font, "Open Settings — default F9", width / 2, 97, 0xFFE0E0E0);
        }
        if (!error.isEmpty()) g.centeredText(font, font.plainSubstrByWidth(error, panel), width / 2, height - 43, 0xFFFF8888);
    }
}
