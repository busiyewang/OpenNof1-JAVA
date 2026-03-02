package com.crypto.trader.service.strategy;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.model.Signal;
import java.util.List;

public interface TradingStrategy {
    /**
     * 基于行情与链上数据评估当前交易信号。
     *
     * <p>实现应尽量做到纯函数/无副作用（除日志），避免在此方法内直接下单；
     * 下单由更上层的执行器统一决策与控制。</p>
     *
     * @param symbol      交易对（如 {@code BTCUSDT}）
     * @param klines      行情 K 线数据（通常为最近 N 条，按时间排序的约定由调用方决定）
     * @param onChainData 链上指标数据（可能为空）
     * @return 交易信号（不建议返回 null；无动作时返回 {@code HOLD}）
     */
    Signal evaluate(String symbol, List<Kline> klines, List<OnChainMetric> onChainData);

    /**
     * 策略名称（用于打点、通知、记录来源等）。
     *
     * @return 策略名称
     */
    String getName();
}
