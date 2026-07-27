package com.minigoogle.core.storage;

import com.minigoogle.indexer.inverted.PostingList;

import java.io.IOException;

public interface IndexReader extends AutoCloseable {
    PostingList readPostingList(long offset) throws IOException;
}
