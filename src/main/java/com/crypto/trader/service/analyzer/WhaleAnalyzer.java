package com.crypto.trader.service.analyzer;

import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.OnChainMetricRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class WhaleAnalyzer {

    @Autowired
    private OnChainMetricRepository metricRepository;

    /**
     * 通过链上指标的“近期均值 vs 之前均值”来粗略判断巨鲸行为。
     *
     * <p>返回值约定：</p>
     * <ul>
     *   <li>{@code 1}：近期显著高于之前，视为“巨鲸活动增强/可能积累”</li>
     *   <li>{@code -1}：近期显著低于之前，视为“巨鲸活动减弱/可能派发”</li>
     *   <li>{@code 0}：无明显变化或数据不足</li>
     * </ul>
     *
     * <p>注意：该规则非常简化，真实场景通常还需要结合价格、成交量、转账规模分布等维度。</p>
     */
    public int analyzeWhaleActivity(String symbol) {
        List<OnChainMetric> recent = metricRepository.findTop100BySymbol(symbol, "whale_transaction_count");
        if (recent.size() < 10) return 0;

        // recent 默认按 timestamp DESC（最新在前）。这里取最近 5 条 vs 再往前 5 条的均值做对比。
        double avgLast = recent.stream().limit(5).mapToDouble(m -> m.getValue().doubleValue()).average().orElse(0);
        double avgPrev = recent.stream().skip(5).limit(5).mapToDouble(m -> m.getValue().doubleValue()).average().orElse(0);

        if (avgLast > avgPrev * 1.5) return 1;
        if (avgLast < avgPrev * 0.5) return -1;
        return 0;
    }
}
