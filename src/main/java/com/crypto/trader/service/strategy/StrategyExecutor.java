package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.repository.SignalRepository;
import com.crypto.trader.service.notifier.Notifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 策略执行器（简化版 - 仅生成信号和通知，不执行交易）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StrategyExecutor {

    private final List<TradingStrategy> strategies;

    private final List<Notifier> notifiers;

    private final KlineRepository klineRepository;

    private final OnChainMetricRepository onChainRepository;

    private final SignalRepository signalRepository;

    public void execute(String symbol) {
        log.info("[策略执行] -------- {} 开始策略评估 --------", symbol);

        List<Kline> klinesDesc = klineRepository.findLatestKlines(symbol, "1h", 100);
        List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, "whale_transfer_volume");

        if (klinesDesc == null || klinesDesc.isEmpty()) {
            log.warn("[策略执行] {} 数据库中无K线数据，跳过", symbol);
            return;
        }

        List<Kline> klines = klinesDesc.stream()
                .sorted(Comparator.comparing(Kline::getTimestamp))
                .collect(Collectors.toList());

        List<Signal> signals = strategies.stream()
                .map(s -> s.evaluate(symbol, klines, metrics))
                .filter(s -> s.getAction() != Signal.Action.HOLD)
                .collect(Collectors.toList());

        if (signals.isEmpty()) {
            log.info("[策略执行] {} 所有策略结果为 HOLD", symbol);
            return;
        }

        Signal finalSignal = signals.stream()
                .max(Comparator.comparingDouble(Signal::getConfidence))
                .orElse(null);

        if (finalSignal == null) return;

        log.info("[策略执行] {} 最终信号: {} | 策略: {} | 置信度: {}",
                symbol, finalSignal.getAction(), finalSignal.getStrategyName(),
                String.format("%.2f", finalSignal.getConfidence()));

        try {
            signalRepository.save(finalSignal);
        } catch (Exception e) {
            log.error("[策略执行] {} 信号保存失败: {}", symbol, e.getMessage(), e);
        }

        notifiers.forEach(n -> {
            try {
                n.notify(finalSignal);
            } catch (Exception e) {
                log.error("[策略执行] {} 通知发送失败: {}", symbol, e.getMessage());
            }
        });
    }
}
