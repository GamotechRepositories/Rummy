package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WalletTransactionRepository extends MongoRepository<WalletTransactionDocument, String> {

    Optional<WalletTransactionDocument> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<WalletTransactionDocument> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    List<WalletTransactionDocument> findByGameId(String gameId);
}
