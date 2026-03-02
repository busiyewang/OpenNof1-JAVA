package com.crypto.trader.service.collector;

import com.crypto.trader.client.glassnode.GlassnodeClient;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.OnChainMetricRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class OnChainCollector {

    @Autowired
    private GlassnodeClient glassnodeClient;

    @Autowired
    private OnChainMetricRepository metricRepository;

    /**
     * 拉取并保存巨鲸相关链上指标。
     *
     * <p>当前实现固定拉取最近 24 小时的区间（{@code now-24h ~ now}）。数据源为 {@link GlassnodeClient}，
     * 返回非空时批量落库。</p>
     *
     * <p>链上指标同样可能与前一次窗口重叠；真实接入时建议保证幂等（避免唯一索引冲突或重复统计）。</p>
     *
     * @param symbol 标的（传入形式取决于上游约定：可能是资产代码或交易对）
     */
    public void collectWhaleMetrics(String symbol) {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(24 * 60 * 60);
        List<OnChainMetric> metrics = glassnodeClient.getWhaleTransactionCount(symbol, from, to);
        if (!metrics.isEmpty()) {
            metricRepository.saveAll(metrics);
            log.info("Saved {} whale metrics for {}", metrics.size(), symbol);
        }
    }
}
