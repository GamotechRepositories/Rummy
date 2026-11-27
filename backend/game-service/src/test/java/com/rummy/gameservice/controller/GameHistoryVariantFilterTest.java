package com.rummy.gameservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.gameservice.persistence.GamePersistenceService;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.persistence.repository.GameEventRepository;
import com.rummy.gameservice.persistence.repository.GameRepository;
import com.rummy.gameservice.persistence.repository.GameResultRepository;
import com.rummy.gameservice.persistence.repository.UserProfileRepository;
import com.rummy.gameservice.persistence.repository.WalletTransactionRepository;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.security.VariantAccessPolicy;
import com.rummy.gameservice.wallet.GameSettlementResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class GameHistoryVariantFilterTest {

    private final Map<String, GameDocument> games = new HashMap<>();
    private final List<GameResultDocument> results = new CopyOnWriteArrayList<>();
    private final List<WalletTransactionDocument> txStore = new CopyOnWriteArrayList<>();
    private GamePersistenceService persistenceService;
    private GameHistoryController controller;

    @BeforeEach
    void setUp() {
        games.clear();
        results.clear();
        txStore.clear();

        UserProfileDocument profile = new UserProfileDocument("P1", "Player One", 10000L);
        profile.setGamesPlayed(10);
        profile.setGamesWon(4);

        GameRepository gameRepo = (GameRepository) Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, args) -> {
                    if ("findAllById".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Iterable<String> ids = (Iterable<String>) args[0];
                        List<GameDocument> matched = new ArrayList<>();
                        for (String id : ids) {
                            GameDocument doc = games.get(id);
                            if (doc != null) matched.add(doc);
                        }
                        return matched;
                    }
                    if ("findById".equals(method.getName())) {
                        return Optional.ofNullable(games.get((String) args[0]));
                    }
                    return null;
                }
        );

        GameResultRepository resultRepo = (GameResultRepository) Proxy.newProxyInstance(
                GameResultRepository.class.getClassLoader(),
                new Class<?>[]{GameResultRepository.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("findByPlayerIdOrderByCreatedAtDesc".equals(m)) {
                        String pid = (String) args[0];
                        Pageable pageable = (Pageable) args[1];
                        return results.stream()
                                .filter(r -> pid.equals(r.getPlayerId()))
                                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                                .limit(pageable.getPageSize())
                                .toList();
                    }
                    if ("findByGameId".equals(m)) {
                        String gid = (String) args[0];
                        return results.stream().filter(r -> gid.equals(r.getGameId())).toList();
                    }
                    if ("save".equals(m)) {
                        GameResultDocument doc = (GameResultDocument) args[0];
                        if (!results.contains(doc)) {
                            results.add(doc);
                        }
                        return doc;
                    }
                    return null;
                }
        );

        GameEventRepository eventRepo = (GameEventRepository) Proxy.newProxyInstance(
                GameEventRepository.class.getClassLoader(),
                new Class<?>[]{GameEventRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        UserProfileRepository profileRepo = (UserProfileRepository) Proxy.newProxyInstance(
                UserProfileRepository.class.getClassLoader(),
                new Class<?>[]{UserProfileRepository.class},
                (proxy, method, args) -> {
                    if ("findByUserId".equals(method.getName())) {
                        return Optional.of(profile);
                    }
                    if ("save".equals(method.getName())) {
                        return args[0];
                    }
                    return null;
                }
        );

        WalletTransactionRepository txRepo = (WalletTransactionRepository) Proxy.newProxyInstance(
                WalletTransactionRepository.class.getClassLoader(),
                new Class<?>[]{WalletTransactionRepository.class},
                (proxy, method, args) -> {
                    if ("findByGameIdIn".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Collection<String> gids = (Collection<String>) args[0];
                        return txStore.stream().filter(t -> t.getGameId() != null && gids.contains(t.getGameId())).toList();
                    }
                    if ("findByGameId".equals(method.getName())) {
                        String gid = (String) args[0];
                        return txStore.stream().filter(t -> gid.equals(t.getGameId())).toList();
                    }
                    return Collections.emptyList();
                }
        );

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        persistenceService = new GamePersistenceService(gameRepo, resultRepo, eventRepo, profileRepo, mapper);
        persistenceService.setWalletTransactionRepository(txRepo);
        controller = new GameHistoryController(persistenceService);
    }

    @AfterEach
    void tearDown() {
        persistenceService.shutdown();
    }

    private void addMatch(String gameId, String tableId, String rulesetId, boolean resultHasRuleset, boolean won, int score, Instant when) {
        GameDocument g = new GameDocument();
        g.setGameId(gameId);
        g.setRulesetId(rulesetId);
        games.put(gameId, g);

        GameResultDocument r = new GameResultDocument(
                gameId, tableId, "P1", "Player One", score, won,
                won ? "WINNER" : "ELIMINATED", when
        );
        if (resultHasRuleset) {
            r.setRulesetId(rulesetId);
        }
        results.add(r);
    }

    @Test
    @DisplayName("Enriches legacy GameResultDocument entries missing rulesetId from GameDocument")
    void enrichesMissingRulesetFromGameDocument() {
        Instant now = Instant.now();
        addMatch("G1", "TBL_MM_1", "POINTS_13", false, true, 0, now);
        addMatch("G2", "TBL_MM_2", "POOL_101", false, false, 34, now.minusSeconds(10));

        List<GameResultDocument> history = persistenceService.getPlayerHistory("P1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRulesetId()).isEqualTo("POINTS_13");
        assertThat(history.get(1).getRulesetId()).isEqualTo("POOL_101");
    }

    @Test
    @DisplayName("Filters player history by ?rulesets= across >50 recent matches and computes filtered winRate")
    void filtersByRulesetsAcrossLargerWindowAndComputesWinRate() {
        Instant base = Instant.now();
        for (int i = 0; i < 60; i++) {
            addMatch("PTS_" + i, "TBL_MM_" + i, "POINTS_13", true, i % 2 == 0, i % 2 == 0 ? 0 : 25, base.plusSeconds(100 + i));
        }
        addMatch("D2_1", "TBL_MM_D2", "DEALS_2", false, true, 0, base.plusSeconds(5));
        addMatch("D3_1", "TBL_MM_D3", "DEALS_3", true, false, 18, base.plusSeconds(4));
        addMatch("P201_1", "TBL_MM_P201", "POOL_201", true, true, 0, base.plusSeconds(3));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");

        ResponseEntity<Map<String, Object>> dealsResp = controller.getPlayerHistory(req, "P1", "DEALS_2,DEALS_3");
        assertThat(dealsResp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> dealsBody = dealsResp.getBody();
        assertThat(dealsBody).isNotNull();
        assertThat(dealsBody.get("filtered")).isEqualTo(true);
        assertThat(dealsBody.get("winRate")).isEqualTo(50.0);

        @SuppressWarnings("unchecked")
        List<GameResultDocument> dealsList = (List<GameResultDocument>) dealsBody.get("results");
        assertThat(dealsList).extracting(GameResultDocument::getGameId).containsExactly("D2_1", "D3_1");
        UserProfileDocument dealsProfile = (UserProfileDocument) dealsBody.get("profile");
        assertThat(dealsProfile.getGamesPlayed()).isEqualTo(2);
        assertThat(dealsProfile.getGamesWon()).isEqualTo(1);

        ResponseEntity<Map<String, Object>> allResp = controller.getPlayerHistory(req, "P1", null);
        Map<String, Object> allBody = allResp.getBody();
        assertThat(allBody).isNotNull();
        assertThat(allBody.get("filtered")).isEqualTo(false);
        assertThat(allBody.get("winRate")).isEqualTo(40.0);
        UserProfileDocument allProfile = (UserProfileDocument) allBody.get("profile");
        assertThat(allProfile.getGamesPlayed()).isEqualTo(10);
        assertThat(allProfile.getGamesWon()).isEqualTo(4);

        @SuppressWarnings("unchecked")
        List<GameResultDocument> allList = (List<GameResultDocument>) allBody.get("results");
        assertThat(allList).hasSize(50);
    }

    @Test
    @DisplayName("Attaches settlement delta directly and enriches Points win/loss rupee P&L from ledger")
    void recordsSettlementDeltaAndEnrichesLegacyNetAmounts() {
        Instant now = Instant.now();
        // 1. Persisted match updated via recordGameSettlement
        addMatch("G_SETTLED", "TBL_MM_S", "POOL_101", true, true, 0, now);
        GameSettlementResult settled = new GameSettlementResult(
                "G_SETTLED", "TBL_MM_S", "POOL_101", "P1", BigDecimal.valueOf(50),
                BigDecimal.valueOf(100), BigDecimal.valueOf(0.15), BigDecimal.valueOf(15), BigDecimal.valueOf(85),
                Map.of("P1", new GameSettlementResult.PlayerSettlementDetail(
                        "P1", true, 0, BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.valueOf(85), BigDecimal.valueOf(35.0))),
                null
        );
        persistenceService.recordGameSettlement(settled);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(results.stream().filter(r -> "G_SETTLED".equals(r.getGameId())).findFirst().orElseThrow().getNetAmountRs())
                    .isEqualTo(35.0);
        });

        // 2. Legacy Points drop match with GAME_REFUND metadata (penalty=20, refund=15 => -5.00 loss on Rs20 stake)
        addMatch("G_DROP", "TBL_MM_D", "POINTS_13", true, false, 20, now.minusSeconds(10));
        txStore.add(new WalletTransactionDocument(
                "REFUND_G_DROP_P1", "P1", "G_DROP", "GAME_REFUND", BigDecimal.valueOf(15),
                BigDecimal.ZERO, BigDecimal.valueOf(15), "INR", "Drop refund",
                Map.of("gameId", "G_DROP", "penalty", 20, "refund", 15.0)
        ));

        // 3. Legacy Points win match with GAME_WIN metadata netPrize=17.00
        addMatch("G_WIN", "TBL_MM_W", "POINTS_13", true, true, 0, now.minusSeconds(20));
        txStore.add(new WalletTransactionDocument(
                "WIN_G_WIN_P1", "P1", "G_WIN", "GAME_WIN", BigDecimal.valueOf(37),
                BigDecimal.ZERO, BigDecimal.valueOf(37), "INR", "Win",
                Map.of("gameId", "G_WIN", "netPrize", 17.0)
        ));

        // 4. Tournament table match remains non-cash (netAmountRs = null) and is excluded when excludeTournaments=true
        addMatch("G_TRN", "TBL_TRN_T1_R1_1", "POINTS_13", true, false, 20, now.minusSeconds(30));

        // 5. Private room match is retained when excludeTournaments=true
        addMatch("G_PRV", "TBL_PRV_ABC123", "POINTS_13", true, true, 0, now.minusSeconds(40));

        List<GameResultDocument> history = persistenceService.getPlayerHistory("P1");
        assertThat(history).hasSize(5);
        assertThat(history.get(0).getNetAmountRs()).isEqualTo(35.0);
        assertThat(history.get(1).getNetAmountRs()).isEqualTo(-5.0);
        assertThat(history.get(2).getNetAmountRs()).isEqualTo(17.0);
        assertThat(history.get(3).getNetAmountRs()).isNull();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");
        ResponseEntity<Map<String, Object>> exclResp = controller.getPlayerHistory(req, "P1", "POINTS_13", true);
        @SuppressWarnings("unchecked")
        List<GameResultDocument> exclList = (List<GameResultDocument>) exclResp.getBody().get("results");
        assertThat(exclList).extracting(GameResultDocument::getGameId).containsExactly("G_DROP", "G_WIN", "G_PRV");
    }

    @Test
    @DisplayName("getGameDetails rejects cross-variant replay requests while allowing matching variant origin")
    void getGameDetailsEnforcesVariantPolicy() {
        GameDocument poolGame = new GameDocument();
        poolGame.setGameId("G_POOL_REPLAY");
        poolGame.setRulesetId("POOL_101");
        poolGame.setStatus("COMPLETED");
        poolGame.setPlayers(List.of(new GameDocument.PlayerSnapshot("P1", "Player One", 0, 0, "ACTIVE", false, true)));
        games.put("G_POOL_REPLAY", poolGame);

        VariantAccessPolicy policy = new VariantAccessPolicy(
                "https://points.example.com=POINTS_13;https://pool.example.com=POOL_101,POOL_201",
                ""
        );
        GameHistoryController isolated = new GameHistoryController(persistenceService, policy);

        MockHttpServletRequest pointsReq = new MockHttpServletRequest();
        pointsReq.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");
        pointsReq.addHeader("Origin", "https://points.example.com");
        ResponseEntity<Map<String, Object>> denied = isolated.getGameDetails(pointsReq, "G_POOL_REPLAY");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        MockHttpServletRequest poolReq = new MockHttpServletRequest();
        poolReq.setAttribute(AuthenticatedPlayer.REQUEST_ATTRIBUTE, "P1");
        poolReq.addHeader("Origin", "https://pool.example.com");
        ResponseEntity<Map<String, Object>> allowed = isolated.getGameDetails(poolReq, "G_POOL_REPLAY");
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
