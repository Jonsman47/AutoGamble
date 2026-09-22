package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.config.SettingsDraft.Field;
import com.jonsman.autogamble.history.AnalyticsEngine;
import com.jonsman.autogamble.targeting.ExperimentalFeatures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.text.DateFormat;
import java.util.*;

/** Compact editor for weighted in-game targeting and scanned-baltop filters. */
public final class TargetingSettingsScreen extends Screen {
    private static final String[] PAGES = {"Weights", "Money", "Checks", "Analytics"};
    private final Screen parent; private final SettingsContext context; private final SettingsDraft draft;
    private final Map<Field, EditBox> fields = new EnumMap<>(Field.class);
    private int page, panel, left, row; private String error = "", notice = ""; private Button save;
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
                button("Smart Random: active 100% (legacy)", left, row, panel, this::comingSoon); row += 24;
                button("Baltop payments: coming soon", left, row, panel, this::comingSoon); row += 24;
                button("Edit targeting percentages: coming soon", left, row, panel, this::comingSoon); row += 24;
                button("Scanned Baltop…", left, row, panel, () -> minecraft.gui.setScreen(new ScannedBaltopScreen(this,context)));
            }
            case 1 -> {
                button("Baltop minimum: "+MoneyValues.display(draft.working.leaderboardMinimumBalance)+" (inactive)",left,row,panel,this::comingSoon);row+=24;
                button("Baltop maximum: "+MoneyValues.display(draft.working.leaderboardMaximumBalance)+" (inactive)",left,row,panel,this::comingSoon);row+=24;
                int fifth = panel / 5;
                String[] labels = {"100M+", "500M+", "1B+", "10B+", "100B+"};
                for (int i = 0; i < labels.length; i++) button(labels[i], left + i*fifth, row + 4, fifth-2, this::comingSoon);
            }
            case 2 -> {
                number(Field.BALTOP_SPEED,"0 Safe, 1 Normal, 2 Fast. Fast is still rate-limited.");
                button("Baltop recipient checks: coming soon",left,row,panel,this::comingSoon);
            }
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
    public static String unavailablePaymentMessage() { return ExperimentalFeatures.UNAVAILABLE_MESSAGE; }
    private void comingSoon() { notice=unavailablePaymentMessage(); }
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
        g.centeredText(font,"Active payments use the 1.2.4 random /pay prefix method",width/2,30,UiAccent.rgb(draft.working));
        fields.forEach((field,box)->g.text(font,field.label,left,box.getY()+6,0xFFE0E0E0));
        if(page==0)g.centeredText(font,"Saved experimental split: "+draft.working.smartRandomWeight+"% / "+draft.working.moneyLeaderboardWeight+"% (inactive)",width/2,184,0xFFAAAAAA);
        if(page==3) renderAnalytics(g);
        if(!error.isEmpty())g.centeredText(font,font.plainSubstrByWidth(error,panel),width/2,height-43,0xFFFF8888);
        else if(!notice.isEmpty())g.centeredText(font,font.plainSubstrByWidth(notice,panel),width/2,height-43,0xFFFFBB66);
    }
    private void renderAnalytics(GuiGraphicsExtractor g){
        int y=72; for(AnalyticsEngine.MethodStats s:context.targetingAnalytics().get()){
            if(s.method()!=com.jonsman.autogamble.targeting.TargetMethod.SMART_RANDOM && s.method()!=com.jonsman.autogamble.targeting.TargetMethod.MONEY_LEADERBOARD) continue;
            String line=String.format(Locale.ROOT,"%s  Ads: %,d  Conv: %,d (%.1f%%)",s.method().label(),s.paymentsSent(),s.conversions(),s.conversionPercent());
            g.text(font,font.plainSubstrByWidth(line,panel),left,y,0xFFE0E0E0); y+=16;
            String sub=String.format(Locale.ROOT,"Attempts %,d • Offline %,d • Repeats blocked %,d • Profit/1000 %s",s.attempts(),s.offlineRejected(),s.repeatPrevented(),MoneyValues.display(s.profitPerThousand()));
            g.text(font,font.plainSubstrByWidth(sub,panel),left+8,y,0xFFAAAAAA); y+=24;
        }
    }
}
