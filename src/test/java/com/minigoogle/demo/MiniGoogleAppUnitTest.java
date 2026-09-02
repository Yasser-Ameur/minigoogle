package com.minigoogle.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MiniGoogleApp} helpers that do not need a running
 * server or a built index: the pagination slice and the API key length gate.
 */
class MiniGoogleAppUnitTest {

    @Test
    void paginatePage2OfSizeThreeReturnsResultsFourToSixOfSevenHits() {
        List<String> sevenHits = List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7");
        int page = 2;
        int pageSize = 3;
        int offset = (page - 1) * pageSize;

        List<String> pageResults = MiniGoogleApp.paginate(sevenHits, offset, pageSize);

        assertEquals(List.of("r4", "r5", "r6"), pageResults);
    }

    @Test
    void paginateFirstPageReturnsFromTheStart() {
        List<String> items = List.of("a", "b", "c", "d");
        assertEquals(List.of("a", "b"), MiniGoogleApp.paginate(items, 0, 2));
    }

    @Test
    void paginatePastTheEndReturnsEmpty() {
        List<String> items = List.of("a", "b", "c");
        assertEquals(List.of(), MiniGoogleApp.paginate(items, 10, 3));
    }

    @Test
    void paginatePartialLastPage() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("d", "e"), MiniGoogleApp.paginate(items, 3, 3));
    }

    @Test
    void shortApiKeyFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MiniGoogleApp.validateApiKey("too-short"));
        assertTrue(ex.getMessage().contains("16"));
    }

    @Test
    void blankApiKeyIsAllowedAndLeavesAdminRoutesOpen() {
        assertDoesNotThrow(() -> MiniGoogleApp.validateApiKey(""));
    }

    @Test
    void apiKeyAtMinimumLengthIsAllowed() {
        assertDoesNotThrow(() -> MiniGoogleApp.validateApiKey("0123456789abcdef"));
    }
}
