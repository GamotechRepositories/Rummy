package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.GameEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameEventRepository extends MongoRepository<GameEventDocument, String> {
    List<GameEventDocument> findByGameIdOrderBySequenceAsc(String gameId);
}
