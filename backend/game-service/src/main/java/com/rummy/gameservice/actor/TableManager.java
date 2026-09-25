package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.GameEngine;
import com.rummy.engine.model.Deck;
import com.rummy.engine.model.GameState;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RummyRules;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Registry and lifecycle manager for in-memory TableActors.
 */
@Service
public class TableManager {

    private final GameEngine engine = new GameEngine();
    private final ObjectMapper objectMapper;
    private final com.rummy.gameservice.persistence.GamePersistenceService persistenceService;
    private final com.rummy.gameservice.kafka.GameEventProducer eventProducer;
    private final com.rummy.gameservice.session.PlayerSessionService sessionService;
    private final com.rummy.gameservice.wallet.WalletService walletService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, TableActor> tables = new ConcurrentHashMap<>();

    @Autowired
    public TableManager(ObjectMapper objectMapper,
                        com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                        @Autowired(required = false) com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                        @Autowired(required = false) @Lazy com.rummy.gameservice.session.PlayerSessionService sessionService,
                        @Autowired(required = false) com.rummy.gameservice.wallet.WalletService walletService) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.persistenceService = persistenceService;
        this.eventProducer = eventProducer;
        this.sessionService = sessionService;
        this.walletService = walletService;
    }

    public TableManager(ObjectMapper objectMapper,
                        com.rummy.gameservice.persistence.GamePersistenceService persistenceService) {
        this(objectMapper, persistenceService, null, null, null);
    }

    public TableManager(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null, null);
    }

    public TableActor getOrCreateTable(String tableId, RummyRules rules) {
        return tables.computeIfAbsent(tableId, id -> {
            RummyRules activeRules = rules != null ? rules : new PointsRummyRules();
            String gameId = "G_" + UUID.randomUUID().toString().substring(0, 8);
            Deck deck = Deck.createMultiPackDeck(activeRules.getDeckCount(), activeRules.getPrintedJokersPerDeck(), new SecureRandom());
            GameState initialState = new GameState(gameId, id, activeRules.getRulesetId(),
                    activeRules.getRulesetVersion(), List.of(), deck);

            return new TableActor(id, initialState, activeRules, engine, objectMapper, scheduler,
                    persistenceService, eventProducer, sessionService, walletService);
        });
    }

    public Optional<TableActor> getTable(String tableId) {
        return Optional.ofNullable(tables.get(tableId));
    }

    public void removeTable(String tableId) {
        TableActor actor = tables.remove(tableId);
        if (actor != null) {
            actor.destroy();
        }
    }

    public int activeTableCount() {
        return tables.size();
    }

    @PreDestroy
    public void shutdown() {
        for (TableActor actor : tables.values()) {
            actor.destroy();
        }
        tables.clear();
        scheduler.shutdownNow();
    }
}
