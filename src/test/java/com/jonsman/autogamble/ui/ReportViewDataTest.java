package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.history.PaymentHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReportViewDataTest {
    @TempDir Path directory;

    private static PaymentHistory.Transaction received(String player, String amount, long time) {
        return new PaymentHistory.Transaction(PaymentHistory.Direction.RECEIVED, player, new BigDecimal(amount), time, "TEST");
    }

    private static PaymentHistory.Transaction paid(String player, String amount, long time) {
        return new PaymentHistory.Transaction(PaymentHistory.Direction.PAID, player, new BigDecimal(amount), time, "TEST");
    }

    private static PaymentHistory.Snapshot snapshot(List<PaymentHistory.Customer> received,
                                                    List<PaymentHistory.Customer> paid,
                                                    List<PaymentHistory.Transaction> transactions,
                                                    List<String> followed) {
        return new PaymentHistory.Snapshot(true, transactions.size(), received.size(), paid.size(),
                Set.copyOf(followed.stream().map(PaymentHistory::key).toList()), List.of(), "",
                received, paid, transactions, followed);
    }

    private static SettingsContext context(PaymentHistory.Snapshot snapshot) {
        return new SettingsContext(null, () -> {}, () -> {}, () -> "", () -> "", () -> false,
                () -> {}, () -> "", () -> null, () -> snapshot, () -> {}, () -> new com.jonsman.autogamble.payment.TippingManager.Snapshot(0, false, ""), () -> com.jonsman.autogamble.payment.TippingManager.QueueResult.FULL);
    }

    private static ReportViewData.RecentFilter filter(boolean received, boolean paid, String min,
                                                       String max, int lines, boolean newest) {
        return new ReportViewData.RecentFilter(received, paid, min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), lines, newest);
    }

    @Test void reportsPageIsVisibleInMainNavigation() throws Exception {
        Field field = AutoGambleSettingsScreen.class.getDeclaredField("PAGES");
        field.setAccessible(true);
        assertTrue(List.of((String[]) field.get(null)).contains("Reports"));
    }

    @Test void paymentsToPlayersViewerOpens() {
        assertTrue(ScrollableReportScreen.class.isAssignableFrom(PaymentsToPlayersScreen.class));
    }

    @Test void topCustomersViewerOpens() {
        assertTrue(ScrollableReportScreen.class.isAssignableFrom(TopCustomersScreen.class));
    }

    @Test void recentPaymentsViewerOpens() {
        assertTrue(ScrollableReportScreen.class.isAssignableFrom(RecentPaymentsScreen.class));
    }

    @Test void followedPlayersViewerOpens() {
        assertTrue(ScrollableReportScreen.class.isAssignableFrom(FollowedPlayersScreen.class));
    }

    @Test void snapshotCarriesCanonicalDataDirectly() {
        var canonical = snapshot(List.of(new PaymentHistory.Customer("Bob", BigDecimal.TEN)), List.of(),
                List.of(received("Bob", "10", 1)), List.of("Alice"));
        assertEquals("Bob", canonical.receivedTotals().getFirst().name());
        assertEquals("Alice", canonical.followedPlayers().getFirst());
    }

    @Test void viewersWorkWhenEveryTxtExportIsDisabled() {
        AutoGambleConfig config = new AutoGambleConfig();
        config.generatePaymentsToPlayersReport = config.generateTopCustomersReport = config.generateRecentPaymentsReport = false;
        var canonical = snapshot(List.of(new PaymentHistory.Customer("Bob", BigDecimal.TEN)), List.of(), List.of(), List.of());
        assertEquals(1, ReportViewData.totals(canonical.receivedTotals(), "").size());
    }

    @Test void paymentsToPlayersSortHighestToLowest() {
        var rows = ReportViewData.totals(List.of(new PaymentHistory.Customer("Low", BigDecimal.ONE),
                new PaymentHistory.Customer("High", BigDecimal.TEN)), "");
        assertEquals(List.of("High", "Low"), rows.stream().map(PaymentHistory.Customer::name).toList());
    }

    @Test void topCustomersSortHighestToLowest() {
        var rows = ReportViewData.totals(List.of(new PaymentHistory.Customer("Bob", new BigDecimal("5")),
                new PaymentHistory.Customer("Alice", new BigDecimal("7"))), "");
        assertEquals("Alice", rows.getFirst().name());
    }

    @Test void recentPaymentsAreNewestFirstByDefault() {
        var rows = ReportViewData.recent(List.of(received("Old", "1", 1), paid("New", "2", 2)), "",
                filter(true, true, "0", null, 1000, true));
        assertEquals("New", rows.getFirst().player());
    }

    @Test void receivedOnlyFilterWorks() {
        var rows = ReportViewData.recent(List.of(received("Bob", "1", 1), paid("Alice", "2", 2)), "",
                filter(true, false, "0", null, 10, true));
        assertEquals(List.of(PaymentHistory.Direction.RECEIVED), rows.stream().map(PaymentHistory.Transaction::direction).toList());
    }

    @Test void paidOnlyFilterWorks() {
        var rows = ReportViewData.recent(List.of(received("Bob", "1", 1), paid("Alice", "2", 2)), "",
                filter(false, true, "0", null, 10, true));
        assertEquals(List.of(PaymentHistory.Direction.PAID), rows.stream().map(PaymentHistory.Transaction::direction).toList());
    }

    @Test void minimumAmountFilterIsInclusive() {
        var rows = ReportViewData.recent(List.of(received("Low", "9", 1), received("Exact", "10", 2)), "",
                filter(true, true, "10", null, 10, true));
        assertEquals(List.of("Exact"), rows.stream().map(PaymentHistory.Transaction::player).toList());
    }

    @Test void maximumAmountFilterIsInclusive() {
        var rows = ReportViewData.recent(List.of(received("Exact", "10", 1), received("High", "11", 2)), "",
                filter(true, true, "0", "10", 10, true));
        assertEquals(List.of("Exact"), rows.stream().map(PaymentHistory.Transaction::player).toList());
    }

    @Test void maximumEntriesIsAppliedAfterFilteringAndSorting() {
        var rows = ReportViewData.recent(List.of(received("One", "1", 1), received("Two", "2", 2),
                received("Three", "3", 3)), "", filter(true, true, "0", null, 2, true));
        assertEquals(List.of("Three", "Two"), rows.stream().map(PaymentHistory.Transaction::player).toList());
    }

    @Test void playerSearchIsCaseInsensitive() {
        var rows = ReportViewData.totals(List.of(new PaymentHistory.Customer("JonsmanV2", BigDecimal.TEN)), "jOnSmAn");
        assertEquals(1, rows.size());
    }

    @Test void emptyDataProducesSafeEmptyLists() {
        assertTrue(ReportViewData.totals(List.of(), "").isEmpty());
        assertTrue(ReportViewData.recent(List.of(), "", filter(true, true, "0", null, 10, true)).isEmpty());
        assertTrue(ReportViewData.followed(List.of(), "").isEmpty());
    }

    @Test void followedPlayerSearchIsCaseInsensitive() {
        assertEquals(List.of("JonsmanV2"), ReportViewData.followed(List.of("Alice", "JonsmanV2"), "JON"));
    }

    @Test void scrollOffsetClampsAtBothEnds() {
        assertEquals(0, ReportViewData.clampOffset(-20, 30, 8));
        assertEquals(22, ReportViewData.clampOffset(500, 30, 8));
    }

    @Test void mouseWheelScrollsListsLargerThanTheViewport() {
        assertEquals(1, ReportViewData.scroll(0, -1, 30, 8));
        assertEquals(0, ReportViewData.scroll(1, 1, 30, 8));
    }

    @Test void refreshMakesNewCanonicalDataVisible() throws Exception {
        AutoGambleConfig config = new AutoGambleConfig();
        try (PaymentHistory history = new PaymentHistory(directory, config)) {
            history.awaitWrites();
            history.record(received("Bob", "25", 1));
            history.awaitWrites();
            assertEquals("Bob", ReportViewData.totals(history.snapshot().receivedTotals(), "").getFirst().name());
        }
    }

    @Test void minimum320By240LayoutKeepsRowsAboveButtons() {
        ReportViewData.Layout layout = ReportViewData.layout(240);
        assertEquals(8, layout.visibleRows());
        assertTrue(layout.listTop() < layout.listBottom());
        assertTrue(layout.listBottom() < layout.buttonTop());
    }

    @Test void viewingNeverMutatesCanonicalCollections() {
        List<PaymentHistory.Customer> source = new ArrayList<>(List.of(
                new PaymentHistory.Customer("Low", BigDecimal.ONE), new PaymentHistory.Customer("High", BigDecimal.TEN)));
        List<PaymentHistory.Customer> before = List.copyOf(source);
        ReportViewData.totals(source, "");
        assertEquals(before, source);
    }

    @Test void txtExportFailureDoesNotBreakCanonicalViewerData() throws Exception {
        AutoGambleConfig config = new AutoGambleConfig();
        try (PaymentHistory history = new PaymentHistory(directory, config)) {
            history.awaitWrites();
            Path reports = directory.resolve("reports");
            if (Files.isDirectory(reports)) try (var files = Files.list(reports)) { files.forEach(path -> { try { Files.delete(path); } catch (Exception ex) { throw new RuntimeException(ex); } }); }
            Files.deleteIfExists(reports);
            Files.writeString(reports, "blocks directory creation");
            history.record(received("Bob", "10", 1));
            history.awaitWrites();
            assertEquals("Bob", history.snapshot().receivedTotals().getFirst().name());
            assertFalse(history.snapshot().error().isEmpty());
        }
    }

    @Test void txtTogglesDoNotAlterInGameDataAvailability() {
        var canonical = snapshot(List.of(), List.of(new PaymentHistory.Customer("Alice", new BigDecimal("20"))),
                List.of(paid("Alice", "20", 4)), List.of());
        assertEquals(1, ReportViewData.totals(canonical.paidTotals(), "").size());
        assertEquals(1, ReportViewData.recent(canonical.transactions(), "", filter(true, true, "0", null, 10, true)).size());
    }

    @Test void oldestFirstFilterWorks() {
        var rows = ReportViewData.recent(List.of(received("Old", "1", 1), paid("New", "2", 2)), "",
                filter(true, true, "0", null, 10, false));
        assertEquals("Old", rows.getFirst().player());
    }

    @Test void transactionSourceIsPreservedForDetails() {
        var row = new PaymentHistory.Transaction(PaymentHistory.Direction.PAID, "Bob", BigDecimal.TEN, 1, "GAMBLE_PAYOUT");
        assertEquals("GAMBLE_PAYOUT", ReportViewData.recent(List.of(row), "", filter(true, true, "0", null, 10, true)).getFirst().source());
    }
}
