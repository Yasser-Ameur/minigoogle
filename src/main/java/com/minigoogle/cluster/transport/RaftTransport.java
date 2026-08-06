package com.minigoogle.cluster.transport;

import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.ReadIndexRequest;
import com.minigoogle.cluster.transport.dto.ReadIndexResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;

import java.util.concurrent.CompletableFuture;

public interface RaftTransport extends ClusterTransport {
    CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request);
    CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request);
    CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId, InstallSnapshotRequest request);
    CompletableFuture<ReadIndexResponse> sendReadIndex(String targetNodeId, ReadIndexRequest request);
}
