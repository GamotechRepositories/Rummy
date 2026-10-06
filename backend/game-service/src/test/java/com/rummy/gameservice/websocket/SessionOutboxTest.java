package com.rummy.gameservice.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SessionOutbox")
class SessionOutboxTest {

    @Test
    @DisplayName("Frames from many threads are written one at a time, each thread's frames in order")
    void serialWritesPreservePerProducerOrder() throws Exception {
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        WebSocketSession session = session();
        doAnswer(inv -> {
            if (inFlight.incrementAndGet() > 1) overlapped.set(true);
            Thread.sleep(0, 200_000);
            written.add(((TextMessage) inv.getArgument(0)).getPayload());
            inFlight.decrementAndGet();
            return null;
        }).when(session).sendMessage(any());
        SessionOutbox.attach(session);

        int producers = 4;
        int perProducer = 50;
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        for (int p = 0; p < producers; p++) {
            int id = p;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perProducer; i++) {
                    SessionOutbox.send(session, id + ":" + i);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(session, timeout(5000).times(producers * perProducer)).sendMessage(any());

        assertThat(overlapped).isFalse();
        for (int p = 0; p < producers; p++) {
            String prefix = p + ":";
            List<Integer> seq = written.stream().filter(s -> s.startsWith(prefix))
                    .map(s -> Integer.parseInt(s.substring(prefix.length()))).toList();
            assertThat(seq).isSorted().hasSize(perProducer);
        }
    }

    @Test
    @DisplayName("A client that stops reading is disconnected instead of buffering without limit")
    void slowClientIsDisconnected() throws Exception {
        WebSocketSession session = session();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            blocked.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any());
        SessionOutbox outbox = SessionOutbox.attach(session);

        SessionOutbox.send(session, "first");
        assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i <= SessionOutbox.MAX_PENDING; i++) {
            SessionOutbox.send(session, "backlog-" + i);
        }
        assertThat(outbox.pendingCount()).isZero();
        release.countDown();

        verify(session, timeout(2000)).close(CloseStatus.SESSION_NOT_RELIABLE);
        verify(session, times(1)).sendMessage(any());
    }

    @Test
    @DisplayName("A failed write closes the session and stops further sends")
    void failedWriteClosesSession() throws Exception {
        WebSocketSession session = session();
        doThrow(new java.io.IOException("broken pipe")).when(session).sendMessage(any());
        SessionOutbox.attach(session);

        SessionOutbox.send(session, "a");

        verify(session, timeout(2000)).close(CloseStatus.SERVER_ERROR);
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s-1");
        return session;
    }
}
