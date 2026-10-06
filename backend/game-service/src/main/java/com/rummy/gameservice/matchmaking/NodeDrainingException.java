package com.rummy.gameservice.matchmaking;

/** This node is shutting down and takes no new matches; the client should retry (another node will answer). */
public class NodeDrainingException extends IllegalStateException {

    public NodeDrainingException() {
        super("This server is restarting. Please try again.");
    }
}
