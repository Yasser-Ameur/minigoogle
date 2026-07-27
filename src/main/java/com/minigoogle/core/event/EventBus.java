package com.minigoogle.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<? extends Event>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T extends Event> void subscribe(Class<T> eventType, EventListener<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    @SuppressWarnings("unchecked")
    public void publish(Event event) {
        List<EventListener<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null) return;
        for (EventListener<?> listener : eventListeners) {
            try {
                ((EventListener<Event>) listener).onEvent(event);
            } catch (Exception e) {
                logger.error("Error publishing event {} to listener: {}",
                        event.eventType(), listener.getClass().getSimpleName(), e);
            }
        }
    }

    public int listenerCount(Class<? extends Event> eventType) {
        List<?> list = listeners.get(eventType);
        return list != null ? list.size() : 0;
    }

    public void clear() {
        listeners.clear();
    }
}
