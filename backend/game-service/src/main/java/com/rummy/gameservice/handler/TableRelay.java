package com.rummy.gameservice.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.security.JwtHandshakeInterceptor;
import com.rummy.gameservice.websocket.SessionOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connects a player to a table hosted on another node when the load balancer put them on this one.
 *
 * <p>This node opens a WebSocket to the owning node (authenticated with the player's own JWT) and
 * passes frames both ways unchanged, so the client needs no sticky routing. A connection that is
 * itself a relay ({@code relay=1}) is never relayed again, which rules out loops.
 */
@Component
public class TableRelay {

    private static final Logger log = LoggerFactory.getLogger(TableRelay.class);
    static final String LINK_ATTRIBUTE = "rummy.relay";
    static final String RELAYED_ATTRIBUTE = "rummy.relayed";
    private static final String RELAY_AUTH_REQUEST = "relay_auth";

    public enum Result { CONNECTED, UNREACHABLE, UNAVAILABLE }

    private final ClusterNodeService cluster;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration connectTimeout;
    private final HttpClient http;

    @Autowired
    public TableRelay(@Autowired(required = false) ClusterNodeService cluster,
                      ObjectMapper objectMapper,
                      @Value("${rummy.cluster.relay-enabled:true}") boolean enabled,
                      @Value("${rummy.cluster.relay-connect-timeout-ms:3000}") long connectTimeoutMs) {
        this.cluster = cluster;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.enabled = enabled;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /** Marks connections that another node opened on a player's behalf. */
    static void markIfRelayed(WebSocketSession session) {
        URI uri = session.getUri();
        String query = uri != null ? uri.getQuery() : null;
        if (query != null && ("&" + query + "&").contains("&relay=1&")) {
            session.getAttributes().put(RELAYED_ATTRIBUTE, Boolean.TRUE);
        }
    }

    /**
     * Relays {@code client} to the node hosting {@code tableId} and delivers {@code firstFrame} there.
     * {@link Result#UNAVAILABLE}: relaying is not possible here (disabled, no address, or already a relay);
     * {@link Result#UNREACHABLE}: the owner did not accept the connection (it may be going down).
     */
    public Result open(WebSocketSession client, String tableId, String ownerNode, String playerId, String firstFrame) {
        if (!enabled || cluster == null || Boolean.TRUE.equals(client.getAttributes().get(RELAYED_ATTRIBUTE))) {
            return Result.UNAVAILABLE;
        }
        Optional<String> address = cluster.addressOf(ownerNode);
        if (address.isEmpty() || address.get().equals(cluster.address())) {
            return Result.UNAVAILABLE;
        }
        close(client);
        String token = (String) client.getAttributes().get(JwtHandshakeInterceptor.AUTH_TOKEN_ATTRIBUTE);
        URI uri = URI.create("ws://" + address.get() + "/ws/game?relay=1"
                + (token != null ? "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) : ""));
        Link link = new Link(client, tableId, ownerNode);
        try {
            link.ws = http.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(uri, link)
                    .get(connectTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[Relay] Could not reach node {} ({}) for table {}: {}", ownerNode, address.get(), tableId, e.getMessage());
            return Result.UNREACHABLE;
        }
        client.getAttributes().put(LINK_ATTRIBUTE, link);
        try {
            link.send(objectMapper.writeValueAsString(Map.of(
                    "type", "AUTH", "requestId", RELAY_AUTH_REQUEST, "payload", Map.of("playerId", playerId))));
        } catch (IOException e) {
            close(client);
            return Result.UNREACHABLE;
        }
        link.send(firstFrame);
        log.debug("[Relay] Player {} on table {} relayed to node {}", playerId, tableId, ownerNode);
        return Result.CONNECTED;
    }

    /**
     * Passes a client frame to the owning node if this connection is relayed for that table (frames
     * without a table, such as PING, go too). A frame for a different table ends the relay and returns
     * false so this node handles it.
     */
    public boolean forward(WebSocketSession client, String tableId, String frame) {
        if (!(client.getAttributes().get(LINK_ATTRIBUTE) instanceof Link link)) {
            return false;
        }
        if (tableId != null && !tableId.isBlank() && !tableId.equals(link.tableId)) {
            close(client);
            return false;
        }
        link.send(frame);
        return true;
    }

    public boolean isRelayed(WebSocketSession client) {
        return client.getAttributes().get(LINK_ATTRIBUTE) instanceof Link;
    }

    /** Ends the relay of {@code client} (the client disconnected or moved to another table). */
    public void close(WebSocketSession client) {
        if (client.getAttributes().remove(LINK_ATTRIBUTE) instanceof Link link) {
            link.shutdownUpstream();
        }
    }

    /** One relayed player connection: client socket here, upstream socket to the owning node. */
    private final class Link implements WebSocket.Listener {
        final WebSocketSession client;
        final String tableId;
        final String owner;
        volatile WebSocket ws;
        private volatile boolean closed;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private final StringBuilder partial = new StringBuilder();
        private boolean handshakeDone;

        Link(WebSocketSession client, String tableId, String owner) {
            this.client = client;
            this.tableId = tableId;
            this.owner = owner;
        }

        /** Upstream sends must not overlap, so they are chained. */
        synchronized void send(String text) {
            if (closed) {
                return;
            }
            tail = tail.thenCompose(v -> ws.sendText(text, true).thenApply(w -> (Void) null))
                    .exceptionally(e -> {
                        upstreamGone(CloseStatus.SERVICE_RESTARTED, "relay send failed: " + e.getMessage());
                        return null;
                    });
        }

        synchronized void shutdownUpstream() {
            if (closed) {
                return;
            }
            closed = true;
            tail.thenCompose(v -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "client left"))
                    .exceptionally(e -> {
                        ws.abort();
                        return null;
                    });
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                deliver(frame);
            }
            webSocket.request(1);
            return null;
        }

        private void deliver(String frame) {
            if (!handshakeDone) {
                String type = typeOf(frame);
                if ("CONNECTED".equals(type)) {
                    return;
                }
                handshakeDone = true;
                if ("AUTH_SUCCESS".equals(type)) {
                    return;
                }
            }
            SessionOutbox.send(client, frame);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            upstreamGone(forwardable(statusCode), "owner closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            upstreamGone(CloseStatus.SERVICE_RESTARTED, "relay error: " + error.getMessage());
        }

        /** The owner side ended: disconnect the client so it reconnects and finds the table again. */
        private void upstreamGone(CloseStatus status, String why) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            client.getAttributes().remove(LINK_ATTRIBUTE, this);
            log.debug("[Relay] Relay of table {} to node {} ended: {}", tableId, owner, why);
            try {
                if (client.isOpen()) {
                    client.close(status);
                }
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
        }
    }

    private String typeOf(String frame) {
        try {
            JsonNode type = objectMapper.readTree(frame).get("type");
            return type != null ? type.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static CloseStatus forwardable(int code) {
        return switch (code) {
            case 1000, 1001, 1008, 1011, 1012, 1013 -> new CloseStatus(code);
            default -> code >= 4000 && code <= 4999 ? new CloseStatus(code) : CloseStatus.SERVICE_RESTARTED;
        };
    }
}
