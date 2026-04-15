package com.crypto.trader.service.analysis;

import com.crypto.trader.client.mcp.dto.DeepSeekAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 DeepSeek 返回的 JSON 分析结果。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnalysisResponseParser {

    private final ObjectMapper objectMapper;

    /** 匹配 markdown 代码块中的 JSON */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", Pattern.DOTALL);
    /** 匹配裸 JSON 对象 */
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{.*})", Pattern.DOTALL);

    /**
     * 解析 DeepSeek 响应文本为结构化分析结果。
     *
     * @param responseText DeepSeek 返回的原始文本
     * @return 解析后的分析结果；解析失败时返回带默认值的结果
     */
    public DeepSeekAnalysisResult parse(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            log.warn("[AnalysisParser] 响应为空，返回默认结果");
            return defaultResult();
        }

        String json = extractJson(responseText);
        if (json == null) {
            log.warn("[AnalysisParser] 无法从响应中提取 JSON，原始文本长度: {}", responseText.length());
            return defaultResultWithReasoning(responseText);
        }

        try {
            DeepSeekAnalysisResult result = objectMapper.readValue(json, DeepSeekAnalysisResult.class);
            validate(result);
            log.debug("[AnalysisParser] 解析成功: trend={}, confidence={}", result.getTrendDirection(), result.getConfidence());
            return result;
        } catch (Exception e) {
            log.warn("[AnalysisParser] JSON 解析失败: {}，尝试回退处理", e.getMessage());
            return defaultResultWithReasoning(responseText);
        }
    }

    /** 从响应文本中提取 JSON 字符串 */
    private String extractJson(String text) {
        // 尝试 markdown 代码块
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(text);
        if (blockMatcher.find()) {
            return blockMatcher.group(1);
        }
        // 尝试裸 JSON
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(text);
        if (objectMatcher.find()) {
            return objectMatcher.group(1);
        }
        return null;
    }

    /** 验证并修正数值范围 */
    private void validate(DeepSeekAnalysisResult result) {
        if (result.getConfidence() < 0) result.setConfidence(0);
        if (result.getConfidence() > 1) result.setConfidence(1);

        if (result.getTrendDirection() == null) result.setTrendDirection("NEUTRAL");
        if (result.getRiskLevel() == null) result.setRiskLevel("MODERATE");
        if (result.getRiskFactors() == null) result.setRiskFactors(List.of());
        if (result.getKeyIndicatorAnalysis() == null) result.setKeyIndicatorAnalysis(Map.of());
        if (result.getOnChainInsights() == null) result.setOnChainInsights(Map.of());
    }

    /** 返回默认分析结果 */
    private DeepSeekAnalysisResult defaultResult() {
        DeepSeekAnalysisResult result = new DeepSeekAnalysisResult();
        result.setTrendDirection("NEUTRAL");
        result.setConfidence(0.5);
        result.setRiskLevel("MODERATE");
        result.setShortTermOutlook("数据不足，无法给出短期展望");
        result.setMediumTermOutlook("数据不足，无法给出中期展望");
        result.setRiskFactors(List.of("AI 分析未能正常返回结果"));
        result.setKeyIndicatorAnalysis(Map.of());
        result.setOnChainInsights(Map.of());
        result.setReasoning("DeepSeek 未返回有效分析结果");
        return result;
    }

    /** 解析失败时，将原始文本作为 reasoning 保留 */
    private DeepSeekAnalysisResult defaultResultWithReasoning(String rawText) {
        DeepSeekAnalysisResult result = defaultResult();
        result.setReasoning(rawText);
        return result;
    }
}
