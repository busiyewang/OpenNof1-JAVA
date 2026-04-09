package com.crypto.trader.service.analysis;

import com.crypto.trader.client.mcp.McpClient;
import com.crypto.trader.client.mcp.dto.DeepSeekAnalysisResult;
import com.crypto.trader.client.mcp.dto.TimeframeAnalysis;
import com.crypto.trader.model.AnalysisReport;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.AnalysisReportRepository;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.service.indicator.BollingerBandsCalculator;
import com.crypto.trader.service.indicator.ChanCalculator;
import com.crypto.trader.service.indicator.MacdCalculator;
import com.crypto.trader.service.indicator.chan.ChanResult;
import com.crypto.trader.service.strategy.TradingStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 加密货币分析服务 - 核心编排器。
 * <p>
 * 加载多时间框架 K 线 + 链上指标 → 计算技术指标 → 构建 Prompt → 调用 DeepSeek → 解析结果 → 持久化报告
 */
@Service
@Slf4j
public class AnalysisService {

    @Autowired
    private KlineRepository klineRepository;

    @Autowired
    private OnChainMetricRepository onChainRepository;

    @Autowired
    private MacdCalculator macdCalculator;

    @Autowired
    private BollingerBandsCalculator bollingerCalculator;

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private AnalysisResponseParser responseParser;

    @Autowired
    private AnalysisReportRepository reportRepository;

    @Autowired
    private List<TradingStrategy> strategies;

    @Autowired
    private ChanCalculator chanCalculator;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${crypto.analysis.timeframes:1h,4h,1d}")
    private List<String> timeframes;

    @Value("${crypto.analysis.kline-count-per-timeframe:100}")
    private int klineCount;

    /** 所有需采集的链上指标名 */
    private static final List<String> ONCHAIN_METRIC_NAMES = List.of(
            "whale_transfer_volume", "nupl", "sopr",
            "exchange_net_flow", "exchange_inflow", "exchange_outflow",
            "active_addresses"
    );

    /** 市场数据指标名 */
    private static final List<String> MARKET_METRIC_NAMES = List.of(
            "funding_rate", "funding_rate_next",
            "open_interest", "open_interest_usdt",
            "fear_greed_index",
            "liquidation_long_usd", "liquidation_short_usd", "liquidation_long_short_ratio"
    );

    /**
     * 对指定交易对执行完整分析流程。
     *
     * @param symbol     交易对
     * @param reportType 报告类型
     * @return 持久化后的分析报告
     */
    public AnalysisReport analyzeSymbol(String symbol, AnalysisReport.ReportType reportType) {
        log.info("[分析] ========== {} 开始 {} 分析 ==========", symbol, reportType);
        long t0 = System.currentTimeMillis();

        // 1. 加载多时间框架 K 线并计算技术指标
        List<TimeframeAnalysis> tfAnalyses = new ArrayList<>();
        BigDecimal currentPrice = BigDecimal.ZERO;

        for (String tf : timeframes) {
            List<Kline> klines = klineRepository.findLatestKlines(symbol, tf, klineCount);
            if (klines.isEmpty()) {
                log.warn("[分析] {} {} 无 K 线数据", symbol, tf);
                continue;
            }

            // 按时间正序排列（从旧到新）
            klines.sort(Comparator.comparing(Kline::getTimestamp));

            TimeframeAnalysis analysis = buildTimeframeAnalysis(tf, klines);
            tfAnalyses.add(analysis);

            // 取最短周期的最新价格作为当前价
            if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
                currentPrice = klines.get(klines.size() - 1).getClose();
            }
        }

        if (tfAnalyses.isEmpty()) {
            log.warn("[分析] {} 所有时间框架均无数据，终止分析", symbol);
            return null;
        }

