package com.crypto.trader.client.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class McpResponse {
    private Choice[] choices;

    @Data
    public static class Choice {
        private Message message;

        @Data
        public static class Message {
            private String role;
            private String content;
        }
    }
}
