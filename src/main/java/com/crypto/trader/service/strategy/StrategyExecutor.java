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
        List<Kline> klinesDesc = klineRepository.findTop100BySymbolOrderByTimestampDesc(symbol);
        List<OnChainMetric> metrics = onChainRepository.findTop100BySymbol(symbol, "whale_transaction_count");

        if (klinesDesc == null || klinesDesc.isEmpty()) {
            log.debug("No klines in DB for {}", symbol);
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
            log.debug("No signals for {}", symbol);
            return;
        }

        Signal finalSignal = signals.stream()
                .max(Comparator.comparingDouble(Signal::getConfidence))
                .orElse(null);

        if (finalSignal == null) return;

        // 保存信号到数据库（供每日摘要使用）
        try {
            signalRepository.save(finalSignal);
        } catch (Exception e) {
            log.error("Failed to save signal for {}", symbol, e);
        }

        // 发送实时告警（邮件 + Telegram）
        notifiers.forEach(n -> n.notify(finalSignal));

        // 执行交易
        if (finalSignal.getAction() != Signal.Action.HOLD && "live".equals(tradingMode)) {
            orderExecutor.execute(finalSignal);
        } else {
            log.info("[Paper] Would execute: {}", finalSignal);
        }
    }
}
