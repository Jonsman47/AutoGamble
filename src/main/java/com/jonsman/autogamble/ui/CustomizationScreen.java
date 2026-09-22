package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.QuickCommands;
import com.jonsman.autogamble.config.SettingsDraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Collections;

/** Small, user-initiated command palette and appearance editor. Changes share the parent draft. */
public final class CustomizationScreen extends Screen {
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private int tab, offset, left, panel;
    private String notice = "";
    private EditBox input;

    public CustomizationScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Customization"));
        this.parent = parent; this.context = context; this.draft = draft;
    }
    private Button button(String label, int x, int y, int width, Runnable click) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click.run()).bounds(x,y,width,20).build());
    }
    @Override protected void init() {
        panel = Math.min(440,width-24); left=(width-panel)/2;
        String[] tabs={"Quick Commands","Interface","Colors"};
        for (int i=0;i<tabs.length;i++) {
            int page=i, w=panel/3;
            Button b=button(tabs[i],left+i*w,45,w-2,()->{tab=page;rebuildWidgets();});
            b.active=i!=tab;
        }
        if (tab==0) commands();
        if (tab==1) interfaceOptions();
        if (tab==2) colors();
        button("Back to Settings",left,height-28,panel,()->minecraft.gui.setScreen(parent));
    }
    private void commands() {
        var commands=draft.working.quickCommands;
        int visible=Math.max(2,Math.min(5,(height-155)/27));
        offset=Math.clamp(offset,0,Math.max(0,commands.size()-visible));
        for(int i=0;i<visible && offset+i<commands.size();i++) {
            int index=offset+i, y=76+i*27;
            String command=commands.get(index);
            button(command,left,y,panel-141,()->run(command));
            button("↑",left+panel-137,y,32,()->move(index,-1)).active=index>0;
            button("↓",left+panel-102,y,32,()->move(index,1)).active=index<commands.size()-1;
            button("Remove",left+panel-67,y,67,()->{commands.remove(index);rebuildWidgets();});
        }
        int pagerY=76+visible*27;
        button("Previous",left,pagerY,panel/2-3,()->{offset=Math.max(0,offset-visible);rebuildWidgets();}).active=offset>0;
        button("Next",left+panel/2+3,pagerY,panel/2-3,()->{offset=Math.min(Math.max(0,commands.size()-visible),offset+visible);rebuildWidgets();}).active=offset+visible<commands.size();
        input=addRenderableWidget(new EditBox(font,left,height-55,panel-75,20,Component.literal("New quick command")));
        input.setHint(Component.literal("/command")); input.setMaxLength(80);
        button("Add",left+panel-70,height-55,70,()->{
            String value=input.getValue().trim();
            if (!QuickCommands.valid(value)) {notice="Enter a simple /command (no line breaks or special characters).";return;}
            if(commands.size()>=12){notice="Limit: 12 quick commands.";return;}
            if(!commands.contains(value)){commands.add(value);offset=Math.max(0,commands.size()-visible);rebuildWidgets();}
        });
    }
    private void move(int index,int direction){Collections.swap(draft.working.quickCommands,index,index+direction);rebuildWidgets();}
    private void run(String command) {
        if (minecraft.getConnection()==null) {notice="Join a server to run a quick command.";return;}
        if (command.equalsIgnoreCase("/settings gamble")) {
            minecraft.gui.setScreen(new AutoGambleSettingsScreen(null,context)); return;
        }
        minecraft.gui.setScreen(null);
        QuickCommands.executeClicked(command,minecraft.getConnection()::sendCommand);
    }
    private void interfaceOptions() {
        button("Menu spacing: "+(draft.working.compactMenu?"Compact":"Default"),left,80,panel,()->{draft.working.compactMenu=!draft.working.compactMenu;rebuildWidgets();});
        button("Less-used sections: "+(draft.working.showLessUsedSections?"Shown":"Hidden"),left,108,panel,()->{draft.working.showLessUsedSections=!draft.working.showLessUsedSections;rebuildWidgets();});
        button("Reset all customization to defaults",left,150,panel,()->{draft.working.resetCustomization();notice="Customization reset in draft. Save & Done to apply.";rebuildWidgets();});
    }
    private void colors() {
        String[] names={"Mint","Blue","Purple","Gold","Coral","White"};
        String[] values={"55DDBB","66AAFF","C49BFF","FFD166","FF8B80","EAEAEA"};
        for(int i=0;i<names.length;i++) {
            int x=left+(i%3)*(panel/3),y=76+(i/3)*28; String color=values[i];
            button(names[i],x,y,panel/3-3,()->{draft.working.accentColor=color;rebuildWidgets();});
        }
        input=addRenderableWidget(new EditBox(font,left,145,panel-100,20,Component.literal("RGB hex color")));
        input.setValue(draft.working.accentColor);input.setMaxLength(7);
        button("Apply hex",left+panel-95,145,95,()->{
            String hex=input.getValue().trim().replaceFirst("^#","");
            if(!hex.matches("(?i)[0-9a-f]{6}")){notice="Enter a six-digit RGB hex color.";return;}
            draft.working.accentColor=hex.toUpperCase(java.util.Locale.ROOT);rebuildWidgets();
        });
        button("Reset color",left,177,panel,()->{draft.working.accentColor="55DDBB";rebuildWidgets();});
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        super.extractRenderState(g,mx,my,delta);
        g.centeredText(font,title,width/2,15,0xFFFFFFFF);
        String subtitle = notice.isEmpty() ? (tab==2?"Current accent: #"+draft.working.accentColor:"Changes are saved with Save & Done in Settings") : notice;
        g.centeredText(font,font.plainSubstrByWidth(subtitle,panel),width/2,30,notice.isEmpty()?UiAccent.rgb(draft.working):0xFFFFBB66);
        if(tab==0 && draft.working.quickCommands.isEmpty())g.centeredText(font,"No quick commands configured",width/2,94,0xFFAAAAAA);
        if(tab==1)g.centeredText(font,"Compact spacing and optional advanced menu visibility",width/2,185,0xFFAAAAAA);
    }
}
