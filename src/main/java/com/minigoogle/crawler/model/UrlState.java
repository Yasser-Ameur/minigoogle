package com.minigoogle.crawler.model;

/** Enum representing the lifecycle states of a crawled URL. */
public enum UrlState {
    DISCOVERED,
    QUEUED,
    ASSIGNED,
    FETCHING,
    FETCHED,
    INDEXED,
    FAILED,
    RETRY
}
