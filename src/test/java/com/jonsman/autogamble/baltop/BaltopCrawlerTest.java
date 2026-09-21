package com.jonsman.autogamble.baltop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BaltopCrawlerTest {
    @TempDir Path folder;
    private static class Fake implements BaltopCrawler.Environment {
        int page, opens, clicks; boolean connected=true, obstructed;
        @Override public boolean connected(){return connected;}
        @Override public boolean unobstructed(){return !obstructed;}
        @Override public boolean sendBaltop(){opens++;page=1;return true;}
        @Override public BaltopParser.Page page(){
            if(page==0 || obstructed)return null;
            return new BaltopParser.Page(page,List.of(
                    new BaltopParser.Item(1,"head",page==1?"#1 Bob":"#2 Alice",List.of("Balance: $1B"),""),
                    new BaltopParser.Item(42,"arrow","Next Page",List.of(),"")),54,"Most Money (Page "+page+")");
        }
        @Override public boolean clickNext(int slot){assertEquals(42,slot);clicks++;page++;return true;}
        @Override public void debug(BaltopParser.Page p,BaltopParser.Parsed parsed){}
    }
    @Test void scanPausesOnCloseAndResumesThroughPreviousPages() {
        try(var db=new BaltopDatabase(folder.resolve("cache.json"),LoggerFactory.getLogger("test"))){
            var crawler=new BaltopCrawler(db,LoggerFactory.getLogger("test"));var fake=new Fake();
            long t=1_000_000_000L;crawler.start(t);crawler.tick(t,fake,2);assertEquals(1,fake.opens);
            crawler.tick(t+1,fake,2);crawler.tick(t+300_000_001L,fake,2);
            assertEquals(1,db.snapshot().highestPage());
            fake.obstructed=true;crawler.tick(t+400_000_000L,fake,2);
            assertEquals(BaltopCrawler.State.PAUSED,crawler.status(t+400_000_000L).state());
            fake.obstructed=false;t+=1_000_000_000L;crawler.start(t);crawler.tick(t,fake,2);
            crawler.tick(t+1,fake,2);crawler.tick(t+300_000_001L,fake,2);
            crawler.tick(t+700_000_002L,fake,2);assertEquals(2,fake.opens);assertEquals(1,fake.clicks);
            crawler.tick(t+700_000_003L,fake,2);crawler.tick(t+1_000_000_004L,fake,2);
            assertEquals(2,db.snapshot().highestPage());assertEquals(2,db.snapshot().entries().size());
        }
    }
    @Test void pageTimeoutDoesNotClickRepeatedly() {
        try(var db=new BaltopDatabase(folder.resolve("cache.json"),LoggerFactory.getLogger("test"))){
            var crawler=new BaltopCrawler(db,LoggerFactory.getLogger("test"));var fake=new Fake(){@Override public boolean clickNext(int slot){clicks++;return true;}};
            long t=1_000_000_000L;crawler.start(t);crawler.tick(t,fake,2);crawler.tick(t+1,fake,2);
            crawler.tick(t+300_000_001L,fake,2);crawler.tick(t+700_000_002L,fake,2);
            crawler.tick(t+6_000_000_003L,fake,2);
            assertEquals(1,fake.clicks);assertEquals(BaltopCrawler.State.PAUSED,crawler.status(t+6_000_000_003L).state());
        }
    }
}
