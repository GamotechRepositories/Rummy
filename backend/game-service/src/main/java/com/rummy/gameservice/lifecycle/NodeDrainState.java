package com.rummy.gameservice.lifecycle;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Whether this node has stopped taking new work. While draining, existing tables keep playing
 * but no new table, lobby or matchmaking group may start here.
 */
@Component
public class NodeDrainState {

    private volatile Instant drainingSince;

    public boolean isDraining() {
        return drainingSince != null;
    }

    public Instant drainingSince() {
        return drainingSince;
    }

    /** Returns true if this call started the drain. */
    synchronized boolean begin() {
        if (drainingSince != null) {
            return false;
        }
        drainingSince = Instant.now();
        return true;
    }

    synchronized void cancel() {
        drainingSince = null;
    }
}
