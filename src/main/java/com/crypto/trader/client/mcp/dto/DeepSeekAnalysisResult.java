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

    // ========== 交易计划 ==========

    /** 操作建议: BUY_LONG(做多), SELL_SHORT(做空), HOLD(观望), CLOSE(平仓) */
    @JsonProperty("action")
    private String action;

    /** 建议入场价格下限 */
    @JsonProperty("entryPriceLow")
    private double entryPriceLow;

    /** 建议入场价格上限 */
    @JsonProperty("entryPriceHigh")
    private double entryPriceHigh;

    /** 止损价格 */
    @JsonProperty("stopLoss")
    private double stopLoss;

    /** 止盈目标1（保守） */
    @JsonProperty("takeProfit1")
    private double takeProfit1;

    /** 止盈目标2（激进） */
    @JsonProperty("takeProfit2")
    private double takeProfit2;

    /** 建议仓位比例 (0-100)，如 30 表示总资金的 30% */
    @JsonProperty("positionPercent")
    private int positionPercent;

    /** 入场条件说明（什么时候买） */
    @JsonProperty("entryCondition")
    private String entryCondition;

    /** 出场条件说明（什么时候卖） */
    @JsonProperty("exitCondition")
    private String exitCondition;

    /** 预计持仓时间 */
    @JsonProperty("holdDuration")
    private String holdDuration;

    /** 风险收益比 */
    @JsonProperty("riskRewardRatio")
    private String riskRewardRatio;

    /** 操作注意事项 */
    @JsonProperty("tradingNotes")
    private List<String> tradingNotes;
}
