package com.minigoogle.cluster.transport;

import java.net.URI;

public interface NodeDirectory {
    URI getBaseUri(String nodeId);
}
