package com.rummy.gameservice.persistence;

import com.rummy.engine.event.CardDiscardedEvent;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.gameservice.controller.GameHistoryController;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class GamePersistenceIntegrationTest {

    @Autowired
    private GamePersistenceService persistenceService;

    @Autowired
    private GameHistoryController historyController;

    private String testPlayerId;
    private String testGameId;
    private String testTableId;

    @BeforeEach
    void setUp() {
        testPlayerId = "TEST_P_" + UUID.randomUUID().toString().substring(0, 8);
        testGameId = "TEST_G_" + UUID.randomUUID().toString().substring(0, 8);
        testTableId = "TEST_T_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Should persist game start, event audit log, and game completion to MongoDB")
    void testGamePersistenceLifecycle() {
        // 1. Prepare game state
        Deck deck = Deck.createStandard13CardDeck();
        PlayerState p1 = new PlayerState(testPlayerId, "RoyalAce", 0, false);
        PlayerState bot = new PlayerState("BOT_1", "RoyalBot", 1, true);

        GameState state = new GameState(testGameId, testTableId, "POINTS_13", "1.0.0", List.of(p1, bot), deck);
        state.setStatus(GameStatus.IN_PROGRESS);

        // 2. Record game started
        persistenceService.recordGameStarted(state, testTableId);

        // Wait for async write to persist GameDocument
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(persistenceService.getGame(testGameId)).isPresent();
            GameDocument gameDoc = persistenceService.getGame(testGameId).get();
            assertThat(gameDoc.getStatus()).isEqualTo(GameStatus.IN_PROGRESS.name());
            assertThat(gameDoc.getPlayers()).hasSize(2);
        });

        // 3. Record an audit event
        CardInstance card = new CardInstance("c-inst-1", Card.of(Suit.SPADES, Rank.ACE), 1);
        CardDiscardedEvent discardEvent = new CardDiscardedEvent(
                "evt-1", testGameId, 1L, Instant.now(), testPlayerId, card, 13
        );
        persistenceService.recordGameEventAsync(testGameId, discardEvent);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(persistenceService.getGameEvents(testGameId)).isNotEmpty();
            assertThat(persistenceService.getGameEvents(testGameId).get(0).getEventType())
                    .isEqualTo(CardDiscardedEvent.class.getSimpleName());
        });

        // 4. Conclude game and record finish
        p1.setScore(0); // Winner
        bot.setScore(45); // Loser penalty
        state.setStatus(GameStatus.COMPLETED);
        state.setWinnerPlayerId(testPlayerId);

        persistenceService.recordGameFinished(state, testTableId);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<GameResultDocument> results = persistenceService.getPlayerHistory(testPlayerId);
            assertThat(results).isNotEmpty();
            GameResultDocument result = results.get(0);
            assertThat(result.getGameId()).isEqualTo(testGameId);
            assertThat(result.isWon()).isTrue();
            assertThat(result.getFinalScore()).isEqualTo(0);

            UserProfileDocument profile = persistenceService.getUserProfile(testPlayerId).orElse(null);
            assertThat(profile).isNotNull();
            assertThat(profile.getGamesPlayed()).isEqualTo(1);
            assertThat(profile.getGamesWon()).isEqualTo(1);
            assertThat(profile.getVirtualPoints()).isGreaterThanOrEqualTo(10500L); // 10000 start + 500 win
        });

        // 5. Query REST history controller
        ResponseEntity<Map<String, Object>> response = historyController.getPlayerHistory(testPlayerId);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("playerId")).isEqualTo(testPlayerId);
        assertThat(body.get("winRate")).isEqualTo(100.0);
    }
}
