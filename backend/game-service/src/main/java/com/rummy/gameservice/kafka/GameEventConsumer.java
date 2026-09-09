package com.rummy.gameservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 22: Asynchronous downstream Kafka consumer for Game Analytics and Audit.
 */
@Service
public class GameEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GameEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final AtomicLong consumedEventCount = new AtomicLong(0);

    public GameEventConsumer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @KafkaListener(
            topics = KafkaTopicConstants.TOPIC_GAME_EVENTS,
            groupId = KafkaTopicConstants.GROUP_ANALYTICS,
            autoStartup = "${spring.kafka.consumer.auto-startup:false}"
    )
    public void consumeGameEvent(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            consumedEventCount.incrementAndGet();
            log.info("[KafkaConsumer] Received downstream game event (total consumed: {}): type={}",
                    consumedEventCount.get(), node.path("eventType").asText("UNKNOWN"));
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Failed to process incoming event: {}", e.getMessage());
        }
    }

    public long getConsumedEventCount() {
        return consumedEventCount.get();
    }
}
