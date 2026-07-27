package com.minigoogle.cluster.migration;

import com.minigoogle.network.http.RestClient;
import com.minigoogle.storage.filesystem.StorageLayout;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public class ShardMigrator {

    private final StorageLayout sourceLayout;
    private final StorageLayout targetLayout;
    private final RestClient restClient;

    public ShardMigrator(StorageLayout sourceLayout, StorageLayout targetLayout,
                         RestClient restClient) {
        this.sourceLayout = sourceLayout;
        this.targetLayout = targetLayout;
        this.restClient = restClient;
    }

    public boolean migrateShard(int shardId) {
        Path sourceDir = sourceLayout.getShardDirectory(shardId);
        Path targetDir = targetLayout.getShardDirectory(shardId);

        if (!Files.exists(sourceDir)) {
            return false;
        }

        try {
            Files.createDirectories(targetDir);
            copyDirectory(sourceDir, targetDir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean migrateShard(int shardId, String coordinatorUrl) {
        boolean success = migrateShard(shardId);
        if (success && coordinatorUrl != null) {
            try {
                String payload = String.format("{\"shardId\":%d,\"status\":\"MIGRATED\"}", shardId);
                restClient.post(coordinatorUrl + "/api/v1/cluster/shard-migrated", payload);
            } catch (Exception e) {
                // Notification failed but migration succeeded
            }
        }
        return success;
    }

    public boolean verifyIntegrity(int shardId) {
        Path sourceDir = sourceLayout.getShardDirectory(shardId);
        Path targetDir = targetLayout.getShardDirectory(shardId);

        if (!Files.exists(sourceDir) || !Files.exists(targetDir)) {
            return false;
        }

        try {
            return compareDirectories(sourceDir, targetDir);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteSource(int shardId) {
        Path sourceDir = sourceLayout.getShardDirectory(shardId);
        try {
            deleteDirectory(sourceDir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path dest = target.resolve(source.relativize(dir));
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean compareDirectories(Path source, Path target) throws IOException {
        long[] sourceSize = {0};
        long[] targetSize = {0};

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sourceSize[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });

        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                targetSize[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });

        return sourceSize[0] == targetSize[0];
    }
}
