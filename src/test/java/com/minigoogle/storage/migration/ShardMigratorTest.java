package com.minigoogle.cluster.migration;

import com.minigoogle.network.http.RestClient;
import com.minigoogle.storage.filesystem.StorageLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ShardMigrator functionality. */
class ShardMigratorTest {

    @TempDir
    Path tempDir;

    private StorageLayout sourceLayout;
    private StorageLayout targetLayout;
    private ShardMigrator migrator;

    @BeforeEach
    void setUp() {
        sourceLayout = new StorageLayout(tempDir.resolve("source"));
        targetLayout = new StorageLayout(tempDir.resolve("target"));
        migrator = new ShardMigrator(sourceLayout, targetLayout, null);
    }

    private void createShardFiles(int shardId, String... fileNames) throws Exception {
        Path shardDir = sourceLayout.getShardDirectory(shardId);
        Files.createDirectories(shardDir);
        for (String name : fileNames) {
            Files.writeString(shardDir.resolve(name), "content-" + name);
        }
    }

    @Test
    void testMigrateShard() throws Exception {
        createShardFiles(0, "dictionary.bin", "postings.bin");

        boolean success = migrator.migrateShard(0);
        assertTrue(success);

        Path targetDir = targetLayout.getShardDirectory(0);
        assertTrue(Files.exists(targetDir.resolve("dictionary.bin")));
        assertTrue(Files.exists(targetDir.resolve("postings.bin")));
    }

    @Test
    void testMigrateNonexistentShard() {
        boolean success = migrator.migrateShard(999);
        assertFalse(success);
    }

    @Test
    void testVerifyIntegrity() throws Exception {
        createShardFiles(0, "a.bin", "b.bin");
        migrator.migrateShard(0);

        assertTrue(migrator.verifyIntegrity(0));
    }

    @Test
    void testVerifyIntegrityFailsWithoutMigration() {
        assertFalse(migrator.verifyIntegrity(999));
    }

    @Test
    void testDeleteSource() throws Exception {
        createShardFiles(0, "file.bin");
        assertTrue(Files.exists(sourceLayout.getShardDirectory(0)));

        boolean success = migrator.deleteSource(0);
        assertTrue(success);
        assertFalse(Files.exists(sourceLayout.getShardDirectory(0)));
    }

    @Test
    void testDeleteNonexistentSource() {
        boolean success = migrator.deleteSource(999);
        assertFalse(success);
    }

    @Test
    void testMigrateWithCoordinatorNotification() throws Exception {
        createShardFiles(1, "data.bin");
        ShardMigrator migratorWithRest = new ShardMigrator(sourceLayout, targetLayout, new RestClient());

        boolean success = migratorWithRest.migrateShard(1, "http://localhost:99999");
        assertTrue(success);
        assertTrue(Files.exists(targetLayout.getShardDirectory(1).resolve("data.bin")));
    }
}
