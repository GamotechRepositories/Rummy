package com.rummy.gameservice.privateroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.gameservice.actor.TableManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateRoomVariantFilterTest {

    private TableManager tableManager;
    private final List<PrivateRoomDocument> store = new CopyOnWriteArrayList<>();
    private PrivateRoomService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tableManager = new TableManager(mapper, null, null, null, null);
        store.clear();

        PrivateRoomRepository repo = (PrivateRoomRepository) Proxy.newProxyInstance(
                PrivateRoomRepository.class.getClassLoader(),
                new Class<?>[]{PrivateRoomRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByRoomCode".equals(name)) {
                        String code = (String) args[0];
                        return store.stream().filter(d -> code.equals(d.getRoomCode())).findFirst();
                    }
                    if ("findByPlayersPlayerIdOrderByCreatedAtDesc".equals(name)) {
                        String pid = (String) args[0];
                        return store.stream()
                                .filter(d -> d.getPlayers().stream().anyMatch(p -> pid.equals(p.getPlayerId())))
                                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                                .toList();
                    }
                    if ("save".equals(name)) {
                        PrivateRoomDocument doc = (PrivateRoomDocument) args[0];
                        if (!store.contains(doc)) {
                            store.add(doc);
                        }
                        return doc;
                    }
                    return null;
                }
        );

        service = new PrivateRoomService(repo, tableManager, null, null);
    }

    @AfterEach
    void tearDown() {
        tableManager.shutdown();
    }

    private static PrivateRoomDocument makeRoom(String code, String rulesetId, String status, String playerId, Instant createdAt) {
        PrivateRoomDocument doc = new PrivateRoomDocument();
        doc.setRoomCode(code);
        doc.setRulesetId(rulesetId);
        doc.setStatus(status);
        doc.setMaxPlayers(2);
        doc.setStakeTier(0);
        doc.setHostPlayerId(playerId);
        doc.setHostDisplayName("Host");
        doc.setCreatedAt(createdAt);
        doc.setExpiresAt(createdAt.plusSeconds(900));
        PrivateRoomDocument.RoomPlayer rp = new PrivateRoomDocument.RoomPlayer();
        rp.setPlayerId(playerId);
        rp.setDisplayName("Host");
        rp.setSeat(0);
        doc.getPlayers().add(rp);
        return doc;
    }

    @Test
    @DisplayName("listMyRooms filters by rulesetIds before applying limit(20)")
    void listMyRoomsFiltersByRulesetsBeforeLimit() {
        Instant base = Instant.now();
        // Add 25 newer POINTS_13 rooms that would push older DEALS_2 rooms past limit(20)
        for (int i = 0; i < 25; i++) {
            store.add(makeRoom("PTS" + i, "POINTS_13", "COMPLETED", "P1", base.plusSeconds(100 + i)));
        }
        store.add(makeRoom("DLS001", "DEALS_2", "COMPLETED", "P1", base));
        store.add(makeRoom("POL001", "POOL_101", "COMPLETED", "P1", base.plusSeconds(1)));

        List<Map<String, Object>> dealsRooms = service.listMyRooms("P1", "DEALS_2,DEALS_3");
        assertThat(dealsRooms).hasSize(1);
        assertThat(dealsRooms.get(0).get("roomCode")).isEqualTo("DLS001");

        List<Map<String, Object>> poolRooms = service.listMyRooms("P1", "POOL_101,POOL_201");
        assertThat(poolRooms).hasSize(1);
        assertThat(poolRooms.get(0).get("roomCode")).isEqualTo("POL001");
    }

    @Test
    @DisplayName("joinRoom rejects room code from a different variant lobby before adding player")
    void joinRoomRejectsCrossVariantRoom() {
        store.add(makeRoom("PTS999", "POINTS_13", "WAITING", "HOST1", Instant.now()));

        assertThatThrownBy(() -> service.joinRoom("PTS999", "P2", "Player 2", List.of("DEALS_2", "DEALS_3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Point Rummy");

        assertThat(store.get(0).getPlayers()).hasSize(1);
    }
}
