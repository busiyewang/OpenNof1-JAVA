package com.crypto.trader.service.executor;

import com.crypto.trader.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderExecutor {

    /**
     * 执行交易信号对应的下单动作。
     *
     * <p>当前实现仅记录日志。真实接入时应根据 {@link Signal} 的 action/price/仓位与风控结果，
     * 调用交易所客户端完成下单，并记录成交/订单状态。</p>
     *
     * <p>设计建议：</p>
     * <ul>
     *   <li>下单前先调用 {@code RiskManager} / {@code PositionManager} 进行额度、频率、止损止盈等约束</li>
     *   <li>将交易所返回的订单/成交写入 `trade_records`，便于复盘、对账与恢复状态</li>
     *   <li>下单与通知要考虑幂等：调度重复触发/服务重启时避免重复开仓</li>
     * </ul>
     *
     * @param signal 交易信号
     */
    public void execute(Signal signal) {
        log.info("Executing order: {}", signal);
        // 调用交易所API下单
    }
}
