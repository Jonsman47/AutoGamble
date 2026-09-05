package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import java.util.List;

/** All calls occur on the client thread. Dispatch must revalidate connection and target. */
public interface AutoPayEnvironment {
    boolean connected();
    boolean inputBlocked();
    List<Candidate> eligiblePlayers();
    boolean dispatch(Candidate target, String amount);
}
