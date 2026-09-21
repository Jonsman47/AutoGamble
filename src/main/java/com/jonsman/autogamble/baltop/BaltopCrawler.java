package com.jonsman.autogamble.baltop;

import org.slf4j.Logger;

/** Tick-driven GUI navigation. The Minecraft adapter owns all client-thread interactions. */
public final class BaltopCrawler {
    public enum State { IDLE, OPENING_BALTOP, WAITING_FOR_PAGE, PARSING_PAGE, WAITING_BEFORE_NEXT_CLICK, CLICKING_NEXT, FINISHED, PAUSED, ERROR }
    public interface Environment {
        boolean connected();
        /** A non-baltop screen is never replaced without explicit user action. */
        boolean unobstructed();
        boolean sendBaltop();
        BaltopParser.Page page();
        boolean clickNext(int slot);
        void debug(BaltopParser.Page page, BaltopParser.Parsed parsed);
    }
    public record Status(State state, int currentPage, int lastParsed, boolean nextFound, int nextSlot,
                         int parseFailures, long duplicatePages, double pagesPerMinute, String message, String title) {}
    private final BaltopDatabase database; private final Logger log;
    private State state = State.IDLE; private String message = "", title = "";
    private long stateSince, scanStartedNanos, duplicatePages;
    private int sessionPages;
    private int currentPage, lastParsed, nextSlot = -1, parseFailures;
    private boolean nextFound; private String lastFingerprint = "";
    private int speed = 1;
    public BaltopCrawler(BaltopDatabase database, Logger log) { this.database = database; this.log = log; }
    public Status status(long now) {
        double minutes = scanStartedNanos == 0 ? 0 : Math.max(.001,(now-scanStartedNanos)/60_000_000_000.0);
        return new Status(state,currentPage,lastParsed,nextFound,nextSlot,parseFailures,duplicatePages,
                scanStartedNanos==0?0:sessionPages/minutes,message,title);
    }
    public boolean running() { return switch(state) { case OPENING_BALTOP, WAITING_FOR_PAGE, PARSING_PAGE, WAITING_BEFORE_NEXT_CLICK, CLICKING_NEXT -> true; default -> false; }; }
    public void start(long now) {
        if (running()) return;
        state=State.OPENING_BALTOP; stateSince=now; scanStartedNanos=now;
        currentPage=0; sessionPages=0; nextSlot=-1; nextFound=false; lastFingerprint=""; message="Opening /baltop from page 1";
    }
    public void pause(String reason) { if (running()) { state=State.PAUSED; message=reason; database.flush(); } }
    public void stopSession() { pause("Disconnected; resume will reopen page 1"); }
    public boolean resetAndRestart(long now) {
        pause("Resetting scan");
        if (!database.reset()) { state=State.ERROR; message="Could not reset baltop cache"; return false; }
        lastParsed=parseFailures=0; duplicatePages=0; start(now); return true;
    }
    public void tick(long now, Environment env, int scanSpeed) {
        speed=Math.clamp(scanSpeed,0,2);
        if (!running()) return;
        if (!env.connected()) { pause("Disconnected; progress preserved"); return; }
        if (state==State.OPENING_BALTOP) {
            if (!env.unobstructed()) { if (elapsed(now)>5_000_000_000L) pause("Close the current screen, then resume"); return; }
            if (!env.sendBaltop()) { state=State.ERROR; message="Could not send /baltop"; return; }
            state=State.WAITING_FOR_PAGE; stateSince=now; return;
        }
        BaltopParser.Page page=env.page();
        if (page==null) {
            if (state==State.WAITING_FOR_PAGE && elapsed(now)<5_000_000_000L) return;
            pause("Baltop GUI closed or did not open; resume to retry"); return;
        }
        title=page.title();
        if (page.number()<1 || BaltopParser.pageNumber(page.title())!=page.number()) { pause("Unexpected baltop page title"); return; }
        switch (state) {
            case WAITING_FOR_PAGE -> {
                if (currentPage>0 && page.number()==currentPage) {
                    if (elapsed(now)>5_000_000_000L) pause("Page did not change after Next click");
                    return;
                }
                if (page.number()!=(currentPage==0?1:currentPage+1)) { pause("Page desync: expected "+(currentPage==0?1:currentPage+1)+", got "+page.number()); return; }
                currentPage=page.number(); state=State.PARSING_PAGE; stateSince=now;
            }
            case PARSING_PAGE -> {
                if (page.number()!=currentPage) { pause("Page changed during parse"); return; }
                if (elapsed(now)<parseDelay()) return;
                BaltopParser.Parsed parsed=BaltopParser.parse(page,System.currentTimeMillis());
                env.debug(page,parsed); parseFailures+=parsed.failures();
                if(parsed.entries().isEmpty()) { pause("No parseable players on page "+currentPage+"; inspect debug output"); return; }
                if(parsed.fingerprint().equals(lastFingerprint)) { duplicatePages++; pause("Duplicate page content; stopping to avoid repeated clicks"); return; }
                lastFingerprint=parsed.fingerprint(); lastParsed=parsed.entries().size(); sessionPages++;
                if(currentPage>database.snapshot().highestPage() && !database.recordPage(currentPage,parsed.entries(),System.currentTimeMillis())) {
                    pause("Could not persist page "+currentPage); return;
                }
                nextSlot=parsed.nextSlot(); nextFound=nextSlot>=0;
                if(!nextFound) { database.completed(); state=State.FINISHED; message="Final page scanned: "+currentPage; database.flush(); return; }
                state=State.WAITING_BEFORE_NEXT_CLICK; stateSince=now;
                message=currentPage<=database.snapshot().highestPage()?"Scanning page "+currentPage:"Advancing";
            }
            case WAITING_BEFORE_NEXT_CLICK -> {
                if(page.number()!=currentPage) { pause("Page changed before Next click"); return; }
                if(elapsed(now)<clickDelay()) return;
                state=State.CLICKING_NEXT;
                if(!env.clickNext(nextSlot)) { pause("Next Page item changed or click failed"); return; }
                state=State.WAITING_FOR_PAGE; stateSince=now; message="Waiting for page "+(currentPage+1);
            }
            default -> { }
        }
    }
    private long elapsed(long now) { return now-stateSince; }
    private long parseDelay() { return switch(speed) {case 0 -> 500_000_000L; case 2 -> 250_000_000L; default -> 350_000_000L;}; }
    private long clickDelay() { return switch(speed) {case 0 -> 750_000_000L; case 2 -> 350_000_000L; default -> 500_000_000L;}; }
}
