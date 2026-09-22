package com.jonsman.autogamble;

import com.jonsman.autogamble.baltop.BaltopEntry;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.payment.EligibleRecipientQueue;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EligibleRecipientQueueTest {
    private static Candidate player(String name) { return new Candidate(null,name); }
    private static BaltopEntry balance(String name, String amount, long seen) {
        return new BaltopEntry(name,new BigDecimal(amount),null,1,seen);
    }
    @Test void filtersHundredMillionCandidatesInOneBatchAndSkipsUnknown() {
        var queue=new EligibleRecipientQueue();
        var data=Map.of("Rich",balance("Rich","100000000",1000),"Low",balance("Low","99999999",1000));
        var result=queue.addApproved(List.of(player("Low"),player("Unknown"),player("Rich")),
                new BigDecimal("100000000"),data::get,0,1000,10000);
        assertEquals(new EligibleRecipientQueue.Refill(3,1),result);
        assertEquals(List.of(player("Rich")),queue.snapshot(0));
    }
    @Test void deduplicatesBoundsAndRefillsAfterItBecomesLow() {
        var queue=new EligibleRecipientQueue();
        var many=new ArrayList<Candidate>();var data=new HashMap<String,BaltopEntry>();
        for(int i=0;i<40;i++){String name="Player"+i;many.add(player(name));data.put(name,balance(name,"200000000",1000));}
        many.add(player("pLaYeR0"));
        var result=queue.addApproved(many,new BigDecimal("100000000"),data::get,0,1000,10000);
        assertEquals(20,result.accepted()); assertEquals(20,queue.snapshot(0).size());
        for(int i=0;i<15;i++)queue.paid("Player"+i);
        assertTrue(queue.low(0));
        result=queue.addApproved(many,new BigDecimal("100000000"),data::get,1,1000,10000);
        assertEquals(15,result.accepted());assertEquals(20,queue.snapshot(1).size());
    }
    @Test void staleEvidenceAndQueueExpiryRequireFreshDiscovery() {
        var queue=new EligibleRecipientQueue();
        var data=Map.of("Rich",balance("Rich","100000000",1000));
        assertEquals(0,queue.addApproved(List.of(player("Rich")),BigDecimal.ONE,data::get,0,12000,10000).accepted());
        assertEquals(1,queue.addApproved(List.of(player("Rich")),BigDecimal.ONE,data::get,0,1000,10000).accepted());
        assertTrue(queue.snapshot(EligibleRecipientQueue.ENTRY_TTL_NANOS).isEmpty());
        assertTrue(queue.needsRefill(EligibleRecipientQueue.ENTRY_TTL_NANOS));
    }
    @Test void avoidsImmediateRepeatWhenAnotherRecipientIsReady() {
        var queue=new EligibleRecipientQueue();queue.offer(player("Bob"),0);queue.offer(player("Alex"),0);
        assertEquals("Bob",queue.next(0,false,n->false).orElseThrow().username());
        queue.paid("Bob");queue.offer(player("bOb"),1);
        assertEquals("Alex",queue.next(1,false,n->false).orElseThrow().username());
        queue.clear();assertTrue(queue.next(2,false,n->false).isEmpty());
    }
}
