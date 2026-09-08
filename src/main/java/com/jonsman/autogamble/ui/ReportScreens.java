package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.MoneyValues;
import com.jonsman.autogamble.config.SettingsDraft;
import com.jonsman.autogamble.history.PaymentHistory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

public final class ReportScreens {
    private ReportScreens() {}

    public static Screen paymentsToPlayers(Screen parent, SettingsContext context) {
        return new PaymentsToPlayersScreen(parent, context);
    }

    public static Screen topCustomers(Screen parent, SettingsContext context) {
        return new TopCustomersScreen(parent, context);
    }

    public static Screen recentPayments(Screen parent, SettingsContext context, SettingsDraft draft) {
        return new RecentPaymentsScreen(parent, context, draft);
    }

    public static Screen followedPlayers(Screen parent, SettingsContext context) {
        return new FollowedPlayersScreen(parent, context);
    }

    public static Screen recentFilters(Screen parent, SettingsDraft draft) {
        return new RecentPaymentFiltersScreen(parent, draft);
    }
}

abstract class ScrollableReportScreen<T> extends Screen {
    protected final Screen parent;
    protected final SettingsContext context;
    private final String emptyText;
    protected int left, panel, listTop, listBottom, visibleRows, offset;
    protected EditBox search;
    protected List<T> rows = List.of();

    ScrollableReportScreen(String title, Screen parent, SettingsContext context, String emptyText) {
        super(Component.literal(title));
        this.parent = parent;
        this.context = context;
        this.emptyText = emptyText;
    }

    @Override protected void init() {
        panel = Math.min(500, width - 24);
        left = (width - panel) / 2;
        ReportViewData.Layout layout = ReportViewData.layout(height);
        listTop = layout.listTop();
        listBottom = layout.listBottom();
        visibleRows = layout.visibleRows();
        search = addRenderableWidget(new EditBox(font, left, 34, panel, 20, Component.literal("Search players")));
        search.setHint(Component.literal("Search players"));
        search.setMaxLength(64);
        search.setResponder(ignored -> reload());
        int third = panel / 3;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), ignored -> {
            context.refreshReports().run();
            reload();
        }).bounds(left, height - 28, third - 2, 20).build());
        addMiddleButton(left + third, height - 28, third - 2);
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(left + third * 2, height - 28, panel - third * 2, 20).build());
        reload();
    }

    protected void addMiddleButton(int x, int y, int width) {
    }

    protected final PaymentHistory.Snapshot snapshot() {
        PaymentHistory.Snapshot snapshot = context.reports().get();
        return snapshot == null ? ReportViewData.emptySnapshot() : snapshot;
    }

    protected final void reload() {
        rows = loadRows(search == null ? "" : search.getValue());
        offset = ReportViewData.clampOffset(offset, rows.size(), visibleRows);
    }

    protected abstract List<T> loadRows(String search);
    protected abstract String header();
    protected abstract String rowText(T row);

    @Override public void tick() {
        if (minecraft.level != null && minecraft.level.getGameTime() % 20 == 0) reload();
    }

    @Override public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= left && mouseX <= left + panel && mouseY >= listTop && mouseY <= listBottom) {
            offset = ReportViewData.scroll(offset, verticalAmount, rows.size(), visibleRows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.text(font, font.plainSubstrByWidth(header(), panel - 12), left, 59, 0xFFAAAAAA);
        if (rows.isEmpty()) {
            graphics.centeredText(font, font.plainSubstrByWidth(emptyText, panel), width / 2,
                    listTop + Math.max(8, (listBottom - listTop) / 2 - 4), 0xFFAAAAAA);
        } else {
            int end = Math.min(rows.size(), offset + visibleRows);
            for (int index = offset; index < end; index++) {
                String line = rowText(rows.get(index));
                graphics.text(font, font.plainSubstrByWidth(line, panel - 12), left, listTop + (index - offset) * 16 + 3,
                        index % 2 == 0 ? 0xFFE8E8E8 : 0xFFC8C8C8);
            }
            drawScrollbar(graphics);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        if (rows.size() <= visibleRows) return;
        int trackHeight = Math.max(1, listBottom - listTop);
        int thumbHeight = Math.max(10, trackHeight * visibleRows / rows.size());
        int maxOffset = rows.size() - visibleRows;
        int thumbY = listTop + (trackHeight - thumbHeight) * offset / maxOffset;
        for (int y = listTop; y < listBottom; y += 8) graphics.text(font, "|", left + panel - 7, y, 0xFF555555);
        for (int y = thumbY; y < thumbY + thumbHeight; y += 8) graphics.text(font, "#", left + panel - 7, y, 0xFFBBBBBB);
    }
}

final class PaymentsToPlayersScreen extends ScrollableReportScreen<PaymentHistory.Customer> {
    PaymentsToPlayersScreen(Screen parent, SettingsContext context) {
        super("Payments To Players", parent, context, "No outgoing payment data yet.");
    }
    @Override protected List<PaymentHistory.Customer> loadRows(String search) {
        return ReportViewData.totals(snapshot().paidTotals(), search);
    }
    @Override protected String header() { return "Player | Total Paid"; }
    @Override protected String rowText(PaymentHistory.Customer row) {
        return row.name() + " | " + MoneyValues.display(row.total());
    }
}

final class TopCustomersScreen extends ScrollableReportScreen<PaymentHistory.Customer> {
    TopCustomersScreen(Screen parent, SettingsContext context) {
        super("Top Customers", parent, context, "No incoming payment data yet.");
    }
    @Override protected List<PaymentHistory.Customer> loadRows(String search) {
        return ReportViewData.totals(snapshot().receivedTotals(), search);
    }
    @Override protected String header() { return "Player | Total Paid You"; }
    @Override protected String rowText(PaymentHistory.Customer row) {
        return row.name() + " | " + MoneyValues.display(row.total());
    }
}

final class FollowedPlayersScreen extends ScrollableReportScreen<String> {
    FollowedPlayersScreen(Screen parent, SettingsContext context) {
        super("Followed Players", parent, context, "No players have been auto-followed yet.");
    }
    @Override protected List<String> loadRows(String search) {
        return ReportViewData.followed(snapshot().followedPlayers(), search);
    }
    @Override protected String header() { return "Player | Total: " + rows.size(); }
    @Override protected String rowText(String row) { return row; }
}

final class RecentPaymentsScreen extends ScrollableReportScreen<PaymentHistory.Transaction> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final SettingsDraft draft;

    RecentPaymentsScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super("Recent Payments", parent, context, "No transactions match the current filters.");
        this.draft = draft;
    }

    @Override protected List<PaymentHistory.Transaction> loadRows(String search) {
        var config = draft.working;
        return ReportViewData.recent(snapshot().transactions(), search,
                new ReportViewData.RecentFilter(config.recentShowReceived, config.recentShowPaid,
                        config.recentMinimumAmount, config.recentMaximumAmount, config.recentMaxLines,
                        config.recentNewestFirst));
    }

    @Override protected void addMiddleButton(int x, int y, int width) {
        addRenderableWidget(Button.builder(Component.literal("Filters"), ignored ->
                minecraft.gui.setScreen(new RecentPaymentFiltersScreen(this, draft))).bounds(x, y, width, 20).build());
    }

    @Override protected String header() { return "Time | Direction | Player | Amount"; }
    @Override protected String rowText(PaymentHistory.Transaction row) {
        return TIME.format(Instant.ofEpochMilli(row.timestamp())) + " | " + row.direction() + " | "
                + row.player() + " | " + MoneyValues.display(row.amount());
    }
}

