package com.crypto.trader.service.executor;

import com.crypto.trader.client.exchange.ExchangeClient;
import com.crypto.trader.model.Signal;
import com.crypto.trader.model.TradeRecord;
import com.crypto.trader.repository.TradeRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单执行器。
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>风控检查（RiskManager）</li>
 *   <li>仓位检查（PositionManager）：BUY 前确认无持仓，SELL 前确认有持仓</li>
 *   <li>调用 ExchangeClient 下单</li>
 *   <li>更新内存仓位</li>
 *   <li>将成交记录写入 trade_records 表</li>
 * </ol>
 */
@Service
@Slf4j
public class OrderExecutor {

    @Autowired
    private ExchangeClient exchangeClient;

    @Autowired
    private RiskManager riskManager;

    @Autowired
    private PositionManager positionManager;

    @Autowired
    private TradeRecordRepository tradeRecordRepository;

    /**
     * 根据交易信号执行下单。
     *
     * @param signal 交易信号（action 为 BUY 或 SELL）
     */
    public void execute(Signal signal) {
        String symbol = signal.getSymbol();
        Signal.Action action = signal.getAction();

        if (action == Signal.Action.HOLD) {
            return;
        }

        // 1. 风控检查
        if (!riskManager.isTradeAllowed(signal)) {
            log.warn("[OrderExecutor] Order blocked by RiskManager: {} {}", action, symbol);
            return;
        }

        if (action == Signal.Action.BUY) {
            executeBuy(signal);
        } else {
            executeSell(signal);
        }
    }

    private void executeBuy(Signal signal) {
        String symbol = signal.getSymbol();
        double price  = signal.getPrice();

        // 已有持仓则跳过（避免重复开仓）
        if (positionManager.hasPosition(symbol)) {
            log.info("[OrderExecutor] Already holding {}, skip BUY", symbol);
            return;
        }

        double usdtAmount = riskManager.getMaxPositionSizeUsdt();
        Map<String, Object> req = new HashMap<>();
        req.put("symbol", symbol);
        req.put("side", "buy");
        req.put("type", "market");
        req.put("quoteQuantity", usdtAmount);

        try {
            Object result = exchangeClient.placeOrder(req);
            // 估算基础货币数量（市价单成交价以信号价格近似）
            BigDecimal quantity = price > 0
                    ? BigDecimal.valueOf(usdtAmount / price).setScale(8, RoundingMode.DOWN)
                    : BigDecimal.ZERO;

            positionManager.addPosition(symbol, quantity);
            saveRecord(symbol, "BUY", price, quantity, usdtAmount, result);

            log.info("[OrderExecutor] BUY executed: {} qty={} price={} usdt={}",
                    symbol, quantity, price, usdtAmount);
        } catch (Exception e) {
            log.error("[OrderExecutor] BUY failed for {}: {}", symbol, e.getMessage(), e);
        }
    }

    private void executeSell(Signal signal) {
        String symbol = signal.getSymbol();
        double price  = signal.getPrice();

        BigDecimal quantity = positionManager.getPosition(symbol);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("[OrderExecutor] No position to sell for {}", symbol);
            return;
        }

        Map<String, Object> req = new HashMap<>();
        req.put("symbol", symbol);
        req.put("side", "sell");
        req.put("type", "market");
        req.put("quantity", quantity.toPlainString());

        try {
            Object result = exchangeClient.placeOrder(req);
            double quoteAmount = quantity.doubleValue() * price;

            positionManager.clearPosition(symbol);
            saveRecord(symbol, "SELL", price, quantity, quoteAmount, result);

            log.info("[OrderExecutor] SELL executed: {} qty={} price={} usdt≈{}",
                    symbol, quantity, price, quoteAmount);
        } catch (Exception e) {
            log.error("[OrderExecutor] SELL failed for {}: {}", symbol, e.getMessage(), e);
        }
    }

    private void saveRecord(String symbol, String side, double price,
                            BigDecimal quantity, double quoteAmount, Object exchangeResult) {
        TradeRecord record = new TradeRecord();
        record.setSymbol(symbol);
        record.setTimestamp(Instant.now());
        record.setSide(side);
        record.setType("MARKET");
        record.setPrice(BigDecimal.valueOf(price));
        record.setQuantity(quantity);
        record.setQuoteQuantity(BigDecimal.valueOf(quoteAmount));
        record.setStatus("FILLED");

        // 尝试从交易所响应中提取订单ID
        if (exchangeResult instanceof Map<?, ?> map) {
            Object ordId = map.get("ordId");
            if (ordId != null) record.setOrderId(ordId.toString());
        }

        try {
            tradeRecordRepository.save(record);
        } catch (Exception e) {
            log.error("[OrderExecutor] Failed to save trade record for {}", symbol, e);
        }
    }
}
