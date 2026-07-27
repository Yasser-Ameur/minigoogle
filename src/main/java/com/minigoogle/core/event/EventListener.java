package com.minigoogle.core.event;

@FunctionalInterface
public interface EventListener<T extends Event> {
    void onEvent(T event);
}
