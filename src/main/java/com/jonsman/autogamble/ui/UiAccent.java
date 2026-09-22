package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.AutoGambleConfig;

/** Keeps configurable highlights readable against the dark Minecraft settings background. */
public final class UiAccent {
    private UiAccent() {}
    public static int rgb(AutoGambleConfig config) {
        int rgb = Integer.parseInt(config.accentColor, 16);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        double light = .2126*r + .7152*g + .0722*b;
        if (light < 145) {
            r = (r + 255) / 2; g = (g + 255) / 2; b = (b + 255) / 2;
        }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
