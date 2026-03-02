package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.service.executor.OrderExecutor;
import com.crypto.trader.service.notifier.Notifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class StrategyExecutor {

    @Autowired
    private List<TradingStrategy> strategies;

    @Autowired
    private Notifier notifier;

    @Autowired
    private OrderExecutor orderExecutor;

    @Autowired
    private KlineRepository klineRepository;

    @Autowired
    private OnChainMetricRepository onChainRepository;

    @Value("${crypto.trading.mode:paper}")
    private String tradingMode;

    /**
     * 对指定交易对执行所有已注册策略并做一次融合决策。
     *
     * <p>流程：</p>
     * <ul>
     *   <li>从数据库读取最近行情与链上指标</li>
     *   <li>遍历 {@link TradingStrategy} 列表生成信号，并过滤 HOLD</li>
     *   <li>选择置信度最高的信号作为最终信号</li>
     *   <li>发送通知；当 {@code crypto.trading.mode=live} 时触发下单执行，否则仅记录日志（paper 模式）</li>
     * </ul>
     *
     * <p>该方法可能被并发调用（定时任务对 watch list 使用并行流），实现与依赖组件应保证并发安全。</p>
     *
     * @param symbol 交易对（如 {@code BTCUSDT}）
     */
    public void execute(String symbol) {
        // 获取最新数据（注意：Repository 方法按 timestamp DESC 返回）
        List<Kline> klinesDesc = klineRepository.findTop100BySymbolOrderByTimestampDesc(symbol);
        List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, "whale_transaction_count");

        if (klinesDesc == null || klinesDesc.isEmpty()) {
            log.debug("No klines in DB for {}", symbol);
            return;
        }

        // 指标/策略计算通常依赖“时间正序”的序列；同时策略里会用最后一个元素当作最新价格。
        // 因此这里统一将 K 线按 timestamp ASC 排序后再传给各策略，避免“取到最旧价格”的隐性 bug。
        List<Kline> klines = klinesDesc.stream()
                .sorted(Comparator.comparing(Kline::getTimestamp))
                .collect(Collectors.toList());

        // 聚合所有策略信号
        List<Signal> signals = strategies.stream()
                .map(s -> s.evaluate(symbol, klines, metrics))
                .filter(s -> s.getAction() != Signal.Action.HOLD)
                .collect(Collectors.toList());

        if (signals.isEmpty()) {
            log.debug("No signals for {}", symbol);
            return;
        }

        // 融合策略：选择置信度最高的信号，或者根据加权平均决定（这里简单选最高置信度）
        Signal finalSignal = signals.stream()
                .max(Comparator.comparingDouble(Signal::getConfidence))
                .orElse(null);

        if (finalSignal == null) return;

        // 发送通知
        notifier.notify(finalSignal);

        // 执行交易 (如果启用自动交易)
        if (finalSignal.getAction() != Signal.Action.HOLD && "live".equals(tradingMode)) {
            orderExecutor.execute(finalSignal);
        } else {
            log.info("[Paper] Would execute: {}", finalSignal);
        }
    }
}
