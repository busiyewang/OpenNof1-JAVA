package com.crypto.trader.client.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepSeekAnalysisResult {

    @JsonProperty("trendDirection")
    private String trendDirection;

    @JsonProperty("confidence")
    private double confidence;

    @JsonProperty("shortTermOutlook")
    private String shortTermOutlook;

    @JsonProperty("mediumTermOutlook")
    private String mediumTermOutlook;

    @JsonProperty("supportLevel")
    private double supportLevel;

    @JsonProperty("resistanceLevel")
    private double resistanceLevel;

    @JsonProperty("riskLevel")
    private String riskLevel;

    @JsonProperty("riskFactors")
    private List<String> riskFactors;

    @JsonProperty("keyIndicatorAnalysis")
    private Map<String, String> keyIndicatorAnalysis;

    @JsonProperty("onChainInsights")
    private Map<String, String> onChainInsights;

    @JsonProperty("reasoning")
    private String reasoning;
}
