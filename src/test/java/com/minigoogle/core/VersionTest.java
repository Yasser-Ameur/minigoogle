package com.minigoogle.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests for {@link Version}. */
class VersionTest {

    @Test
    void currentIsNeverNullOrBlank() {
        String version = Version.current();
        assertNotNull(version);
        assertEquals(false, version.isBlank());
    }

    @Test
    void fallsBackToPropertiesResourceWhenNoManifest() {
        // Test runs have no jar manifest (Implementation-Version is null), so
        // Version must fall back to src/test/resources/minigoogle-version.properties.
        assertEquals("9.9.9-test", Version.current());
    }
}
