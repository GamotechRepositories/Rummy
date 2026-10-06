package com.rummy.gameservice.matchmaking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMatchmakingStoreTest {

    @Test
    @DisplayName("Finished tickets are pruned; queued ones are kept")
    void pruneFinishedTickets() {
        InMemoryMatchmakingStore store = new InMemoryMatchmakingStore();
        MatchmakingTicket queued = ticket("TKT_Q");
        MatchmakingTicket matched = ticket("TKT_M");
        matched.setStatus(MatchmakingTicket.Status.MATCHED);
        MatchmakingTicket cancelled = ticket("TKT_C");
        cancelled.setStatus(MatchmakingTicket.Status.CANCELLED);
        store.saveTicket(queued);
        store.saveTicket(matched);
        store.saveTicket(cancelled);

        assertThat(store.pruneFinishedTickets(Instant.now().minusSeconds(60))).isZero();
        assertThat(store.pruneFinishedTickets(Instant.now().plusSeconds(1))).isEqualTo(2);

        assertThat(store.findTicket("TKT_Q")).isPresent();
        assertThat(store.findTicket("TKT_M")).isEmpty();
        assertThat(store.findTicket("TKT_C")).isEmpty();
    }

    private static MatchmakingTicket ticket(String id) {
        return new MatchmakingTicket(id, "P_" + id, "Name", "INDIAN_POINTS", 0, 2, false);
    }
}
