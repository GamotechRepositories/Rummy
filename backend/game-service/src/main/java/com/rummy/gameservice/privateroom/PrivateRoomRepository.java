package com.rummy.gameservice.privateroom;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrivateRoomRepository extends MongoRepository<PrivateRoomDocument, String> {

    Optional<PrivateRoomDocument> findByRoomCode(String roomCode);

    Optional<PrivateRoomDocument> findByTableId(String tableId);

    List<PrivateRoomDocument> findByStatusIn(List<String> statuses);

    List<PrivateRoomDocument> findByPlayersPlayerIdOrderByCreatedAtDesc(String playerId);
}
