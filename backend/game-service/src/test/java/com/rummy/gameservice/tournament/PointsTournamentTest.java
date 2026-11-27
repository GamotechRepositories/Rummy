package com.rummy.gameservice.tournament;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.GameStatus;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.security.VariantAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PointsTournamentTest {

    private TableManager tableManager;
    private final List<TournamentDocument> store = new CopyOnWriteArrayList<>();
    private TournamentService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tableManager = new TableManager(mapper, null, null, null, null);
        store.clear();

        TournamentRepository repo = (TournamentRepository) Proxy.newProxyInstance(
                TournamentRepository.class.getClassLoader(),
                new Class<?>[]{TournamentRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByStatusInOrderByStartsAtAsc".equals(name)) {
                        @SuppressWarnings("unchecked")
                        List<String> statuses = (List<String>) args[0];
                        return store.stream().filter(t -> statuses.contains(t.getStatus())).toList();
                    }
                    if ("findByStatus".equals(name)) {
                        String status = (String) args[0];
                        return store.stream().filter(t -> status.equals(t.getStatus())).toList();
                    }
                    if ("findByTournamentId".equals(name)) {
                        String id = (String) args[0];
                        return store.stream().filter(t -> id.equals(t.getTournamentId())).findFirst();
                    }
                    if ("save".equals(name)) {
                        TournamentDocument doc = (TournamentDocument) args[0];
                        if (!store.contains(doc)) {
                            store.add(doc);
                        }
                        return doc;
                    }
                    return null;
                }
        );

        service = new TournamentService(repo, tableManager, null, null);
    }

    @AfterEach
    void tearDown() {
        tableManager.shutdown();
    }

    private static TournamentDocument makeTournament(String id, String variant, String status) {
        TournamentDocument t = new TournamentDocument();
        t.setTournamentId(id);
        t.setTitle("Test " + variant);
        t.setVariant(variant);
        t.setStatus(status);
        t.setMinPlayers(2);
        t.setMaxPlayers(6);
        t.setFixedPrizeRs(500.0);
        return t;
    }

    @Test
    @DisplayName("listPublicTournaments filters by variant when specified")
    void listFiltersByVariant() {
        TournamentDocument pts = makeTournament("TRN-PTS", "POINTS_13", "REGISTERING");
        TournamentDocument dls = makeTournament("TRN-DLS", "DEALS_3", "REGISTERING");
        TournamentDocument legacyNull = makeTournament("TRN-LEGACY", null, "REGISTERING");
        store.add(pts);
        store.add(dls);
        store.add(legacyNull);

        List<Map<String, Object>> onlyPoints = service.listPublicTournaments("P1", "POINTS_13");
        assertThat(onlyPoints).hasSize(1);
        assertThat(onlyPoints.get(0).get("tournamentId")).isEqualTo("TRN-PTS");

        List<Map<String, Object>> onlyDeals = service.listPublicTournaments("P1", "DEALS_3");
        assertThat(onlyDeals).hasSize(2);
        assertThat(onlyDeals).extracting(m -> m.get("tournamentId")).containsExactly("TRN-DLS", "TRN-LEGACY");

        List<Map<String, Object>> all = service.listPublicTournaments("P1", null);
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("POINTS_13 tournament uses single-deal rules and completes after 1 hand")
    void pointsTournamentCompletesInSingleDeal() {
        TournamentDocument t = makeTournament("TRN-PTS-1", "POINTS_13", "STARTING");
        t.setCheckInEndsAt(Instant.now().minusSeconds(5));

        for (int i = 1; i <= 2; i++) {
            TournamentDocument.Registration reg = new TournamentDocument.Registration();
            reg.setPlayerId("P" + i);
            reg.setDisplayName("Player " + i);
            t.getRegistrations().add(reg);
            t.getCheckedInPlayerIds().add("P" + i);
        }
        store.add(t);

        // Start Round 1
        service.tickTournaments();

        assertThat(t.getStatus()).isEqualTo("RUNNING");
        assertThat(t.getRounds()).hasSize(1);
        String tableId = t.getRounds().get(0).getTableIds().get(0);
        TableActor actor = tableManager.getTable(tableId).orElseThrow();
        assertThat(actor.getState().getRulesetId()).isEqualTo("POINTS_13");
        actor.processCommand(
                new StartGameCommand("start-1", actor.getState().getGameId(), "P1", Instant.now()),
                "start-1"
        );
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        // One player drops -> single-deal Point Rummy hand ends immediately and sets tournamentWinnerId
        String loserId = actor.getState().getTurnState().getCurrentPlayerId();
        String expectedWinnerId = loserId.equals("P1") ? "P2" : "P1";
        actor.processCommand(
                new DropCommand("drop-1", actor.getState().getGameId(), loserId, Instant.now()),
                "drop-1"
        );

        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(actor.getTournamentWinnerId()).isEqualTo(expectedWinnerId);

        // Next tick finalizes tournament immediately (no 2nd/3rd deal)
        service.tickTournaments();

        assertThat(t.getStatus()).isEqualTo("COMPLETED");
        assertThat(t.getFinalRankings()).hasSize(2);
        assertThat(t.getFinalRankings().get(0).getRank()).isEqualTo(1);
        assertThat(t.getFinalRankings().get(0).getPlayerId()).isEqualTo(expectedWinnerId);
        assertThat(t.getFinalRankings().get(0).getPrizeRs()).isEqualTo(500.0);
        assertThat(t.getFinalRankings().get(1).getRank()).isEqualTo(2);
        assertThat(t.getFinalRankings().get(1).getPlayerId()).isEqualTo(loserId);
        assertThat(t.getFinalRankings().get(1).getPrizeRs()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("12-player POINTS_13 tournament advances Round 1 winners + lowest-penalty wildcards to Round 2 Final")
    void multiRoundPointsTournamentAdvancesAndFinalizes() {
        TournamentDocument t = makeTournament("TRN-PTS-12", "POINTS_13", "STARTING");
        t.setMinPlayers(6);
        t.setMaxPlayers(12);
        t.setFixedPrizeRs(1000.0);
        t.setCheckInEndsAt(Instant.now().minusSeconds(5));

        for (int i = 1; i <= 12; i++) {
            TournamentDocument.Registration reg = new TournamentDocument.Registration();
            reg.setPlayerId("P" + i);
            reg.setDisplayName("Player " + i);
            t.getRegistrations().add(reg);
            t.getCheckedInPlayerIds().add("P" + i);
        }
        store.add(t);

        // Start Round 1 (12 players -> 2 tables of 6)
        service.tickTournaments();
        assertThat(t.getStatus()).isEqualTo("RUNNING");
        assertThat(t.getRounds()).hasSize(1);
        assertThat(t.getRounds().get(0).getTableIds()).hasSize(2);

        for (String tid : t.getRounds().get(0).getTableIds()) {
            TableActor actor = tableManager.getTable(tid).orElseThrow();
            assertThat(actor.getState().getRulesetId()).isEqualTo("POINTS_13");
            String firstPlayer = actor.getState().getPlayers().get(0).getPlayerId();
            actor.processCommand(new StartGameCommand("s-" + tid, actor.getState().getGameId(), firstPlayer, Instant.now()), "s-" + tid);
            int guard = 0;
            while (actor.getState().getStatus() == GameStatus.IN_PROGRESS && guard++ < 10) {
                String curr = actor.getState().getTurnState().getCurrentPlayerId();
                actor.processCommand(new DropCommand("d-" + tid + "-" + guard, actor.getState().getGameId(), curr, Instant.now()), "d-" + tid + "-" + guard);
            }
            assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
            assertThat(actor.getTournamentWinnerId()).isNotBlank();
        }

        // Advance to Round 2 Final Table (2 table winners + 4 wildcards = 6 finalists)
        service.tickTournaments();
        assertThat(t.getStatus()).isEqualTo("RUNNING");
        assertThat(t.getRounds()).hasSize(2);
        assertThat(t.getRounds().get(0).getStatus()).isEqualTo("DONE");
        assertThat(t.getRounds().get(0).getQualifiedPlayerIds()).hasSize(6);
        assertThat(t.getRounds().get(1).getTableIds()).hasSize(1);

        String finalTableId = t.getRounds().get(1).getTableIds().get(0);
        TableActor finalActor = tableManager.getTable(finalTableId).orElseThrow();
        String finalFirst = finalActor.getState().getPlayers().get(0).getPlayerId();
        finalActor.processCommand(new StartGameCommand("s-final", finalActor.getState().getGameId(), finalFirst, Instant.now()), "s-final");
        int guard = 0;
        while (finalActor.getState().getStatus() == GameStatus.IN_PROGRESS && guard++ < 10) {
            String curr = finalActor.getState().getTurnState().getCurrentPlayerId();
            finalActor.processCommand(new DropCommand("d-final-" + guard, finalActor.getState().getGameId(), curr, Instant.now()), "d-final-" + guard);
        }

        // Finalize tournament
        service.tickTournaments();
        assertThat(t.getStatus()).isEqualTo("COMPLETED");
        assertThat(t.getFinalRankings()).hasSize(6);
        assertThat(t.getFinalRankings().get(0).getPlayerId()).isEqualTo(finalActor.getTournamentWinnerId());
        assertThat(t.getFinalRankings().get(0).getPrizeRs()).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("listMyTournaments returns registered tournaments beyond 7-day cutoff and filters by variant CSV")
    void listMyTournamentsIncludesOldCompletedAndFiltersVariants() {
        TournamentDocument oldPts = makeTournament("TRN-OLD-PTS", "POINTS_13", "COMPLETED");
        oldPts.setStartsAt(Instant.now().minusSeconds(30L * 24 * 3600));
        TournamentDocument.Registration r1 = new TournamentDocument.Registration();
        r1.setPlayerId("P1");
        r1.setDisplayName("Player One");
        oldPts.getRegistrations().add(r1);
        oldPts.setFinalRankings(List.of(new TournamentDocument.FinalRanking(1, "P1", "Player One", 500.0)));

        TournamentDocument dls = makeTournament("TRN-DLS", "DEALS_3", "CANCELLED");
        dls.setStartsAt(Instant.now().minusSeconds(60));
        TournamentDocument.Registration r2 = new TournamentDocument.Registration();
        r2.setPlayerId("P1");
        r2.setDisplayName("Player One");
        dls.getRegistrations().add(r2);

        TournamentDocument otherPlayerPts = makeTournament("TRN-OTHER", "POINTS_13", "COMPLETED");
        TournamentDocument.Registration rOther = new TournamentDocument.Registration();
        rOther.setPlayerId("P2");
        rOther.setDisplayName("Other");
        otherPlayerPts.getRegistrations().add(rOther);

        store.add(oldPts);
        store.add(dls);
        store.add(otherPlayerPts);

        List<Map<String, Object>> ptsOnly = service.listMyTournaments("P1", "POINTS_13");
        assertThat(ptsOnly).hasSize(1);
        assertThat(ptsOnly.get(0).get("tournamentId")).isEqualTo("TRN-OLD-PTS");
        assertThat(ptsOnly.get(0).get("myRank")).isEqualTo(1);
        assertThat(ptsOnly.get(0).get("myPrizeRs")).isEqualTo(500.0);

        List<Map<String, Object>> allMine = service.listMyTournaments("P1", null);
        assertThat(allMine).hasSize(2);
        assertThat(allMine.get(0).get("tournamentId")).isEqualTo("TRN-DLS");
        assertThat(allMine.get(1).get("tournamentId")).isEqualTo("TRN-OLD-PTS");
    }

    @Test
    @DisplayName("TournamentController checkIn and byTable enforce VariantAccessPolicy")
    void controllerCheckInAndByTableEnforceVariantPolicy() {
        TournamentDocument t = makeTournament("TRN-PTS-CHK", "POINTS_13", "STARTING");
        t.setCheckInEndsAt(Instant.now().plusSeconds(120));
        TournamentDocument.Registration reg = new TournamentDocument.Registration();
        reg.setPlayerId("P1");
        reg.setDisplayName("Player One");
        t.getRegistrations().add(reg);
        store.add(t);

        VariantAccessPolicy policy = new VariantAccessPolicy(
                "https://points.example.com=POINTS_13;https://deals.example.com=DEALS_RUMMY,DEALS_2,DEALS_3",
                ""
        );
        TournamentController controller = new TournamentController(service, policy);

        MockHttpServletRequest dealsReq = new MockHttpServletRequest();
        dealsReq.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");
        dealsReq.addHeader("Origin", "https://deals.example.com");

        ResponseEntity<?> deniedCheckIn = controller.checkIn(dealsReq, "TRN-PTS-CHK", Map.of());
        assertThat(deniedCheckIn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<?> deniedByTable = controller.byTable(dealsReq, "TBL_TRN_TRN-PTS-CHK_R1_1", "P1");
        assertThat(deniedByTable.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        MockHttpServletRequest pointsReq = new MockHttpServletRequest();
        pointsReq.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");
        pointsReq.addHeader("Origin", "https://points.example.com");

        ResponseEntity<?> allowedCheckIn = controller.checkIn(pointsReq, "TRN-PTS-CHK", Map.of());
        assertThat(allowedCheckIn.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> allowedByTable = controller.byTable(pointsReq, "TBL_TRN_TRN-PTS-CHK_R1_1", "P1");
        assertThat(allowedByTable.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
