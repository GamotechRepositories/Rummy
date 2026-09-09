package com.rummy.gameservice.persistence.repository;

import com.rummy.gameservice.persistence.document.UserProfileDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends MongoRepository<UserProfileDocument, String> {
    Optional<UserProfileDocument> findByUserId(String userId);
}
