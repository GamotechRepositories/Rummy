package com.rummy.gameservice.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ordered, non-blocking outbound queue for one WebSocket session.
 *
 * <p>Callers (table actors, the handler) only enqueue; a single drainer writes frames in
 * enqueue order, so a slow client never blocks the table lock and two threads never write
 * to the same socket at once (Tomcat rejects that with TEXT_PARTIAL_WRITING). A client that
 * falls more than {@link #MAX_PENDING} frames behind is disconnected; on reconnect it gets
 * a fresh full GAME_VIEW, which is cheaper than buffering an unbounded backlog.
 */
public final class SessionOutbox {

    private static final Logger log = LoggerFactory.getLogger(SessionOutbox.class);

    public static final String ATTRIBUTE = "rummy.outbox";
    static final int MAX_PENDING = 256;
    private static final ExecutorService DRAINERS = Executors.newVirtualThreadPerTaskExecutor();

    private final WebSocketSession session;
    private final Executor executor;
    private final Queue<TextMessage> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean overflowed = new AtomicBoolean();

    SessionOutbox(WebSocketSession session, Executor executor) {
        this.session = session;
        this.executor = executor;
    }

    /** Creates the outbox for a new connection. Call once from afterConnectionEstablished. */
    public static SessionOutbox attach(WebSocketSession session) {
        return attach(session, DRAINERS);
    }

    static SessionOutbox attach(WebSocketSession session, Executor executor) {
        SessionOutbox outbox = new SessionOutbox(session, executor);
        Map<String, Object> attributes = session.getAttributes();
        if (attributes == null) {
            return outbox;
        }
        // Spring's session attribute maps are concurrent, so putIfAbsent is atomic in production.
        Object existing = attributes.putIfAbsent(ATTRIBUTE, outbox);
        return existing instanceof SessionOutbox current ? current : outbox;
    }

    /** Queues a frame for the session. Never blocks on network I/O. */
    public static void send(WebSocketSession session, TextMessage message) {
        if (session == null || message == null) {
            return;
        }
        Map<String, Object> attributes = session.getAttributes();
        if (attributes == null) {
            sendDirect(session, message);
            return;
        }
        Object outbox = attributes.get(ATTRIBUTE);
        if (outbox instanceof SessionOutbox box) {
            box.enqueue(message);
        } else {
            attach(session).enqueue(message);
        }
    }

    /** Sessions without an attribute map cannot hold an outbox (only stubs do this). */
    private static void sendDirect(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.debug("[WS:{}] Direct send failed: {}", session.getId(), e.getMessage());
        }
    }

    public static void send(WebSocketSession session, String json) {
        send(session, new TextMessage(json));
    }

    void enqueue(TextMessage message) {
        if (overflowed.get() || !session.isOpen()) {
            return;
        }
        if (pendingCount.incrementAndGet() > MAX_PENDING) {
            pendingCount.decrementAndGet();
            if (overflowed.compareAndSet(false, true)) {
                log.warn("[WS:{}] Client is {} frames behind; disconnecting so it can resync", session.getId(), MAX_PENDING);
                pending.clear();
                pendingCount.set(0);
                scheduleDrain();
            }
            return;
        }
        pending.add(message);
        scheduleDrain();
    }

    int pendingCount() {
        return pendingCount.get();
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            TextMessage next;
            while (!overflowed.get() && (next = pending.poll()) != null) {
                pendingCount.decrementAndGet();
                if (!session.isOpen()) {
                    pending.clear();
                    pendingCount.set(0);
                    return;
                }
                session.sendMessage(next);
            }
            if (overflowed.get()) {
                closeQuietly(CloseStatus.SESSION_NOT_RELIABLE);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[WS:{}] Send failed, closing session: {}", session.getId(), e.getMessage());
            pending.clear();
            pendingCount.set(0);
            closeQuietly(CloseStatus.SERVER_ERROR);
        } finally {
            draining.set(false);
            if (!pending.isEmpty() && !overflowed.get() && session.isOpen()) {
                scheduleDrain();
            }
        }
    }

    private void closeQuietly(CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException ignored) {
            // already gone
        }
    }
}
