package com.rummy.gameservice.compliance;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResponsibleGamingRepository extends MongoRepository<ResponsibleGamingDocument, String> {
    Optional<ResponsibleGamingDocument> findByPlayerId(String playerId);
}
