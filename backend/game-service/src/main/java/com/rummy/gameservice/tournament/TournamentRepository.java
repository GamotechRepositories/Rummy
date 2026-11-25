package com.rummy.gameservice.tournament;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TournamentRepository extends MongoRepository<TournamentDocument, String> {

    Optional<TournamentDocument> findByTournamentId(String tournamentId);

    List<TournamentDocument> findByStatusInOrderByStartsAtAsc(List<String> statuses);

    List<TournamentDocument> findByStatus(String status);
}
