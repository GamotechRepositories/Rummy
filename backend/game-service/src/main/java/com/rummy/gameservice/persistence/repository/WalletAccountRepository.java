package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletAccountRepository extends MongoRepository<WalletAccountDocument, String> {

    Optional<WalletAccountDocument> findByPlayerId(String playerId);

    boolean existsByPlayerId(String playerId);
}
