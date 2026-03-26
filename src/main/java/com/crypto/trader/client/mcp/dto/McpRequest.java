package com.crypto.trader.client.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpRequest {
    private String model;
    private Message[] messages;
    private Double temperature;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
