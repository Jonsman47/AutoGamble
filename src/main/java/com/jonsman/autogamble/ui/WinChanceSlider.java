package com.jonsman.autogamble.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import java.util.function.DoubleConsumer;

public final class WinChanceSlider extends AbstractSliderButton {
    private final DoubleConsumer changed;
    public WinChanceSlider(int x, int y, int width, double chance, DoubleConsumer changed) {
        super(x, y, width, 20, Component.empty(), chance); this.changed = changed; updateMessage();
    }
    @Override protected void updateMessage() {
        setMessage(Component.literal(String.format(java.util.Locale.ROOT, "Win Chance: %.1f%%", Math.round(value * 1000) / 10.0)));
    }
    @Override protected void applyValue() { changed.accept(Math.round(value * 1000) / 1000.0); }
}