        // 2. 加载链上指标
        Map<String, List<OnChainMetric>> onChainMetrics = new LinkedHashMap<>();
        for (String metricName : ONCHAIN_METRIC_NAMES) {
            List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, metricName);
            onChainMetrics.put(metricName, metrics);
        }

        // 2.5 加载市场数据指标（资金费率、持仓量、恐惧贪婪、爆仓）
        Map<String, List<OnChainMetric>> marketMetrics = new LinkedHashMap<>();
        for (String metricName : MARKET_METRIC_NAMES) {
            List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, metricName);
            marketMetrics.put(metricName, metrics);
        }

        // 3. 运行策略引擎，获取各策略结论
        List<Kline> latestKlines = klineRepository.findLatestKlines(symbol, "1h", 100);
        latestKlines.sort(Comparator.comparing(Kline::getTimestamp));

        List<OnChainMetric> whaleMetrics = onChainRepository.findTop100BySymbol(symbol, "whale_transfer_volume");

        List<Signal> strategySignals = new ArrayList<>();
        for (TradingStrategy strategy : strategies) {
            try {
                Signal signal = strategy.evaluate(symbol, latestKlines, whaleMetrics);
                if (signal != null) {
                    strategySignals.add(signal);
                }
            } catch (Exception e) {
                log.warn("[分析] {} 策略 {} 执行异常: {}", symbol, strategy.getName(), e.getMessage());
            }
        }
        log.info("[分析] {} 策略引擎完成: {} 个策略返回信号", symbol, strategySignals.size());

        // 4. 运行缠论分析，获取详细结构
        ChanResult chanResult = chanCalculator.calculate(latestKlines);

        // 5. 构建 Prompt（含策略结论、缠论分析和市场数据）
        String prompt = promptBuilder.build(symbol, tfAnalyses, onChainMetrics, marketMetrics,
                currentPrice, strategySignals, chanResult);
        log.info("[分析] {} Prompt 构建完成，长度: {} 字符", symbol, prompt.length());

        // 6. 调用 DeepSeek
        log.info("[分析] {} 调用 DeepSeek 进行分析...", symbol);
        String rawResponse = mcpClient.analyze(prompt);

        // 7. 解析结果
        DeepSeekAnalysisResult result = responseParser.parse(rawResponse);

        // 8. 构建并保存报告
        AnalysisReport report = buildReport(symbol, reportType, result, tfAnalyses, onChainMetrics, currentPrice);
        reportRepository.save(report);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[分析] ========== {} {} 分析完成，趋势: {}, 置信度: {}, 耗时: {}ms ==========",
                symbol, reportType, report.getTrendDirection(), report.getTrendConfidence(), elapsed);

        return report;
    }

    /** 计算单个时间框架的技术指标 */
    private TimeframeAnalysis buildTimeframeAnalysis(String timeframe, List<Kline> klines) {
        Kline latest = klines.get(klines.size() - 1);
        Kline earliest = klines.get(0);

        double currentPrice = latest.getClose().doubleValue();
        double earliestPrice = earliest.getClose().doubleValue();
        double priceChange = earliestPrice > 0 ? ((currentPrice - earliestPrice) / earliestPrice) * 100 : 0;

        double latestVolume = latest.getVolume().doubleValue();
        double earliestVolume = earliest.getVolume().doubleValue();
        double volumeChange = earliestVolume > 0 ? ((latestVolume - earliestVolume) / earliestVolume) * 100 : 0;

        TimeframeAnalysis.TimeframeAnalysisBuilder builder = TimeframeAnalysis.builder()
                .timeframe(timeframe)
                .currentPrice(currentPrice)
                .priceChangePercent(priceChange)
                .volume(latestVolume)
                .volumeChangePercent(volumeChange);

        // MACD
        MacdCalculator.MacdValues macd = macdCalculator.calculate(klines);
        if (macd != null) {
            builder.macdValue(macd.macd).macdSignal(macd.signal).macdHistogram(macd.histogram);
        }

        // Bollinger Bands
        BollingerBandsCalculator.BollingerValues bb = bollingerCalculator.calculate(klines);
        if (bb != null) {
            builder.bollingerUpper(bb.upper).bollingerMiddle(bb.middle).bollingerLower(bb.lower);
        }

        return builder.build();
    }

    /** 将 DeepSeek 分析结果组装为报告实体 */
    private AnalysisReport buildReport(String symbol, AnalysisReport.ReportType reportType,
                                       DeepSeekAnalysisResult result, List<TimeframeAnalysis> tfAnalyses,
                                       Map<String, List<OnChainMetric>> onChainMetrics, BigDecimal currentPrice) {
        AnalysisReport.AnalysisReportBuilder builder = AnalysisReport.builder()
                .symbol(symbol)
                .reportType(reportType)
                .createdAt(Instant.now())
                .priceCurrent(currentPrice)
                .trendConfidence(result.getConfidence())
                .shortTermOutlook(result.getShortTermOutlook())
                .mediumTermOutlook(result.getMediumTermOutlook())
                .deepseekAnalysis(result.getReasoning());

        // 趋势方向
        builder.trendDirection(parseTrendDirection(result.getTrendDirection()));

        // 风险等级
        builder.riskAssessment(parseRiskLevel(result.getRiskLevel()));

        // 支撑位 / 阻力位
        if (result.getSupportLevel() > 0) {
            builder.priceSupport(BigDecimal.valueOf(result.getSupportLevel()));
        }
        if (result.getResistanceLevel() > 0) {
            builder.priceResistance(BigDecimal.valueOf(result.getResistanceLevel()));
        }

        // 交易计划
        builder.tradeAction(result.getAction());
        builder.positionPercent(result.getPositionPercent());

        if (result.getEntryPriceLow() > 0 || result.getEntryPriceHigh() > 0) {
            builder.entryPriceRange(String.format("{\"low\":%.2f,\"high\":%.2f}",
                    result.getEntryPriceLow(), result.getEntryPriceHigh()));
        }
        if (result.getStopLoss() > 0) {
            builder.stopLoss(BigDecimal.valueOf(result.getStopLoss()));
        }
        if (result.getTakeProfit1() > 0) {
            builder.takeProfit1(BigDecimal.valueOf(result.getTakeProfit1()));
        }
        if (result.getTakeProfit2() > 0) {
            builder.takeProfit2(BigDecimal.valueOf(result.getTakeProfit2()));
        }

        // 交易计划 JSON
        try {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("entryCondition", result.getEntryCondition());
            plan.put("exitCondition", result.getExitCondition());
            plan.put("holdDuration", result.getHoldDuration());
            plan.put("riskRewardRatio", result.getRiskRewardRatio());
            plan.put("tradingNotes", result.getTradingNotes());
            builder.tradingPlan(objectMapper.writeValueAsString(plan));
        } catch (Exception ignored) {}

        // JSON 字段
        try {
            builder.timeframesSummary(objectMapper.writeValueAsString(tfAnalyses));
            builder.keyIndicators(objectMapper.writeValueAsString(result.getKeyIndicatorAnalysis()));
            builder.onChainSummary(objectMapper.writeValueAsString(result.getOnChainInsights()));
            builder.riskFactors(objectMapper.writeValueAsString(result.getRiskFactors()));
        } catch (Exception e) {
            log.warn("[分析] JSON 序列化失败: {}", e.getMessage());
        }

        return builder.build();
    }

    private AnalysisReport.TrendDirection parseTrendDirection(String direction) {
        if (direction == null) return AnalysisReport.TrendDirection.NEUTRAL;
        try {
            return AnalysisReport.TrendDirection.valueOf(direction.toUpperCase());
        } catch (IllegalArgumentException e) {
            if (direction.toUpperCase().contains("BULL")) return AnalysisReport.TrendDirection.BULLISH;
            if (direction.toUpperCase().contains("BEAR")) return AnalysisReport.TrendDirection.BEARISH;
            return AnalysisReport.TrendDirection.NEUTRAL;
        }
    }

    private AnalysisReport.RiskLevel parseRiskLevel(String level) {
        if (level == null) return AnalysisReport.RiskLevel.MODERATE;
        try {
            return AnalysisReport.RiskLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AnalysisReport.RiskLevel.MODERATE;
        }
    }
}
