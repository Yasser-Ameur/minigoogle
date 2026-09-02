package com.minigoogle.cluster.placement;

import com.minigoogle.cluster.MembershipListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges gossip membership events to document placement: whenever a node
 * joins or leaves, ring ownership shifts, so every local document is
 * re-checked against its current owners and delivered to any owner that is
 * missing a copy.
 *
 * <p>Membership events arrive in bursts (a node joining triggers one event
 * per gossip round until convergence), so repair is debounced onto a single
 * daemon thread: each event resets a 1 second timer, and only the timer that
 * survives quietly runs the repair pass. Delivery is idempotent by URL (see
 * {@link DocumentIngest}), so a repair pass that runs more than once, or
 * races a direct {@link com.minigoogle.cluster.ClusterNode#place}, is always
 * safe. Nothing is ever deleted: an owner that only just lost ownership keeps
 * its stale copy until the operator reclaims the space.
 */
public final class PlacementRepairListener implements MembershipListener {

    private static final Logger logger = Logger.getLogger(PlacementRepairListener.class.getName());
    private static final long DEBOUNCE_MS = 1000;

    private final DocumentPlacement placement;
    private final LocalDocuments localDocuments;
    private final HttpIngestTransport transport;
    private final String localNodeId;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pending;

    public PlacementRepairListener(DocumentPlacement placement, LocalDocuments localDocuments,
                                    HttpIngestTransport transport, String localNodeId) {
        this.placement = placement;
        this.localDocuments = localDocuments;
        this.transport = transport;
        this.localNodeId = localNodeId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "placement-repair-" + localNodeId);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onNodeJoined(String nodeId) {
        scheduleRepair();
    }

    @Override
    public void onNodeLeft(String nodeId) {
        scheduleRepair();
    }

    private synchronized void scheduleRepair() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::repair, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void repair() {
        for (IngestedDocument doc : localDocuments.all()) {
            java.util.List<String> owners = placement.owners(doc.url().toString());
            if (!owners.contains(localNodeId)) {
                continue;
            }
            for (String owner : owners) {
                if (owner.equals(localNodeId)) {
                    continue;
                }
                try {
                    transport.ingest(owner, doc).exceptionally(e -> {
                        logger.log(Level.WARNING, "Repair delivery of " + doc.url() + " to " + owner + " failed", e);
                        return null;
                    });
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Repair delivery of " + doc.url() + " to " + owner + " failed", e);
                }
            }
        }
    }

    /** Stops the debounce timer thread. */
    public void shutdown() {
        scheduler.shutdown();
    }
}
