package com.rummy.gameservice.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.event.GameEvent;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.PlayerState;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameDocument.PlayerSnapshot;
import com.rummy.gameservice.persistence.document.GameEventDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import com.rummy.gameservice.persistence.repository.GameEventRepository;
import com.rummy.gameservice.persistence.repository.GameRepository;
import com.rummy.gameservice.persistence.repository.GameResultRepository;
import com.rummy.gameservice.persistence.repository.UserProfileRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service orchestrating asynchronous MongoDB persistence for Rummy matches,
 * results, audit event trails, and user career statistics.
 *
 * Adheres to Section 22 ("Zero hot-path DB writes"): Writes are handled off the
 * in-memory game execution loop via an asynchronous thread pool.
 */
@Service
public class GamePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(GamePersistenceService.class);

    private final GameRepository gameRepository;
    private final GameResultRepository gameResultRepository;
    private final GameEventRepository gameEventRepository;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService asyncWriter = Executors.newFixedThreadPool(4);

    public GamePersistenceService(GameRepository gameRepository,
                                  GameResultRepository gameResultRepository,
                                  GameEventRepository gameEventRepository,
                                  UserProfileRepository userProfileRepository,
                                  ObjectMapper objectMapper) {
        this.gameRepository = Objects.requireNonNull(gameRepository);
        this.gameResultRepository = Objects.requireNonNull(gameResultRepository);
        this.gameEventRepository = Objects.requireNonNull(gameEventRepository);
        this.userProfileRepository = Objects.requireNonNull(userProfileRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Asynchronously records game initialization.
     */
    public void recordGameStarted(GameState state, String tableId) {
        asyncWriter.submit(() -> {
            try {
                String cutJokerStr = state.getCutJoker() != null
                        ? state.getCutJoker().getCard().toString()
                        : null;

                List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                        .map(p -> new PlayerSnapshot(
                                p.getPlayerId(),
                                p.getDisplayName(),
                                p.getSeatIndex(),
                                p.getScore(),
                                p.getStatus().name(),
                                p.isBot(),
                                false
                        ))
                        .toList();

                GameDocument doc = new GameDocument(
                        state.getGameId(),
                        tableId,
                        state.getRulesetId(),
                        state.getStatus().name(),
                        state.getCreatedAt(),
                        null,
                        null,
                        cutJokerStr,
                        playerSnapshots,
                        0,
                        0
                );

                gameRepository.save(doc);
                log.info("[Persistence] Recorded game start for gameId={}", state.getGameId());

                // Ensure non-bot user profiles exist
                for (PlayerState p : state.getPlayers()) {
                    if (!p.isBot()) {
                        getOrCreateUserProfile(p.getPlayerId(), p.getDisplayName());
                    }
                }
            } catch (Exception e) {
                log.error("[Persistence] Failed to record game start for gameId={}: {}", state.getGameId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Asynchronously records completed game, player results, and updates career statistics.
     */
    public void recordGameFinished(GameState state, String tableId) {
        asyncWriter.submit(() -> {
            try {
                Instant finishedAt = state.getFinishedAt() != null ? state.getFinishedAt() : Instant.now();
                long durationSeconds = Math.max(0, Duration.between(state.getCreatedAt(), finishedAt).toSeconds());
                String winnerId = state.getWinnerPlayerId();

                int totalTurns = state.getPlayers().stream().mapToInt(PlayerState::getTurnsCompleted).sum();

                List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                        .map(p -> new PlayerSnapshot(
                                p.getPlayerId(),
                                p.getDisplayName(),
                                p.getSeatIndex(),
                                p.getScore(),
                                p.getStatus().name(),
                                p.isBot(),
                                p.getPlayerId().equals(winnerId)
                        ))
                        .toList();

                String cutJokerStr = state.getCutJoker() != null
                        ? state.getCutJoker().getCard().toString()
                        : null;

                GameDocument doc = new GameDocument(
                        state.getGameId(),
                        tableId,
                        state.getRulesetId(),
                        state.getStatus().name(),
                        state.getCreatedAt(),
                        finishedAt,
                        winnerId,
                        cutJokerStr,
                        playerSnapshots,
                        totalTurns,
                        durationSeconds
                );
                gameRepository.save(doc);

                // Insert individual GameResultDocument records and update user profiles
                for (PlayerState p : state.getPlayers()) {
                    boolean won = p.getPlayerId().equals(winnerId);
                    GameResultDocument resultDoc = new GameResultDocument(
                            state.getGameId(),
                            tableId,
                            p.getPlayerId(),
                            p.getDisplayName(),
                            p.getScore(),
                            won,
                            p.getStatus().name(),
                            finishedAt
                    );
                    gameResultRepository.save(resultDoc);

                    // Update user career stats if human player
                    if (!p.isBot()) {
                        UserProfileDocument profile = getOrCreateUserProfile(p.getPlayerId(), p.getDisplayName());
                        profile.setGamesPlayed(profile.getGamesPlayed() + 1);
                        if (won) {
                            profile.setGamesWon(profile.getGamesWon() + 1);
                            profile.setVirtualPoints(profile.getVirtualPoints() + 500); // 500 points prize
                        } else {
                            // Penalty points deduction
                            profile.setVirtualPoints(Math.max(0, profile.getVirtualPoints() - p.getScore()));
                        }
                        profile.setTotalScore(profile.getTotalScore() + p.getScore());
                        profile.setUpdatedAt(Instant.now());
                        userProfileRepository.save(profile);
                    }
                }

                log.info("[Persistence] Completed game persistence for gameId={}, winner={}", state.getGameId(), winnerId);
            } catch (Exception e) {
                log.error("[Persistence] Failed to record game finished for gameId={}: {}", state.getGameId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Asynchronously records an immutable game audit event.
     */
    public void recordGameEventAsync(String gameId, GameEvent event) {
        asyncWriter.submit(() -> {
            try {
                String payloadJson = objectMapper.writeValueAsString(event);
                GameEventDocument doc = new GameEventDocument(
                        gameId,
                        event.sequence(),
                        event.eventType(),
                        null,
                        payloadJson,
                        event.timestamp()
                );
                gameEventRepository.save(doc);
            } catch (JsonProcessingException e) {
                log.error("[Persistence] Error serializing game event: {}", e.getMessage());
            } catch (Exception e) {
                log.error("[Persistence] Error saving game event doc: {}", e.getMessage());
            }
        });
    }

    public List<GameResultDocument> getPlayerHistory(String playerId) {
        return gameResultRepository.findByPlayerIdOrderByCreatedAtDesc(playerId);
    }

    public Optional<GameDocument> getGame(String gameId) {
        return gameRepository.findById(gameId);
    }

    public List<GameEventDocument> getGameEvents(String gameId) {
        return gameEventRepository.findByGameIdOrderBySequenceAsc(gameId);
    }

    public UserProfileDocument getOrCreateUserProfile(String userId, String displayName) {
        return userProfileRepository.findByUserId(userId).orElseGet(() -> {
            UserProfileDocument newProfile = new UserProfileDocument(
                    userId,
                    displayName != null ? displayName : userId,
                    10000L // default 10,000 virtual points
            );
            return userProfileRepository.save(newProfile);
        });
    }

    public Optional<UserProfileDocument> getUserProfile(String userId) {
        return userProfileRepository.findByUserId(userId);
    }

    @PreDestroy
    public void shutdown() {
        asyncWriter.shutdown();
    }
}
