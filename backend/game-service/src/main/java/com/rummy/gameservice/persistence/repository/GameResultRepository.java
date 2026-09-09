package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.GameResultDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameResultRepository extends MongoRepository<GameResultDocument, String> {
    List<GameResultDocument> findByPlayerIdOrderByCreatedAtDesc(String playerId);
    List<GameResultDocument> findByGameId(String gameId);
    long countByPlayerId(String playerId);
    long countByPlayerIdAndWonTrue(String playerId);
}
