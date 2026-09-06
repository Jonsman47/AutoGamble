package com.jonsman.autogamble.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import java.util.function.DoubleConsumer;

public final class WinChanceSlider extends AbstractSliderButton {
    private final DoubleConsumer changed;
    private String label = "Win Chance: ";
    public WinChanceSlider(int x, int y, int width, double chance, DoubleConsumer changed, String label) {
        this(x, y, width, chance, changed); this.label = label; updateMessage();
    }
    public WinChanceSlider(int x, int y, int width, double chance, DoubleConsumer changed) {
        super(x, y, width, 20, Component.empty(), chance); this.changed = changed; updateMessage();
    }
    @Override protected void updateMessage() {
        setMessage(Component.literal(String.format(java.util.Locale.ROOT, "%s%.1f%%", label, Math.round(value * 1000) / 10.0)));
    }
    @Override protected void applyValue() { changed.accept(Math.round(value * 1000) / 1000.0); }
}
