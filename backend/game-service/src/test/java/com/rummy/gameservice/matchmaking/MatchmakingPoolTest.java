package com.rummy.gameservice.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingPoolTest {

    @Test
    void testPoolGetsItsOwnQueue() {
        MatchmakingTicket real = new MatchmakingTicket("T1", "P_a", "A", "POINTS_13", 80, 2, true);
        MatchmakingTicket test = new MatchmakingTicket("T2", "P_b", "B", "POINTS_13", 80, 2, true);
        test.setPool(MatchmakingController.TEST_POOL);

        assertEquals("POINTS_13:80:2", real.getQueueKey());
        assertNotEquals(real.getQueueKey(), test.getQueueKey());
    }

    @Test
    void clientCannotChooseItsPool() throws Exception {
        MatchmakingRequest request = new ObjectMapper()
                .readValue("{\"rulesetId\":\"POINTS_13\",\"pool\":\"test\"}", MatchmakingRequest.class);

        assertNull(request.getPool());
    }
}
