package com.rummy.gameservice.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TableRoutingRegistryTest {

    private TableRoutingRegistry registry;
    private PlayerPresenceService presenceService;

    @BeforeEach
    void setUp() {
        registry = new TableRoutingRegistry(null, "game-server-node-1");
        presenceService = new PlayerPresenceService(null);
    }

    @Test
    @DisplayName("Should register and lookup table ownership")
    void shouldRegisterAndLookupTableOwnership() {
        String tableId = "TBL_TEST_101";

        registry.registerTableOwnership(tableId);

        assertThat(registry.isTableHostedLocally(tableId)).isTrue();
        Optional<String> server = registry.getServerForTable(tableId);
        assertThat(server).isPresent().contains("game-server-node-1");

        registry.unregisterTable(tableId);
        assertThat(registry.isTableHostedLocally(tableId)).isFalse();
        assertThat(registry.getServerForTable(tableId)).isEmpty();
    }

    @Test
    @DisplayName("Should track player active table routing")
    void shouldTrackPlayerActiveTableRouting() {
        String playerId = "USR_ALICE";
        String tableId = "TBL_TEST_202";

        registry.registerPlayerTable(playerId, tableId);

        assertThat(registry.getTableForPlayer(playerId)).isPresent().contains(tableId);

        registry.unregisterPlayer(playerId);
        assertThat(registry.getTableForPlayer(playerId)).isEmpty();
    }

    @Test
    @DisplayName("Should track player presence and online status")
    void shouldTrackPlayerPresence() {
        String playerId = "USR_BOB";

        assertThat(presenceService.isPlayerOnline(playerId)).isFalse();

        presenceService.updatePresence(playerId);
        assertThat(presenceService.isPlayerOnline(playerId)).isTrue();
        assertThat(presenceService.getLocalOnlineCount()).isEqualTo(1);

        presenceService.removePresence(playerId);
        assertThat(presenceService.isPlayerOnline(playerId)).isFalse();
    }
}
