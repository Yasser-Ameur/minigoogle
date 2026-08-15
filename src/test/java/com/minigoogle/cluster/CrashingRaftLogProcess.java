package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;
import com.minigoogle.storage.wal.WriteAheadLog.PersistencePoint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Test helper launched as a real subprocess by {@link RaftLogProcessCrashTest}.
 *
 * <p>Terminates with {@link Runtime#halt(int)} rather than returning or calling
 * {@code System.exit}: halt skips shutdown hooks, finalizers and any buffered
 * flush the JVM would otherwise perform, which is the closest an in-JVM test can
 * get to the machine losing power. Anything still readable afterwards is
 * genuinely on disk.</p>
 *
 * <pre>
 *   args[0]  log file path
 *   args[1]  scenario
 *   args[2]  entry count
 * </pre>
 */
public final class CrashingRaftLogProcess {

    private CrashingRaftLogProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path logPath = Path.of(args[0]);
        String scenario = args[1];
        int count = Integer.parseInt(args[2]);

        WriteAheadLog wal = new WriteAheadLog(logPath);
        RaftLog log = new RaftLog(wal);
        for (int i = 1; i <= count; i++) {
            log.append(1, ("entry-" + i).getBytes(StandardCharsets.UTF_8));
        }

        switch (scenario) {
            case "HALT_AFTER_APPENDS" -> {
                // Every append was fsynced before returning, so all of them must
                // survive an immediate abrupt termination.
                Runtime.getRuntime().halt(9);
            }
            case "HALT_DURING_TRUNCATE_BEFORE_RENAME" -> {
                wal.setFailureInjector(point -> {
                    if (point == PersistencePoint.BEFORE_RENAME) {
                        Runtime.getRuntime().halt(9);
                    }
                });
                log.truncateFrom(count / 2 + 1);
                Runtime.getRuntime().halt(1); // unreachable if the injector fired
            }
            case "HALT_DURING_TRUNCATE_AFTER_RENAME" -> {
                wal.setFailureInjector(point -> {
                    if (point == PersistencePoint.AFTER_RENAME) {
                        Runtime.getRuntime().halt(9);
                    }
                });
                log.truncateFrom(count / 2 + 1);
                Runtime.getRuntime().halt(1);
            }
            case "HALT_DURING_COMPACT_BEFORE_RENAME" -> {
                wal.setFailureInjector(point -> {
                    if (point == PersistencePoint.BEFORE_RENAME) {
                        Runtime.getRuntime().halt(9);
                    }
                });
                log.compact(count / 2, 1);
                Runtime.getRuntime().halt(1);
            }
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }
}
