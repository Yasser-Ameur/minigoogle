package com.minigoogle.distributed.query.execution;

import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;

/**
 * Interface representing an executor capable of running a search query
 * against a specific shard and returning a LocalSearchResponse.
 */
public interface SearchExecutor {
    LocalSearchResponse execute(QueryContext context);
}
