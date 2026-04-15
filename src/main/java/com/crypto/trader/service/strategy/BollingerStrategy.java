package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.BollingerBandsCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class BollingerStrategy implements TradingStrategy {

    private final BollingerBandsCalculator bollingerCalculator;

    /**
     * 使用布林带判断价格偏离并生成交易信号。
     *
     * <p>规则（简化版）：</p>
     * <ul>
     *   <li>收盘价高于上轨：倾向超买，输出 SELL</li>
     *   <li>收盘价低于下轨：倾向超卖，输出 BUY</li>
     *   <li>其他情况：输出 HOLD</li>
     * </ul>
     *
     * @param symbol      交易对
     * @param klines      K 线数据（用于计算布林带与取最后收盘价）
     * @param onChainData 链上数据（该策略不使用，可为空）
     * @return 交易信号
     */
    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        var bb = bollingerCalculator.calculate(klines);
        if (bb == null) {
            log.debug("[Bollinger] {} 布林带计算返回 null（数据不足），返回 HOLD", symbol);
            return holdSignal(symbol);
        }

        double lastClose = klines.get(klines.size()-1).getClose().doubleValue();
        log.info("[Bollinger] {} 指标值: 上轨={} 中轨={} 下轨={} 收盘价={}",
                symbol, String.format("%.2f", bb.upper), String.format("%.2f", bb.middle),
                String.format("%.2f", bb.lower), String.format("%.2f", lastClose));

        if (lastClose > bb.upper) {
            log.info("[Bollinger] {} 价格突破上轨（超买）-> SELL", symbol);
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.SELL)
                    .price(lastClose)
                    .confidence(0.6)
                    .strategyName(getName())
                    .reason("Price above upper Bollinger Band")
                    .build();
        } else if (lastClose < bb.lower) {
            log.info("[Bollinger] {} 价格跌破下轨（超卖）-> BUY", symbol);
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.BUY)
                    .price(lastClose)
                    .confidence(0.6)
                    .strategyName(getName())
                    .reason("Price below lower Bollinger Band")
                    .build();
        }

        log.debug("[Bollinger] {} 价格在布林带内 -> HOLD", symbol);
        return holdSignal(symbol);
    }

    /**
     * 构造 HOLD 信号。
     *
     * @param symbol 交易对
     * @return HOLD 信号
     */
    private Signal holdSignal(String symbol) {
        return Signal.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .action(Signal.Action.HOLD)
                .strategyName(getName())
                .build();
    }

    /**
     * @return 策略名称
     */
    @Override
    public String getName() {
        return "Bollinger";
    }
}
