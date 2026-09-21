package com.jonsman.autogamble.baltop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import java.util.*;

/** Maps a live inventory snapshot into pure crawler input and uses vanilla menu clicks. */
public final class MinecraftBaltopEnvironment implements BaltopCrawler.Environment {
    private final Minecraft client; private final Logger log;
    private boolean debug;
    public MinecraftBaltopEnvironment(Minecraft client, Logger log) { this.client=client; this.log=log; }
    public void debug(boolean enabled) { debug=enabled; }
    @Override public boolean connected() { return client.player!=null && client.getConnection()!=null && client.getConnection().getConnection().isConnected(); }
    @Override public boolean unobstructed() { return client.gui.screen()==null; }
    @Override public boolean sendBaltop() {
        if(!connected() || !client.isSameThread() || !unobstructed()) return false;
        try { client.getConnection().sendCommand("baltop"); return true; }
        catch(RuntimeException ex) { log.warn("[AutoGamble] Could not open /baltop",ex); return false; }
    }
    @Override public BaltopParser.Page page() {
        if(!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof ChestMenu chest)) return null;
        String title=screen.getTitle().getString(); int number=BaltopParser.pageNumber(title);
        if(number<1) return null;
        int size=chest.getRowCount()*9; List<BaltopParser.Item> items=new ArrayList<>();
        for(int slot=0;slot<size && slot<chest.slots.size();slot++) {
            ItemStack stack=chest.getSlot(slot).getItem(); if(stack.isEmpty()) continue;
            var lore=stack.get(DataComponents.LORE);
            List<String> lines=lore==null?List.of():lore.lines().stream().map(c->c.getString()).toList();
            items.add(new BaltopParser.Item(slot,BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getHoverName().getString(),lines,stack.getComponents().toString()));
        }
        return new BaltopParser.Page(number,items,size,title);
    }
    @Override public boolean clickNext(int slot) {
        BaltopParser.Page current=page();
        if(current==null || slot<0 || slot>=current.inventorySlots() || current.items().stream().noneMatch(i->i.slot()==slot && BaltopParser.clean(i.name()).toLowerCase(Locale.ROOT).contains("next page"))) return false;
        if(!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || client.gameMode==null || !client.isSameThread()) return false;
        try { client.gameMode.handleContainerInput(screen.getMenu().containerId,slot,0,ContainerInput.PICKUP,client.player); return true; }
        catch(RuntimeException ex) { log.warn("[AutoGamble] Baltop Next Page click failed",ex); return false; }
    }
    @Override public void debug(BaltopParser.Page page,BaltopParser.Parsed parsed) {
        if(!debug) return;
        log.info("[AutoGamble] Baltop title='{}' page={} entries={} failures={} nextSlot={}",page.title(),page.number(),parsed.entries().size(),parsed.failures(),parsed.nextSlot());
        for(BaltopParser.Item item:page.items()) log.info("[AutoGamble] Baltop slot={} item={} name='{}' lore={} components={}",
                item.slot(),item.itemId(),item.name(),item.lore(),item.components());
    }
}
