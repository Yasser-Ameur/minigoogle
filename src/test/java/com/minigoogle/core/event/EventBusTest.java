package com.minigoogle.core.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for EventBus functionality. */
class EventBusTest {

    @Test
    void testPublishDeliversToSubscribers() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(QueryExecutedEvent.class,
            e -> received.addAndGet(e.resultCount()));

        bus.publish(new QueryExecutedEvent("test", 5, 12, false));

        assertEquals(5, received.get());
    }

    @Test
    void testPublishIgnoredWhenNoSubscribers() {
        EventBus bus = new EventBus();
        assertDoesNotThrow(() -> bus.publish(new QueryExecutedEvent("test", 5, 12, false)));
    }

    @Test
    void testListenerOnlyReceivesItsEventType() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(QueryExecutedEvent.class,
            e -> received.incrementAndGet());

        bus.publish(new QueryExecutedEvent("test", 5, 12, false));
        bus.publish(new NodeJoinedEvent("node-1", "localhost", 8081));

        assertEquals(1, received.get());
    }

    @Test
    void testExceptionInListenerDoesNotBlockOthers() {
        EventBus bus = new EventBus();
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(QueryExecutedEvent.class, e -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(QueryExecutedEvent.class,
            e -> received.incrementAndGet());

        bus.publish(new QueryExecutedEvent("test", 5, 12, false));

        assertEquals(1, received.get());
    }

    @Test
    void testListenerCount() {
        EventBus bus = new EventBus();
        bus.subscribe(QueryExecutedEvent.class, e -> { });
        bus.subscribe(QueryExecutedEvent.class, e -> { });
        assertEquals(2, bus.listenerCount(QueryExecutedEvent.class));
        assertEquals(0, bus.listenerCount(NodeJoinedEvent.class));
    }

    @Test
    void testClear() {
        EventBus bus = new EventBus();
        bus.subscribe(QueryExecutedEvent.class, e -> { });
        bus.clear();
        assertEquals(0, bus.listenerCount(QueryExecutedEvent.class));
    }
}
