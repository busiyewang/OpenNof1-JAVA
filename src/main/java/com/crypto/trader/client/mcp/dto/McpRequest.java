package com.crypto.trader.client.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpRequest {
    private String model;
    private Message[] messages;
    private Double temperature;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
