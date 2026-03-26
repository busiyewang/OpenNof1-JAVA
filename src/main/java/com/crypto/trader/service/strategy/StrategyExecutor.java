package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.repository.SignalRepository;
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
    private List<Notifier> notifiers;

    @Autowired
    private OrderExecutor orderExecutor;

    @Autowired
    private KlineRepository klineRepository;

    @Autowired
    private OnChainMetricRepository onChainRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Value("${crypto.trading.mode:paper}")
    private String tradingMode;

    public void execute(String symbol) {
        log.info("[策略执行] -------- {} 开始策略评估 --------", symbol);
        long startTime = System.currentTimeMillis();

        // 1. 加载数据
        List<Kline> klinesDesc = klineRepository.findTop100BySymbolOrderByTimestampDesc(symbol);
        List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, "whale_transaction_count");

        if (klinesDesc == null || klinesDesc.isEmpty()) {
            log.warn("[策略执行] {} 数据库中无K线数据，跳过", symbol);
            return;
        }

        log.info("[策略执行] {} 加载数据: K线 {} 条, 链上指标 {} 条, 最新价格: {}",
                symbol, klinesDesc.size(), metrics.size(),
                klinesDesc.get(0).getClose());

        List<Kline> klines = klinesDesc.stream()
                .sorted(Comparator.comparing(Kline::getTimestamp))
                .collect(Collectors.toList());

        // 2. 执行所有策略
        log.info("[策略执行] {} 开始运行 {} 个策略: {}", symbol, strategies.size(),
                strategies.stream().map(TradingStrategy::getName).collect(Collectors.joining(", ")));

        List<Signal> signals = strategies.stream()
                .map(s -> {
                    long t0 = System.currentTimeMillis();
                    Signal signal = s.evaluate(symbol, klines, metrics);
                    long elapsed = System.currentTimeMillis() - t0;
                    log.info("[策略执行] {} [{}] 结果: {} 置信度: {} 耗时: {}ms",
                            symbol, s.getName(), signal.getAction(),
                            String.format("%.2f", signal.getConfidence()), elapsed);
                    return signal;
                })
                .filter(s -> s.getAction() != Signal.Action.HOLD)
                .collect(Collectors.toList());

        if (signals.isEmpty()) {
            log.info("[策略执行] {} 所有策略结果为 HOLD，无交易信号", symbol);
            return;
        }

        // 3. 融合决策
        Signal finalSignal = signals.stream()
                .max(Comparator.comparingDouble(Signal::getConfidence))
                .orElse(null);

        if (finalSignal == null) return;

        log.info("[策略执行] {} 最终信号: {} | 策略: {} | 置信度: {} | 价格: {} | 原因: {}",
                symbol, finalSignal.getAction(), finalSignal.getStrategyName(),
                String.format("%.2f", finalSignal.getConfidence()),
                String.format("%.2f", finalSignal.getPrice()),
                finalSignal.getReason());

        // 4. 保存信号
        try {
            signalRepository.save(finalSignal);
            log.info("[策略执行] {} 信号已保存到数据库", symbol);
        } catch (Exception e) {
            log.error("[策略执行] {} 信号保存失败: {}", symbol, e.getMessage(), e);
        }

        // 5. 发送通知
        log.info("[策略执行] {} 发送通知（{} 个通知渠道）", symbol, notifiers.size());
        notifiers.forEach(n -> {
            try {
                n.notify(finalSignal);
            } catch (Exception e) {
                log.error("[策略执行] {} 通知发送失败 [{}]: {}", symbol, n.getClass().getSimpleName(), e.getMessage());
            }
        });

        // 6. 执行交易
        if (finalSignal.getAction() != Signal.Action.HOLD && "live".equals(tradingMode)) {
            log.info("[策略执行] {} 实盘模式，开始执行交易: {}", symbol, finalSignal.getAction());
            orderExecutor.execute(finalSignal);
        } else {
            log.info("[策略执行] {} 模拟模式，不执行实际交易: {} {} 价格={}",
                    symbol, finalSignal.getAction(), finalSignal.getStrategyName(), finalSignal.getPrice());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[策略执行] -------- {} 策略评估结束, 耗时: {}ms --------", symbol, elapsed);
    }
}
