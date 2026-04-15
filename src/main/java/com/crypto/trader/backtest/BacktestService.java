package com.crypto.trader.backtest;

import com.crypto.trader.backtest.engine.BacktestEngine;
import com.crypto.trader.backtest.engine.MetricsCalculator;
import com.crypto.trader.backtest.model.BacktestReport;
import com.crypto.trader.backtest.model.BacktestRequest;
import com.crypto.trader.backtest.model.Trade;
import com.crypto.trader.model.Kline;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.service.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 回测服务：编排 K 线加载、策略执行、指标计算。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestService {

    private final KlineRepository klineRepository;

    private final List<TradingStrategy> allStrategies;

    /**
     * 执行回测。
     *
     * @return 每个策略的回测报告列表；投票模式下只返回一个报告
     */
    public List<BacktestReport> runBacktest(BacktestRequest request) {
        // 1. 解析时间范围
        Instant start = LocalDate.parse(request.getStartDate()).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = request.getEndDate() != null
                ? LocalDate.parse(request.getEndDate()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.now();

        // 2. 加载 K 线
        List<Kline> klines = klineRepository.findBySymbolAndIntervalAndTimestampBetween(
                request.getSymbol(), request.getInterval(), start, end);
        klines.sort(Comparator.comparing(Kline::getTimestamp));

        if (klines.size() < request.getWindowSize() + 10) {
            log.warn("[回测] K线数据不足: 需要至少 {} 根，实际 {} 根",
                    request.getWindowSize() + 10, klines.size());
            return List.of();
        }

        log.info("[回测] 加载 {} 根 K 线: {} {} [{} ~ {}]",
                klines.size(), request.getSymbol(), request.getInterval(),
                klines.get(0).getTimestamp(), klines.get(klines.size() - 1).getTimestamp());

        // 3. 筛选策略
        List<TradingStrategy> strategies = filterStrategies(request.getStrategies());

        if (strategies.isEmpty()) {
            log.warn("[回测] 无匹配策略");
            return List.of();
        }

        // 4. 执行回测
        if (request.isVoteMode()) {
            return List.of(runVoteBacktest(request, klines, strategies));
        } else {
            return runIndividualBacktests(request, klines, strategies);
        }
    }

    /**
     * 逐策略独立回测。
     */
    private List<BacktestReport> runIndividualBacktests(BacktestRequest request, List<Kline> klines,
                                                        List<TradingStrategy> strategies) {
        List<BacktestReport> reports = new ArrayList<>();

        for (TradingStrategy strategy : strategies) {
            log.info("[回测] 执行策略: {}", strategy.getName());

            BacktestEngine engine = new BacktestEngine(strategy, request);
            List<Trade> trades = engine.run(klines);

            BacktestReport report = MetricsCalculator.calculate(
                    strategy.getName(), request, trades, engine.getFinalCapital(), klines.size());
            reports.add(report);

            log.info("[回测] {} 完成: 交易{}次, 胜率{}%, 总收益{}%, 最大回撤{}%",
                    strategy.getName(), report.getTotalTrades(),
                    String.format("%.1f", report.getWinRate()),
                    String.format("%.2f", report.getTotalReturnPercent()),
                    String.format("%.2f", report.getMaxDrawdownPercent()));
        }

        // 按总收益率降序排列
        reports.sort(Comparator.comparingDouble(BacktestReport::getTotalReturnPercent).reversed());
        return reports;
    }

    /**
     * 多策略投票回测：多数策略同方向信号才执行。
     */
    private BacktestReport runVoteBacktest(BacktestRequest request, List<Kline> klines,
                                            List<TradingStrategy> strategies) {
        int threshold = request.getVoteThreshold() > 0
                ? request.getVoteThreshold()
                : (strategies.size() / 2) + 1; // 过半

        log.info("[回测] 投票模式: {}个策略, 阈值={}", strategies.size(), threshold);

        // 创建一个"投票策略"包装器
        TradingStrategy voteStrategy = new TradingStrategy() {
            @Override
            public Signal evaluate(String symbol, List<Kline> klineWindow, List<com.crypto.trader.model.OnChainMetric> onChainData) {
                int buyVotes = 0, sellVotes = 0;
                double maxConfidence = 0;
                String reasons = "";

                for (TradingStrategy s : strategies) {
                    try {
                        Signal signal = s.evaluate(symbol, klineWindow, onChainData);
                        if (signal == null) continue;
                        if (signal.getAction() == Signal.Action.BUY) {
                            buyVotes++;
                            maxConfidence = Math.max(maxConfidence, signal.getConfidence());
                        } else if (signal.getAction() == Signal.Action.SELL) {
                            sellVotes++;
                            maxConfidence = Math.max(maxConfidence, signal.getConfidence());
                        }
                    } catch (Exception ignored) {}
                }

                Kline last = klineWindow.get(klineWindow.size() - 1);
                if (buyVotes >= threshold) {
                    return Signal.builder()
                            .symbol(symbol).timestamp(last.getTimestamp())
                            .action(Signal.Action.BUY).price(last.getClose().doubleValue())
                            .confidence(maxConfidence).strategyName("VOTE")
                            .reason(String.format("投票BUY: %d/%d", buyVotes, strategies.size()))
                            .build();
                } else if (sellVotes >= threshold) {
                    return Signal.builder()
                            .symbol(symbol).timestamp(last.getTimestamp())
                            .action(Signal.Action.SELL).price(last.getClose().doubleValue())
                            .confidence(maxConfidence).strategyName("VOTE")
                            .reason(String.format("投票SELL: %d/%d", sellVotes, strategies.size()))
                            .build();
                }

                return Signal.builder()
                        .symbol(symbol).timestamp(last.getTimestamp())
                        .action(Signal.Action.HOLD).strategyName("VOTE").build();
            }

            @Override
            public String getName() {
                return "VOTE(" + strategies.stream().map(TradingStrategy::getName)
                        .collect(Collectors.joining("+")) + ")";
            }
        };

        BacktestEngine engine = new BacktestEngine(voteStrategy, request);
        List<Trade> trades = engine.run(klines);

        return MetricsCalculator.calculate(
                voteStrategy.getName(), request, trades, engine.getFinalCapital(), klines.size());
    }

    private List<TradingStrategy> filterStrategies(List<String> names) {
        if (names == null || names.isEmpty()) {
            return allStrategies;
        }
        Set<String> nameSet = names.stream().map(String::toUpperCase).collect(Collectors.toSet());
        return allStrategies.stream()
                .filter(s -> nameSet.contains(s.getName().toUpperCase()))
                .collect(Collectors.toList());
    }
}
