package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.AnalyticsEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.math.BigDecimal;
import java.util.Locale;

/** Read-only session, EV, customer, and Auto-Pay ROI views. */
public final class AnalyticsScreen extends Screen {
    private static final String[] PAGES = {"Session", "EV", "Customers", "Auto-Pay ROI"};
    private final Screen parent; private final SettingsContext context; private final SettingsDraft draft;
    private int page, left, panel, customerSort;
    public AnalyticsScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("AutoGamble Analytics")); this.parent = parent; this.context = context; this.draft = draft;
    }
    @Override protected void init() {
        panel = Math.min(500, width - 24); left = (width - panel) / 2;
        for (int i = 0; i < PAGES.length; i++) { final int target = i; int w = panel / PAGES.length;
            Button b = addRenderableWidget(Button.builder(Component.literal(PAGES[i]), ignored -> { page = target; rebuildWidgets(); })
                    .bounds(left + i * w, 36, w - 2, 20).build()); b.active = i != page;
        }
        if (page == 2) addRenderableWidget(Button.builder(Component.literal("Sort: " + switch (customerSort) { case 1 -> "Paid By"; case 2 -> "Paid Back"; default -> "Net"; }),
                b -> { customerSort = (customerSort + 1) % 3; rebuildWidgets(); }).bounds(left, 60, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, height - 28, panel, 20).build());
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta); g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        AnalyticsEngine.Snapshot s = context.analytics().get(); if (s == null) return;
        int y = 68;
        switch (page) {
            case 0 -> {
                y = line(g, y, "Session time: " + duration((long)s.elapsedSeconds()));
                y = line(g, y, "Payments received: " + s.paymentsReceived() + "  •  Money received: " + money(s.moneyReceived()));
                y = line(g, y, "Average payment: " + money(average(s.moneyReceived(), s.paymentsReceived())) + "  •  Payments/hour: " + String.format(Locale.ROOT, "%.2f", s.paymentsPerMinute() * 60));
                y = line(g, y, "Money paid: " + money(s.moneyPaid()) + "  •  Tracked net profit: " + money(s.trackedNetProfit()));
                y = line(g, y, "Gambling profit: " + money(s.gamblingProfit()) + "  •  Advertising cost: " + money(s.advertisingCost()));
                y = line(g, y, "Bets: " + s.bets() + "  •  Won/Lost: " + s.wins() + "/" + s.losses() + "  •  Average bet: " + money(average(s.gamblingStake(), s.bets())));
                y = line(g, y, String.format(Locale.ROOT, "Payments/min: %.2f  •  Bets/min: %.2f", s.paymentsPerMinute(), s.betsPerMinute()));
                line(g, y, "Unique customers: " + s.uniqueCustomers() + "  •  Returning customers: " + s.returningCustomers());
            }
            case 1 -> {
                AutoGambleConfig c = draft.working; BigDecimal bet = BigDecimal.valueOf(c.minimumBet).add(BigDecimal.valueOf(c.maximumBet)).divide(BigDecimal.valueOf(2));
                BigDecimal normal = AnalyticsEngine.expectedProfit(bet, c.winChance, c.payoutMultiplier);
                double firstChance = GambleManagerChance.first(c);
                BigDecimal first = AnalyticsEngine.expectedProfit(bet, firstChance, c.payoutMultiplier);
                y = line(g, y, "Payout semantics: a winner receives bet × multiplier (total return).");
                y = line(g, y, "EV formula: bet × (1 − win chance × payout multiplier)");
                y = line(g, y, "Configured win chance: " + pct(c.winChance) + "  •  Multiplier: " + c.payoutMultiplier + "×");
                y = line(g, y, "Example bet: " + money(bet) + "  •  Normal expected profit: " + money(normal));
                y = line(g, y, "House edge: " + pct(normal.divide(bet.signum() == 0 ? BigDecimal.ONE : bet, 8, java.math.RoundingMode.HALF_UP).doubleValue()));
                y = line(g, y, "Break-even multiplier: " + (c.winChance == 0 ? "UNDEFINED" : String.format(Locale.ROOT, "%.4f×", 1 / c.winChance))
                        + "  •  Break-even chance: " + pct(1 / c.payoutMultiplier));
                line(g, y, "First-time chance: " + pct(firstChance) + "  •  First-time expected profit: " + money(first));
            }
            case 2 -> {
                y = 86;
                y = line(g, y, "Customer | Paid By | Paid Back | Net | Bets (W/L)");
                var customers = s.customers().stream().sorted(switch (customerSort) {
                    case 1 -> java.util.Comparator.comparing(AnalyticsEngine.Customer::paidBy).reversed();
                    case 2 -> java.util.Comparator.comparing(AnalyticsEngine.Customer::paidBack).reversed();
                    default -> java.util.Comparator.comparing(AnalyticsEngine.Customer::net).reversed();
                }).toList();
                int shown = 0; for (var c : customers) { if (shown++ >= 8) break;
                    y = line(g, y, c.player() + " | " + money(c.paidBy()) + " | " + money(c.paidBack()) + " | " + money(c.net())
                            + " | " + c.bets() + " (" + c.wins() + "/" + c.losses() + ") | " + (c.lastPayment() == 0 ? "never" : java.time.Instant.ofEpochMilli(c.lastPayment()).toString()));
                }
                if (s.customers().isEmpty()) line(g, y, "No customer transactions recorded yet.");
            }
            case 3 -> {
                y = line(g, y, "Session — payments: " + s.sessionAdvertisingPayments() + ", spend: " + money(s.sessionAdvertisingSpend()));
                y = line(g, y, "Advertised: " + s.sessionUniqueAdvertised() + ", converted: " + s.sessionConverted()
                        + ", revenue: " + money(s.sessionAttributedRevenue()));
                y = line(g, y, "Conversion rate: " + pct(rate(s.sessionConverted(), s.sessionUniqueAdvertised())));
                y = line(g, y, "Attributed profit: " + money(s.sessionAttributedProfit()) + ", ROI: "
                        + AnalyticsEngine.roi(s.sessionAttributedProfit(), s.sessionAdvertisingSpend()) + "%");
                y = line(g, y + 4, "Lifetime — payments: " + s.lifetimeAdvertisingPayments() + ", spend: " + money(s.lifetimeAdvertisingSpend()));
                y = line(g, y, "Advertised: " + s.lifetimeUniqueAdvertised() + ", converted: " + s.lifetimeConverted()
                        + ", revenue: " + money(s.lifetimeAttributedRevenue()));
                y = line(g, y, "Conversion rate: " + pct(rate(s.lifetimeConverted(), s.lifetimeUniqueAdvertised())));
                line(g, y, "Attributed profit: " + money(s.lifetimeAttributedProfit()) + ", ROI: "
                        + AnalyticsEngine.roi(s.lifetimeAttributedProfit(), s.lifetimeAdvertisingSpend()) + "%");
            }
            default -> {}
        }
        if (!s.error().isEmpty()) g.centeredText(font, s.error(), width / 2, height - 43, 0xFFFF8888);
    }
    private int line(GuiGraphicsExtractor g, int y, String text) { g.text(font, font.plainSubstrByWidth(text, panel), left, y, 0xFFE0E0E0); return y + 17; }
    private static String money(BigDecimal value) { return MoneyValues.display(value); }
    private static BigDecimal average(BigDecimal total, long count) { return count == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP); }
    private static double rate(int converted, int advertised) { return advertised == 0 ? 0 : (double) converted / advertised; }
    private static String pct(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }
    private static String duration(long seconds) { return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private static final class GambleManagerChance { static double first(AutoGambleConfig c) { return Math.min(1, c.winChance + (c.firstTimePayerBonusEnabled ? c.firstTimeWinBonus : 0)); } }
}
