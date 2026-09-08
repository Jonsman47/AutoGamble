package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Shares the parent draft: navigation and resizing never discard edits. */
public final class AutomationSettingsScreen extends Screen {
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private final Map<EditBox, String> labels = new LinkedHashMap<>();
    private int page, offset, left, panel, row;
    private String error = "";
    private BalanceRule editing;
    private int editingIndex = -1;
    private String player = "", threshold = "", amount = "", cooldown = "30";
    private Button save;
    public AutomationSettingsScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Customers, Reports & Balance")); this.parent = parent; this.context = context; this.draft = draft;
    }
    private Button button(String label, int y, Runnable action) { return button(label, left, y, panel, action); }
    private Button button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, width, 20).build());
    }
    private void navigate(int page) { this.page = page; error = ""; rebuildWidgets(); }
    private void toggle(String label, BooleanSupplier get, Consumer<Boolean> set, String tip) {
        button(label + ": " + (get.getAsBoolean() ? "ON" : "OFF"), row, () -> { set.accept(!get.getAsBoolean()); rebuildWidgets(); })
            .setTooltip(Tooltip.create(Component.literal(tip))); row += 24;
    }
    private void text(String label, String value, Consumer<String> changed, String tooltip) {
        int width = Math.min(160, panel / 2);
        EditBox box = addRenderableWidget(new EditBox(font, left + panel - width, row, width, 20, Component.literal(label)));
        box.setMaxLength(64); box.setValue(value); box.setResponder(v -> { changed.accept(v); validate(); });
        box.setTooltip(Tooltip.create(Component.literal(tooltip))); labels.put(box, label); row += 24;
    }
    private void money(SettingsDraft.Field field, String tooltip) { text(field.label, draft.text(field), value -> draft.text(field, value), tooltip); }
    @Override protected void init() {
        labels.clear(); panel = Math.min(440, width - 24); left = (width-panel)/2; row = 58;
        var c = draft.working;
        switch (page) {
            case 0 -> {
                button("Good Customer Follow", row, () -> navigate(1));
                button("Optional TXT Exports", row+28, () -> navigate(2));
                button("Balance Payment Rules", row+56, () -> navigate(5));
                button("Auto-Pay Attribution", row+84, () -> navigate(7));
            }
            case 1 -> {
                toggle("Auto Follow Good Customers", () -> c.autoFollowGoodCustomersEnabled, v -> c.autoFollowGoodCustomersEnabled = v,
                    "Automatically follows customers whose total payments reach the configured amount.");
                money(SettingsDraft.Field.FOLLOW_THRESHOLD, "Total amount a player must pay before AutoGamble follows them once.");
                button("Clear Followed Player History", row+4, () -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                    if (yes) context.clearFollowed().run(); minecraft.gui.setScreen(this);
                }, Component.literal("Clear Followed Player History?"), Component.literal("Clearing this list allows previously followed players to trigger Auto Follow again."))));
            }
            case 2 -> {
                toggle("Generate Payments-To-Players TXT", () -> c.generatePaymentsToPlayersReport, v -> c.generatePaymentsToPlayersReport = v, "Totals of real dispatched outgoing payments.");
                toggle("Generate Top-Customers TXT", () -> c.generateTopCustomersReport, v -> c.generateTopCustomersReport = v, "Cumulative validated incoming payments.");
                toggle("Generate Recent-Payments TXT", () -> c.generateRecentPaymentsReport, v -> c.generateRecentPaymentsReport = v, "OFF preserves the existing file without updating it.");
                button("Recent Payment Filters", row, () -> navigate(3));
                button("Order, Lines & Stored History", row+24, () -> navigate(4));
            }
            case 3 -> {
                toggle("Show Received", () -> c.recentShowReceived, v -> c.recentShowReceived = v, "Include incoming payments.");
                toggle("Show Paid", () -> c.recentShowPaid, v -> c.recentShowPaid = v, "Include real outgoing payments.");
                money(SettingsDraft.Field.RECENT_MIN, "Inclusive minimum. Supports 1M and 5000000.");
                money(SettingsDraft.Field.RECENT_MAX, "Inclusive maximum. Leave blank for unlimited.");
            }
            case 4 -> {
                toggle("Newest First", () -> c.recentNewestFirst, v -> c.recentNewestFirst = v, "Newest transactions appear at the top.");
                money(SettingsDraft.Field.RECENT_LINES, "Maximum report lines: 1–100000.");
                money(SettingsDraft.Field.HISTORY_LIMIT, "Keep 100–1000000 transactions. Trimming never removes aggregate totals.");
            }
            case 5 -> {
                toggle("Automatic Balance Payments", () -> c.automaticBalancePaymentsEnabled, v -> c.automaticBalancePaymentsEnabled = v, "Requires a verified current balance. UNKNOWN never triggers payments.");
                offset = Math.min(offset, Math.max(0, c.balancePaymentRules.size()-1));
                for (int i=offset; i<Math.min(offset+3, c.balancePaymentRules.size()); i++) {
                    int index = i; var r = c.balancePaymentRules.get(i);
                    String label = (r.enabled ? "ON" : "OFF") + " | " + r.player + " | >= " + MoneyValues.display(r.threshold) + " | Pay " + MoneyValues.display(r.amount);
                    button(font.plainSubstrByWidth(label, panel-55), left, row, panel-48, () -> editRule(index));
                    button("Del", left+panel-44, row, 44, () -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                        if (yes) c.balancePaymentRules.remove(index); minecraft.gui.setScreen(this);
                    }, Component.literal("Delete rule?"), Component.literal("Delete the balance payment rule for " + r.player + "?")))); row += 24;
                }
                button("Add Rule", left, 162, panel/2-4, () -> editRule(-1)).active = c.balancePaymentRules.size() < 100;
                button("<", left+panel/2, 162, panel/4-3, () -> { offset=Math.max(0, offset-3); rebuildWidgets(); });
                button(">", left+3*panel/4, 162, panel/4, () -> { if (offset+3<c.balancePaymentRules.size()) offset+=3; rebuildWidgets(); });
            }
            case 6 -> {
                toggle("Rule Enabled", () -> editing.enabled, v -> editing.enabled = v, "The master switch must also be on.");
                text("Player", player, v -> player=v, "Minecraft username.");
                text("Balance Threshold", threshold, v -> threshold=v, "Positive amount, e.g. 500M.");
                text("Payment Amount", amount, v -> amount=v, "Positive amount, e.g. 200M.");
                text("Cooldown (seconds)", cooldown, v -> cooldown=v, "0–86400 seconds; also requires falling below threshold to re-arm.");
            }
            case 7 -> {
                money(SettingsDraft.Field.CONVERSION_WINDOW, "Seconds after an advertising payment during which the same player's next payment converts.");
                money(SettingsDraft.Field.ATTRIBUTION_DURATION, "Seconds after conversion during which that player's revenue and profit remain attributed.");
            }
        }
        save = button(page == 6 ? "Apply Rule" : "Save & Done", left, height-28, panel/2-3, page == 6 ? this::applyRule : this::save);
        button(page == 6 ? "Cancel" : "Back", left+panel/2+3, height-28, panel/2-3, () -> {
            if (page == 0) minecraft.gui.setScreen(parent); else navigate(page == 6 ? 5 : page == 3 || page == 4 ? 2 : 0);
        }); validate();
    }
    private void editRule(int index) {
        editingIndex = index;
        editing = index < 0 ? new BalanceRule() : MoneyValues.gson().create().fromJson(MoneyValues.gson().create().toJson(draft.working.balancePaymentRules.get(index)), BalanceRule.class);
        player=editing.player; threshold=MoneyValues.plain(editing.threshold); amount=MoneyValues.plain(editing.amount); cooldown=""+editing.cooldownSeconds; navigate(6);
    }
    private void parseRule() {
        editing.player=player.trim(); editing.threshold=MoneyValues.parse(threshold); editing.amount=MoneyValues.parse(amount);
        if (!cooldown.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Cooldown must be 0–86400 seconds.");
        editing.cooldownSeconds=Integer.parseInt(cooldown);
        if (!editing.valid()) throw new IllegalArgumentException("Enter a valid player, positive amounts and cooldown 0–86400.");
    }
    private void validate() {
        try {
            if (page == 6) parseRule();
            var errors = draft.validate(); error=errors.isEmpty() ? "" : errors.getFirst();
            if (context.configs().isReadOnly()) error="Newer config version: settings are read-only.";
        } catch (IllegalArgumentException ex) { error=ex.getMessage(); }
        if (save != null) save.active=error.isEmpty();
    }
    private void applyRule() {
        validate(); if (!error.isEmpty()) return;
        if (editingIndex < 0) draft.working.balancePaymentRules.add(editing); else draft.working.balancePaymentRules.set(editingIndex, editing);
        navigate(5);
    }
    private void save() {
        validate(); if (!error.isEmpty()) return;
        if (RuntimeSettingsChange.requiresRealPaymentConfirmation(context.configs().snapshot(), draft.working)) {
            minecraft.gui.setScreen(new ConfirmScreen(yes -> { if (yes) commit(); else minecraft.gui.setScreen(this); },
                Component.literal("Enable real payments?"), Component.literal("AutoGamble may send automatic /pay and /follow commands.")));
        } else commit();
    }
    private void commit() {
        if (context.configs().commit(draft.working)) { context.changed().run(); minecraft.gui.setScreen(null); }
        else { minecraft.gui.setScreen(this); error="Could not save settings. Check logs."; }
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g,mx,my,delta);
        String[] titles={"Customers, Reports & Balance", "Good Customer Follow", "Optional TXT Exports", "Recent Payment Filters", "Order, Lines & Stored History", "Balance Payment Rules", "Edit Balance Rule", "Auto-Pay Attribution"};
        g.centeredText(font, Component.literal(titles[page]), width/2, 15, 0xFFFFFFFF);
        String hint=page==5 ? "Balance: UNKNOWN — no verified DonutSMP source" : "Save to apply • Back preserves your draft";
        g.centeredText(font, font.plainSubstrByWidth(hint,panel),width/2,34,0xFFAAAAAA);
        labels.forEach((box,label) -> g.text(font,font.plainSubstrByWidth(label, panel/2-8),left,box.getY()+6,0xFFE0E0E0));
        if (!error.isEmpty()) g.centeredText(font,font.plainSubstrByWidth(error,panel),width/2,height-42,0xFFFF8888);
    }
}
