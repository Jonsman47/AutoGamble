package com.jonsman.autogamble.targeting;

public enum TargetMethod {
    SMART_RANDOM("Smart Random"), MONEY_LEADERBOARD("Baltop"),
    ECONOMY_ACTIVE("Economy Active"), EXPERIMENTAL("Experimental");
    private final String label;
    TargetMethod(String label) { this.label = label; }
    public String label() { return label; }
}
