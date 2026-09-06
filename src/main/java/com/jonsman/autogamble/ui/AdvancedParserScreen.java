package com.jonsman.autogamble.ui;

import com.jonsman.autogamble.config.SettingsDraft;
import com.jonsman.autogamble.payment.RegexPaymentParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AdvancedParserScreen extends Screen {
    private final Screen parent;
    private final SettingsContext context;
    private final SettingsDraft draft;
    private String message = "", result = "Parser tests never gamble or send payments.";
    private int left, panel;
    private int enabledCount;
    public AdvancedParserScreen(Screen parent, SettingsContext context, SettingsDraft draft) {
        super(Component.literal("Incoming Payment Parser")); this.parent = parent; this.context = context; this.draft = draft;
    }
    @Override protected void init() {
        enabledCount = RegexPaymentParser.fromConfig(draft.working).enabledCount();
        panel = Math.min(420, width - 24); left = (width - panel) / 2;
        var box = addRenderableWidget(new EditBox(font, left, 106, panel, 20, Component.literal("Test Payment Message")));
        box.setMaxLength(1024); box.setValue(message); box.setResponder(s -> message = s);
        box.setTooltip(Tooltip.create(Component.literal("Paste the exact server payment message. Testing sends nothing.")));
        addRenderableWidget(Button.builder(Component.literal("Test Parser"), b -> result = ParserTestService.test(draft.working, message, context.localUsername().get()))
                .bounds(left, 134, panel / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Import Patterns from JSON"), b -> {
            try { draft.working.incomingPaymentPatterns = PatternConfigService.read(context.configs().path()); enabledCount = RegexPaymentParser.fromConfig(draft.working).enabledCount(); result = "Patterns imported into draft. Test, then Save & Done."; }
            catch (Exception e) { result = "Import rejected: check regex syntax and sender/amount groups."; }
        }).bounds(left + panel / 2 + 3, 134, panel / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        g.centeredText(font, enabledCount + " enabled patterns in draft", width / 2, 33, 0xFF99DDCC);
        g.textWithWordWrap(font, Component.literal("DonutSMP incoming format is built in. Import additional JSON patterns if needed. Test expanded K/M/B/T amounts below."), left, 51, panel, 0xFFBBBBBB);
        g.text(font, "Test Payment Message", left, 91, 0xFFE0E0E0);
        g.textWithWordWrap(font, Component.literal(result), left, 166, panel, 0xFFDDDDDD);
    }
}
