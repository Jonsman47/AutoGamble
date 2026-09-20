package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.config.SettingsDraft.Field;
import com.jonsman.autogamble.history.AnalyticsEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.text.DateFormat;
import java.util.*;

/** Compact editor for weighted targeting, wealth filters, provider health and method analytics. */
public final class TargetingSettingsScreen extends Screen {
    private static final String[] PAGES = {"Weights", "Money", "Providers", "Analytics"};
    private final Screen parent; private final SettingsContext context; private final SettingsDraft draft;
    private final Map<Field, EditBox> fields = new EnumMap<>(Field.class);
    private int page, panel, left, row; private String error = ""; private Button save;
    public TargetingSettingsScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Advertising Targeting")); this.parent = parent; this.context = context; this.draft = draft;
    }
    @Override protected void init() {
        fields.clear(); panel = Math.min(440, width - 24); left = (width - panel) / 2; row = 76;
        int tabWidth = panel / PAGES.length;
        for (int i = 0; i < PAGES.length; i++) {
            int target = i; Button tab = button(PAGES[i], left + i * tabWidth, 46, tabWidth - 2, () -> { page = target; rebuildWidgets(); });
            tab.active = i != page;
        }
        switch (page) {
            case 0 -> {
                number(Field.SMART_WEIGHT, "Percent of attempts using current /pay prefix suggestions.");
                number(Field.MONEY_WEIGHT, "Percent using balance-filtered leaderboard players.");
                number(Field.ECONOMY_WEIGHT, "Percent using public /sell and /shop activity leaderboards.");
                number(Field.EXPERIMENTAL_WEIGHT, "Percent reserved for safe mixed candidate strategies.");
                button("Reset Recommended 50 / 30 / 15 / 5", left, row + 4, panel, this::resetWeights);
            }
            case 1 -> {
                money(Field.LEADERBOARD_MIN, "Inclusive minimum. Supports K, M, B and T, for example 1.5B.");
                money(Field.LEADERBOARD_MAX, "Inclusive maximum. Leave blank for no maximum.");
                int fifth = panel / 5;
                String[] labels = {"100M+", "500M+", "1B+", "10B+", "100B+"};
                String[] values = {"100M", "500M", "1B", "10B", "100B"};
                for (int i = 0; i < labels.length; i++) { int index = i; button(labels[i], left + i*fifth, row + 4, fifth-2, () -> preset(values[index])); }
                button("Custom: edit the fields above", left, row + 30, panel, () -> fields.get(Field.LEADERBOARD_MIN).setFocused(true)).active = false;
            }
            case 2 -> button("Refresh leaderboard data", left, row, panel, () -> context.refreshLeaderboards().getAsBoolean());
            case 3 -> { }
            default -> throw new IllegalStateException();
        }
        save = button("Save & Done", left, height - 28, panel / 2 - 3, this::save);
        button("Back", left + panel / 2 + 3, height - 28, panel / 2 - 3, () -> minecraft.gui.setScreen(parent));
        validate();
    }
    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x,y,w,20).build());
    }
    private void number(Field field, String tooltip) { edit(field, tooltip); }
    private void money(Field field, String tooltip) { edit(field, tooltip); }
    private void edit(Field field, String tooltip) {
        int w = Math.min(150, panel/2); EditBox box = addRenderableWidget(new EditBox(font,left+panel-w,row,w,20,Component.literal(field.label)));
        box.setMaxLength(32); box.setValue(draft.text(field)); box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(value -> { draft.text(field,value); validate(); }); fields.put(field,box); row += 24;
    }
    private void resetWeights() {
        set(Field.SMART_WEIGHT,"50"); set(Field.MONEY_WEIGHT,"30"); set(Field.ECONOMY_WEIGHT,"15"); set(Field.EXPERIMENTAL_WEIGHT,"5"); validate();
    }
    private void preset(String minimum) { set(Field.LEADERBOARD_MIN,minimum); set(Field.LEADERBOARD_MAX,""); validate(); }
    private void set(Field field, String value) { draft.text(field,value); EditBox box=fields.get(field); if(box!=null) box.setValue(value); }
    private int total() {
        try { return Integer.parseInt(draft.text(Field.SMART_WEIGHT)) + Integer.parseInt(draft.text(Field.MONEY_WEIGHT))
                + Integer.parseInt(draft.text(Field.ECONOMY_WEIGHT)) + Integer.parseInt(draft.text(Field.EXPERIMENTAL_WEIGHT)); }
        catch (RuntimeException ex) { return -1; }
    }
    private void validate() {
        var errors=draft.validate(); error=errors.isEmpty()?"":errors.getFirst();
        if(context.configs().isReadOnly()) error="Newer config version: settings are read-only.";
        if(save!=null) save.active=error.isEmpty();
        fields.values().forEach(f->f.setTextColor(error.isEmpty()?0xFFE0E0E0:0xFFFF8888));
    }
    private void save() {
        validate(); if(!error.isEmpty()) return;
        if(RuntimeSettingsChange.requiresRealPaymentConfirmation(context.configs().snapshot(),draft.working))
            minecraft.gui.setScreen(new ConfirmScreen(yes->{if(yes) commit();else minecraft.gui.setScreen(this);},Component.literal("Enable real payments?"),Component.literal("AutoGamble will be allowed to send /pay commands automatically.")));
        else commit();
    }
    private void commit() {
        if(context.configs().commit(draft.working)){context.changed().run();minecraft.gui.setScreen(parent);}
        else{minecraft.gui.setScreen(this);error="Could not save settings. Check logs.";}
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        super.extractRenderState(g,mx,my,delta); g.centeredText(font,title,width/2,15,0xFFFFFFFF);
        g.centeredText(font,"Every candidate is verified online with /pay autocomplete before payment",width/2,30,0xFF99DDCC);
        fields.forEach((field,box)->g.text(font,field.label,left,box.getY()+6,0xFFE0E0E0));
        if(page==0){int total=total();g.centeredText(font,"Current total: "+(total<0?"invalid":total+"%"),width/2,184,total==100?0xFF99DDCC:0xFFFF8888);}
        if(page==1){
            String min=normalized(Field.LEADERBOARD_MIN), max=draft.text(Field.LEADERBOARD_MAX).isBlank()?"No maximum":normalized(Field.LEADERBOARD_MAX);
            g.centeredText(font,"Normalized range: "+min+" – "+max,width/2,130,0xFFAAAAAA);
        }
        if(page==2) renderProviders(g);
        if(page==3) renderAnalytics(g);
        if(!error.isEmpty())g.centeredText(font,font.plainSubstrByWidth(error,panel),width/2,height-43,0xFFFF8888);
    }
    private String normalized(Field field){try{return MoneyValues.display(MoneyValues.parse(draft.text(field)));}catch(RuntimeException ex){return "Invalid";}}
    private void renderProviders(GuiGraphicsExtractor g){
        var s=context.leaderboardStatus().get(); int y=108;
        g.text(font,"donutsmpstats.net: "+s.primary().state()+detail(s.primary().detail()),left,y,0xFFE0E0E0); y+=18;
        g.text(font,"donutstats.org: "+s.secondary().state()+detail(s.secondary().detail()),left,y,0xFFE0E0E0); y+=18;
        g.text(font,"Cached money players: "+s.moneyPlayers(),left,y,0xFFE0E0E0); y+=18;
        g.text(font,"Economy-active players: "+s.economyPlayers(),left,y,0xFFE0E0E0); y+=18;
        String refreshed=s.lastRefresh()==0?"Never":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(s.lastRefresh()));
        g.text(font,"Last successful refresh: "+refreshed,left,y,0xFFAAAAAA);
    }
    private String detail(String value){return value==null||value.isBlank()?"":" — "+font.plainSubstrByWidth(value,panel/2);}
    private void renderAnalytics(GuiGraphicsExtractor g){
        int y=72; for(AnalyticsEngine.MethodStats s:context.targetingAnalytics().get()){
            String line=String.format(Locale.ROOT,"%s  Ads: %,d  Conv: %,d (%.1f%%)",s.method().label(),s.paymentsSent(),s.conversions(),s.conversionPercent());
            g.text(font,font.plainSubstrByWidth(line,panel),left,y,0xFFE0E0E0); y+=16;
            String sub=String.format(Locale.ROOT,"Attempts %,d • Offline %,d • Repeats blocked %,d • Profit/1000 %s",s.attempts(),s.offlineRejected(),s.repeatPrevented(),MoneyValues.display(s.profitPerThousand()));
            g.text(font,font.plainSubstrByWidth(sub,panel),left+8,y,0xFFAAAAAA); y+=24;
        }
    }
}
