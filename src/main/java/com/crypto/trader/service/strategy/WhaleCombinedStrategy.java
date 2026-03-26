package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import com.crypto.trader.service.analyzer.WhaleAnalyzer;
import com.crypto.trader.service.indicator.MacdCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class WhaleCombinedStrategy implements TradingStrategy {

    @Autowired
    private WhaleAnalyzer whaleAnalyzer;

    @Autowired
    private MacdCalculator macdCalculator;

    /**
     * 结合巨鲸行为与 MACD 的联合信号策略。
     *
     * <p>规则（简化版）：</p>
     * <ul>
     *   <li>巨鲸积累（{@code whaleSignal == 1}）且 MACD 偏多：BUY</li>
     *   <li>巨鲸派发（{@code whaleSignal == -1}）且 MACD 偏空：SELL</li>
     *   <li>其他情况：HOLD</li>
     * </ul>
     *
     * @param symbol      交易对
     * @param klines      K 线数据（用于计算 MACD 与取最后收盘价）
     * @param onChainData 链上数据（当前实现通过 {@link WhaleAnalyzer} 获取信号，未直接使用该参数）
     * @return 交易信号
     */
    @Override
    public Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData) {
        log.info("[WhaleCombined] {} 开始分析鲸鱼活动...", symbol);
        int whaleSignal = whaleAnalyzer.analyzeWhaleActivity(symbol);
        var macd = macdCalculator.calculate(klines);

        String whaleDesc = whaleSignal == 1 ? "积累" : whaleSignal == -1 ? "派发" : "中性";
        String macdDesc = macd == null ? "无数据" :
                macd.macd > macd.signal ? "偏多" : "偏空";

        log.info("[WhaleCombined] {} 鲸鱼信号: {} ({}), MACD方向: {}",
                symbol, whaleSignal, whaleDesc, macdDesc);

        if (whaleSignal == 1 && macd != null && macd.macd > macd.signal) {
            log.info("[WhaleCombined] {} 鲸鱼积累 + MACD偏多 -> BUY (置信度: 0.85)", symbol);
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.BUY)
                    .price(klines.get(klines.size()-1).getClose().doubleValue())
                    .confidence(0.85)
                    .strategyName(getName())
                    .reason("Whale accumulation + MACD bullish")
                    .build();
        } else if (whaleSignal == -1 && macd != null && macd.macd < macd.signal) {
            log.info("[WhaleCombined] {} 鲸鱼派发 + MACD偏空 -> SELL (置信度: 0.85)", symbol);
            return Signal.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .action(Signal.Action.SELL)
                    .price(klines.get(klines.size()-1).getClose().doubleValue())
                    .confidence(0.85)
                    .strategyName(getName())
                    .reason("Whale distribution + MACD bearish")
                    .build();
        }

        log.debug("[WhaleCombined] {} 鲸鱼与MACD未形成共振 -> HOLD", symbol);
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

    @Override
    public String getName() {
        return "WhaleCombined";
    }
}