final class RecentPaymentFiltersScreen extends Screen {
    private final Screen parent;
    private final SettingsDraft draft;
    private int left, panel;
    private EditBox minimum, maximum, entries;
    private boolean received, paid, newest;
    private String error = "";

    RecentPaymentFiltersScreen(Screen parent, SettingsDraft draft) {
        super(Component.literal("Recent Payment Filters"));
        this.parent = parent;
        this.draft = draft;
        received = draft.working.recentShowReceived;
        paid = draft.working.recentShowPaid;
        newest = draft.working.recentNewestFirst;
    }

    @Override protected void init() {
        panel = Math.min(420, width - 24);
        left = (width - panel) / 2;
        toggle("Show Received", 38, () -> received, value -> received = value);
        toggle("Show Paid", 61, () -> paid, value -> paid = value);
        minimum = field("Minimum Amount", draft.text(SettingsDraft.Field.RECENT_MIN), 84);
        maximum = field("Maximum Amount (blank = unlimited)", draft.text(SettingsDraft.Field.RECENT_MAX), 107);
        entries = field("Maximum Entries Displayed", draft.text(SettingsDraft.Field.RECENT_LINES), 130);
        toggle("Newest First", 153, () -> newest, value -> newest = value);
        int half = panel / 2;
        addRenderableWidget(Button.builder(Component.literal("Apply"), ignored -> apply())
                .bounds(left, height - 28, half - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(left + half + 3, height - 28, panel - half - 3, 20).build());
    }

    private EditBox field(String label, String value, int y) {
        int fieldWidth = Math.min(130, panel / 2);
        EditBox box = addRenderableWidget(new EditBox(font, left + panel - fieldWidth, y, fieldWidth, 20, Component.literal(label)));
        box.setMaxLength(32);
        box.setValue(value);
        box.setResponder(ignored -> error = "");
        return box;
    }

    private void toggle(String label, int y, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(Component.literal(label + ": " + (get.get() ? "ON" : "OFF")), button -> {
            set.accept(!get.get());
            button.setMessage(Component.literal(label + ": " + (get.get() ? "ON" : "OFF")));
        }).bounds(left, y, panel, 20).build());
    }

    private void apply() {
        draft.text(SettingsDraft.Field.RECENT_MIN, minimum.getValue());
        draft.text(SettingsDraft.Field.RECENT_MAX, maximum.getValue());
        draft.text(SettingsDraft.Field.RECENT_LINES, entries.getValue());
        draft.working.recentShowReceived = received;
        draft.working.recentShowPaid = paid;
        draft.working.recentNewestFirst = newest;
        var errors = draft.validate();
        if (!errors.isEmpty()) {
            error = errors.getFirst();
            return;
        }
        onClose();
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.text(font, "Minimum Amount", left, 90, 0xFFE0E0E0);
        graphics.text(font, font.plainSubstrByWidth("Maximum Amount", panel / 2 - 8), left, 113, 0xFFE0E0E0);
        graphics.text(font, font.plainSubstrByWidth("Maximum Entries Displayed", panel / 2 - 8), left, 136, 0xFFE0E0E0);
        if (!error.isEmpty()) graphics.centeredText(font, font.plainSubstrByWidth(error, panel), width / 2, height - 43, 0xFFFF8888);
    }
}
