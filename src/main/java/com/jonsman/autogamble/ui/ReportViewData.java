package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.history.PaymentHistory;
import java.math.BigDecimal;
import java.util.*;

/** Pure, read-only projections of canonical payment history for report screens. */
public final class ReportViewData {
    public record RecentFilter(boolean showReceived, boolean showPaid, BigDecimal minimum,
                               BigDecimal maximum, int maximumEntries, boolean newestFirst) {}
    private ReportViewData() {}

    public static PaymentHistory.Snapshot emptySnapshot() {
        return new PaymentHistory.Snapshot(false, 0, 0, 0, Set.of(), List.of(), "",
                List.of(), List.of(), List.of(), List.of());
    }

    public static List<PaymentHistory.Customer> totals(List<PaymentHistory.Customer> source, String search) {
        String query = query(search);
        return source.stream().filter(c -> query.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(PaymentHistory.Customer::total).reversed()
                        .thenComparing(PaymentHistory.Customer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static List<PaymentHistory.Transaction> recent(List<PaymentHistory.Transaction> source,
                                                          String search, RecentFilter filter) {
        String query = query(search);
        List<PaymentHistory.Transaction> events = new ArrayList<>(source);
        if (filter.newestFirst()) Collections.reverse(events);
        Comparator<PaymentHistory.Transaction> order = Comparator.comparingLong(PaymentHistory.Transaction::timestamp);
        if (filter.newestFirst()) order = order.reversed();
        return events.stream()
                .filter(t -> t.direction() == PaymentHistory.Direction.RECEIVED ? filter.showReceived() : filter.showPaid())
                .filter(t -> filter.minimum() == null || t.amount().compareTo(filter.minimum()) >= 0)
                .filter(t -> filter.maximum() == null || t.amount().compareTo(filter.maximum()) <= 0)
                .filter(t -> query.isEmpty() || t.player().toLowerCase(Locale.ROOT).contains(query))
                .sorted(order).limit(Math.max(0, filter.maximumEntries())).toList();
    }

    public static List<String> followed(List<String> source, String search) {
        String query = query(search);
        return source.stream().filter(name -> query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public record Layout(int listTop, int listBottom, int buttonTop, int visibleRows) {}

    public static Layout layout(int height) {
        int listTop = 72, buttonTop = height - 28, listBottom = height - 36;
        return new Layout(listTop, listBottom, buttonTop, Math.max(1, (listBottom - listTop) / 16));
    }

    public static int clampOffset(int offset, int totalRows, int visibleRows) {
        return Math.clamp(offset, 0, Math.max(0, totalRows - Math.max(1, visibleRows)));
    }

    public static int scroll(int offset, double verticalAmount, int totalRows, int visibleRows) {
        int step = verticalAmount > 0 ? -1 : verticalAmount < 0 ? 1 : 0;
        return clampOffset(offset + step, totalRows, visibleRows);
    }

    private static String query(String search) {
        return search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
    }
}
