package com.minigoogle.corpus;

/**
 * A BEIR test query: the original {@code _id} used to reference it in the qrels
 * and the natural-language text that is issued to the engine.
 */
public record BeirQuery(String id, String text) {
}
