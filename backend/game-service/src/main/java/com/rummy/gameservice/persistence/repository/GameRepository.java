package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.GameDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameRepository extends MongoRepository<GameDocument, String> {
    List<GameDocument> findByTableId(String tableId);
    List<GameDocument> findTop20ByOrderByStartedAtDesc();
}
