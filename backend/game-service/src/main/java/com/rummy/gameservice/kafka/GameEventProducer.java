package com.rummy.gameservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.event.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 22: Asynchronous Kafka Event Streaming Producer.
 * Offloads game event streaming, history dispatch, and audit logging onto background threads.
 */
@Service
public class GameEventProducer {

    private static final Logger log = LoggerFactory.getLogger(GameEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean kafkaEnabled;
    private final ExecutorService asyncDispatcher = Executors.newFixedThreadPool(2);

    @Autowired
    public GameEventProducer(
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${rummy.kafka.enabled:false}") boolean kafkaEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.kafkaEnabled = kafkaEnabled;
        log.info("[Kafka] GameEventProducer initialized (Kafka enabled: {})", kafkaEnabled && kafkaTemplate != null);
    }

    /**
     * Publishes a GameEvent asynchronously to the game events topic.
     */
    public void publishGameEvent(String gameId, GameEvent event) {
        asyncDispatcher.submit(() -> {
            try {
                String payload = objectMapper.writeValueAsString(event);
                if (kafkaEnabled && kafkaTemplate != null) {
                    kafkaTemplate.send(KafkaTopicConstants.TOPIC_GAME_EVENTS, gameId, payload)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.warn("[Kafka] Failed to publish event {} for gameId={}: {}",
                                            event.getClass().getSimpleName(), gameId, ex.getMessage());
                                } else {
                                    log.debug("[Kafka] Sent event {} to topic={}",
                                            event.getClass().getSimpleName(), KafkaTopicConstants.TOPIC_GAME_EVENTS);
                                }
                            });
                } else {
                    log.debug("[Kafka-Local] Streamed event {} for gameId={}", event.getClass().getSimpleName(), gameId);
                }
            } catch (Exception e) {
                log.warn("[Kafka] Error serializing event for gameId={}: {}", gameId, e.getMessage());
            }
        });
    }

    /**
     * Publishes an audit event asynchronously for compliance and fairness.
     */
    public void publishAuditEvent(String tableId, String action, Object details) {
        asyncDispatcher.submit(() -> {
            try {
                String payload = objectMapper.writeValueAsString(details);
                if (kafkaEnabled && kafkaTemplate != null) {
                    kafkaTemplate.send(KafkaTopicConstants.TOPIC_AUDIT_EVENTS, tableId, payload);
                }
            } catch (Exception e) {
                log.warn("[Kafka] Error sending audit record for tableId={}: {}", tableId, e.getMessage());
            }
        });
    }

    public boolean isKafkaEnabled() {
        return kafkaEnabled && kafkaTemplate != null;
    }
}
