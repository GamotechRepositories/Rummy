package com.rummy.gameservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.event.CardDrawnEvent;
import com.rummy.engine.event.GameStartedEvent;
import com.rummy.engine.model.Card;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameEventStreamingIntegrationTest {

    private ObjectMapper objectMapper;
    private GameEventProducer producer;
    private GameEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        producer = new GameEventProducer(null, objectMapper, false);
        consumer = new GameEventConsumer(objectMapper);
    }

    @Test
    @DisplayName("Should format and dispatch game events asynchronously without throwing")
    void testGameEventPublishing() {
        com.rummy.engine.model.CardInstance joker = com.rummy.engine.model.CardInstance.of("j1", Card.of(Suit.HEARTS, Rank.SEVEN), 1);
        com.rummy.engine.model.CardInstance discard = com.rummy.engine.model.CardInstance.of("d1", Card.of(Suit.SPADES, Rank.ACE), 1);

        GameStartedEvent event = new GameStartedEvent(
                "EVT_1", "G_100", 1L,
                Instant.now(),
                joker,
                discard,
                List.of("USR_ALICE", "USR_BOB"),
                "USR_ALICE",
                Instant.now().plusSeconds(30)
        );

        producer.publishGameEvent("G_100", event);
        producer.publishAuditEvent("TBL_1", "DEAL_CARDS", event);

        assertThat(producer.isKafkaEnabled()).isFalse(); // local fallback mode
    }

    @Test
    @DisplayName("Consumer should parse incoming JSON game events and update metrics")
    void testConsumerProcessing() {
        String eventJson = """
                {
                    "eventId": "EVT_DRAW_1",
                    "gameId": "G_200",
                    "sequence": 2,
                    "playerId": "USR_ALICE",
                    "source": "OPEN_DECK",
                    "timestamp": "2026-09-09T10:00:00Z",
                    "eventType": "CARD_DRAWN"
                }
                """;

        consumer.consumeGameEvent(eventJson);
        assertThat(consumer.getConsumedEventCount()).isEqualTo(1L);
    }
}
