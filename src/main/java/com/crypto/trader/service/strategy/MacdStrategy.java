package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.indicator.MacdCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
public class MacdStrategy implements TradingStrategy {

    @Autowired
    private MacdCalculator macdCalculator;

    /**
     * 使用 MACD 指标对短期趋势进行判断并生成交易信号。
     *
     * <p>规则（简化版）：</p>
     * <ul>
     *   <li>当 {@code macd > signal} 且 {@code histogram > 0}：输出 BUY</li>
     *   <li>当 {@code macd < signal} 且 {@code histogram < 0}：输出 SELL</li>
     *   <li>其他情况：输出 HOLD</li>
     * </ul>
     *
     * @param symbol      交易对
     * @param klines      K 线数据（用于计算 MACD 与取最后收盘价）
     * @param onChainData 链上数据（该策略不使用，可为空）
     * @return 交易信号
     */
    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        var macd = macdCalculator.calculate(klines);
        if (macd == null) return holdSignal(symbol);

        if (macd.macd > macd.signal && macd.histogram > 0) {
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.BUY)
                    .price(klines.get(klines.size()-1).getClose().doubleValue())
                    .confidence(0.7)
                    .strategyName(getName())
                    .reason("MACD positive crossing")
                    .build();
        } else if (macd.macd < macd.signal && macd.histogram < 0) {
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.SELL)
                    .price(klines.get(klines.size()-1).getClose().doubleValue())
                    .confidence(0.7)
                    .strategyName(getName())
                    .reason("MACD negative crossing")
                    .build();
        }
        return holdSignal(symbol);
    }

    /**
     * 构造一个 HOLD 信号，用于缺少数据或无明确交易机会的场景。
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
        return "MACD";
    }
}
