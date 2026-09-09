package com.rummy.gameservice.matchmaking;

import java.time.Instant;

public class MatchmakingResponse {
    private String ticketId;
    private String status;
    private String matchedTableId;
    private String matchedServerId;
    private long queueTimeSeconds;

    public MatchmakingResponse() {}

    public static MatchmakingResponse fromTicket(MatchmakingTicket ticket) {
        MatchmakingResponse response = new MatchmakingResponse();
        response.setTicketId(ticket.getTicketId());
        response.setStatus(ticket.getStatus().name());
        response.setMatchedTableId(ticket.getMatchedTableId());
        response.setMatchedServerId(ticket.getMatchedServerId());
        response.setQueueTimeSeconds(Instant.now().getEpochSecond() - ticket.getCreatedAt().getEpochSecond());
        return response;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMatchedTableId() {
        return matchedTableId;
    }

    public void setMatchedTableId(String matchedTableId) {
        this.matchedTableId = matchedTableId;
    }

    public String getMatchedServerId() {
        return matchedServerId;
    }

    public void setMatchedServerId(String matchedServerId) {
        this.matchedServerId = matchedServerId;
    }

    public long getQueueTimeSeconds() {
        return queueTimeSeconds;
    }

    public void setQueueTimeSeconds(long queueTimeSeconds) {
        this.queueTimeSeconds = queueTimeSeconds;
    }
}
