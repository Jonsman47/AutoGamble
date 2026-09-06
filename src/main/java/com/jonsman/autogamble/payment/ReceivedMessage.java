package com.jonsman.autogamble.payment;

/** Extraction boundary, with no dependency on Minecraft Component formatting. */
public record ReceivedMessage(String text, Channel channel) {
    public enum Channel { SYSTEM, OVERLAY, SERVER_CHAT, PLAYER_CHAT }
    public static String normalize(String text) {
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
