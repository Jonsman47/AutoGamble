package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.baltop.*;
import com.jonsman.autogamble.config.MoneyValues;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.util.*;

/** Dedicated local-data browser; the scan itself runs over client ticks after this screen closes. */
public final class ScannedBaltopScreen extends Screen {
    private final Screen parent; private final SettingsContext context;
    private int tab, offset, left, panel, accent; private BaltopSorting.Sort sort=BaltopSorting.Sort.RANK;
    private String search="", minimum="", maximum="", warning="";
    private List<BaltopEntry> visible=List.of();
    public ScannedBaltopScreen(Screen parent,SettingsContext context){super(Component.literal("Scanned Baltop"));this.parent=parent;this.context=context;}
    private Button button(String text,int x,int y,int width,Runnable action){return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,width,20).build());}
    @Override protected void init(){
        panel=Math.min(520,width-24);left=(width-panel)/2;accent=UiAccent.rgb(context.configs().snapshot());
        int tabWidth=panel/3;
        button("Scan Status",left,44,tabWidth-2,()->{tab=0;rebuildWidgets();}).active=tab!=0;
        button("Browse Players",left+tabWidth,44,tabWidth-2,()->{tab=1;rebuildWidgets();}).active=tab!=1;
        button("Targeting Debug",left+2*tabWidth,44,panel-2*tabWidth,()->{tab=2;rebuildWidgets();}).active=tab!=2;
        if(tab==0){
            button("Start / Resume Scan",left,72,panel/2-2,context.startBaltop());
            button("Pause Scan",left+panel/2+2,72,panel/2-2,context.pauseBaltop());
            button("Reset Baltop Scan",left,98,panel,()->minecraft.gui.setScreen(new ConfirmScreen(yes->{
                if(yes){if(!context.resetBaltop().getAsBoolean()){minecraft.gui.setScreen(this);warning="Could not reset baltop cache; see log.";}}
                else minecraft.gui.setScreen(this);
            },Component.literal("Reset scanned baltop data?"),Component.literal("Only baltop players and scan progress will be deleted. Restart from Page 1?"),
                    Component.literal("Reset and Restart"),Component.literal("Cancel"))));
        }else if(tab==1){
            EditBox find=addRenderableWidget(new EditBox(font,left,70,panel/2-4,20,Component.literal("Search player")));
            find.setHint(Component.literal("Search player..."));find.setMaxLength(32);find.setValue(search);find.setResponder(v->{search=v;refresh();});
            button("Sort: "+sort.name().replace('_',' '),left+panel/2+4,70,panel/2-4,()->{sort=BaltopSorting.Sort.values()[(sort.ordinal()+1)%BaltopSorting.Sort.values().length];refresh();rebuildWidgets();});
            EditBox min=addRenderableWidget(new EditBox(font,left,96,panel/2-4,20,Component.literal("Minimum balance")));
            min.setHint(Component.literal("Minimum balance"));min.setMaxLength(32);min.setValue(minimum);min.setResponder(v->{minimum=v;refresh();});
            EditBox max=addRenderableWidget(new EditBox(font,left+panel/2+4,96,panel/2-4,20,Component.literal("Maximum balance")));
            max.setHint(Component.literal("Maximum / no maximum"));max.setMaxLength(32);max.setValue(maximum);max.setResponder(v->{maximum=v;refresh();});
            button("Refresh / Re-sort",left,120,panel/3-2,this::refresh);
            button("▲",left+panel/3+2,120,panel/3-2,()->{offset=Math.max(0,offset-5);});
            button("▼",left+2*panel/3+2,120,panel/3-2,()->{offset=Math.min(Math.max(0,visible.size()-rows()),offset+5);});
            refresh();
        }
        button("Back",left,height-28,panel,()->minecraft.gui.setScreen(parent));
    }
    private int rows(){return Math.max(1,(height-192)/14);}
    private void refresh(){
        try{
            BigDecimal min=minimum.isBlank()?BigDecimal.ZERO:MoneyValues.parse(minimum);
            BigDecimal max=maximum.isBlank()?null:MoneyValues.parse(maximum);
            if(max!=null&&max.compareTo(min)<0)throw new IllegalArgumentException();
            visible=new ArrayList<>(BaltopDatabase.filter(context.baltopData().get().entries(),min,max,search));
            BaltopSorting.sort(visible,sort);offset=Math.clamp(offset,0,Math.max(0,visible.size()-rows()));warning="";
        }catch(RuntimeException ex){visible=List.of();warning="Invalid balance filter. Use e.g. 500M or 1.5T.";}
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(tab==1){offset=Math.clamp(offset-(int)Math.signum(dy)*3,0,Math.max(0,visible.size()-rows()));return true;}
        return super.mouseScrolled(x,y,dx,dy);
    }
    @Override public void tick(){if(tab==1 && minecraft.level!=null && minecraft.player!=null && minecraft.player.tickCount%40==0)refresh();}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        super.extractRenderState(g,mx,my,delta);g.centeredText(font,title,width/2,15,0xFFFFFFFF);
        var data=context.baltopData().get();var status=context.baltopStatus().get();
        g.centeredText(font,"Players "+data.entries().size()+"  •  Highest page "+data.highestPage()+"  •  "+status.state(),width/2,30,accent);
        if(tab==0){
            int y=130;String date=data.lastScan()==0?"Never":DateFormat.getDateTimeInstance().format(new Date(data.lastScan()));
            for(String line:new String[]{"Current page: "+status.currentPage()+"  •  Last parsed: "+status.lastParsed(),
                    String.format(Locale.ROOT,"Speed: %.1f pages/min  •  Duplicate entries: %,d",status.pagesPerMinute(),data.duplicates()),
                    "Next Page: "+(status.nextFound()?"slot "+status.nextSlot():"not found")+"  •  Duplicate pages: "+status.duplicatePages(),
                    "Parse failures: "+status.parseFailures()+"  •  Last update: "+date,
                    status.message(),data.warning()}){g.text(font,font.plainSubstrByWidth(line,panel),left,y,0xFFE0E0E0);y+=16;}
        }else if(tab==1){
            g.text(font,"Rank           Player                       Balance           Page",left,149,0xFFAAAAAA);
            for(int i=offset;i<Math.min(visible.size(),offset+rows());i++){
                BaltopEntry entry=visible.get(i);String line=String.format(Locale.ROOT,"%-8s %-18s %-14s %d",
                        entry.rank()==null?"—":"#"+entry.rank(),entry.username(),"$"+MoneyValues.display(entry.balance()),entry.page());
                g.text(font,font.plainSubstrByWidth(line,panel),left,164+(i-offset)*14,0xFFE0E0E0);
            }
            g.text(font,"Showing "+visible.size()+" matches • "+Math.min(visible.size(),offset+1)+"–"+Math.min(visible.size(),offset+rows()),left,height-43,0xFFAAAAAA);
        }else{
            var d=context.targetingDiagnostics().get();
            String[] a={"Method: "+d.method(),"Cached: "+d.scanned(),"Balance eligible: "+d.balanceEligible(),
                    "Local excluded: "+d.localExcluded(),"Recent excluded: "+d.recentExcluded(),
                    "Invalid/blocked: "+d.invalidExcluded()+"/"+d.blockedExcluded(),"Available: "+d.candidatesAvailable(),"Candidate checks: "+d.candidateChecks(),
                    "Suggestion requests: "+d.suggestionRequests()};
            String[] b={"Online matches: "+d.onlineMatches(),"Verify failures: "+d.verificationFailures(),
                    "Pay attempted: "+d.paymentsAttempted(),"Pay sent: "+d.paymentsSent(),
                    "Checking: "+d.currentCandidate(),"Last prefix: "+d.lastSuggestionPrefix(),"Rejected: "+d.lastRejectedUsername(),
                    "Reason: "+d.lastRejectionReason(),"Last success: "+d.lastSuccessfulTarget(),
                    "Since payment: "+(d.millisSinceLastPayment()<0?"Never":d.millisSinceLastPayment()/1000+"s"),
                    "Result: "+d.lastResult()};
            for(int i=0;i<a.length;i++)g.text(font,font.plainSubstrByWidth(a[i],panel/2-8),left,76+i*12,0xFFE0E0E0);
            for(int i=0;i<b.length;i++)g.text(font,font.plainSubstrByWidth(b[i],panel/2-8),left+panel/2+4,76+i*12,0xFFE0E0E0);
        }
        if(!warning.isBlank())g.centeredText(font,font.plainSubstrByWidth(warning,panel),width/2,height-43,0xFFFF8888);
    }
}
