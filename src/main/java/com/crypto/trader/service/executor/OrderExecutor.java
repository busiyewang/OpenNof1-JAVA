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

        log.info("[下单] ======== {} 开始执行 {} ========", symbol, action);

        if (action == Signal.Action.HOLD) {
            log.info("[下单] {} HOLD 信号，跳过执行", symbol);
            return;
        }

        // 1. 风控检查
        log.info("[下单] {} 进行风控检查...", symbol);
        if (!riskManager.isTradeAllowed(signal)) {
            log.warn("[下单] {} {} 被风控拦截，终止下单", symbol, action);
            return;
        }
        log.info("[下单] {} 风控检查通过", symbol);

        if (action == Signal.Action.BUY) {
            executeBuy(signal);
        } else {
            executeSell(signal);
        }
    }

    private void executeBuy(Signal signal) {
        String symbol = signal.getSymbol();
        double price  = signal.getPrice();

        // 仓位检查
        if (positionManager.hasPosition(symbol)) {
            log.info("[下单] {} 已有持仓 ({})，跳过 BUY",
                    symbol, positionManager.getPosition(symbol).toPlainString());
            return;
        }

        double usdtAmount = riskManager.getMaxPositionSizeUsdt();
        log.info("[下单] {} 准备买入: 金额={} USDT, 参考价格={}", symbol, usdtAmount, price);

        Map<String, Object> req = new HashMap<>();
        req.put("symbol", symbol);
        req.put("side", "buy");
        req.put("type", "market");
        req.put("quoteQuantity", usdtAmount);

        try {
            log.info("[下单] {} 向交易所提交买单...", symbol);
            long t0 = System.currentTimeMillis();
            Object result = exchangeClient.placeOrder(req);
            long elapsed = System.currentTimeMillis() - t0;

            BigDecimal quantity = price > 0
                    ? BigDecimal.valueOf(usdtAmount / price).setScale(8, RoundingMode.DOWN)
                    : BigDecimal.ZERO;

            positionManager.addPosition(symbol, quantity);
            saveRecord(symbol, "BUY", price, quantity, usdtAmount, result);

            log.info("[下单] {} BUY 成功! 数量={}, 价格={}, 金额={} USDT, 交易所响应耗时: {}ms",
                    symbol, quantity.toPlainString(), price, usdtAmount, elapsed);
        } catch (Exception e) {
            log.error("[下单] {} BUY 失败: {}", symbol, e.getMessage(), e);
        }
    }

    private void executeSell(Signal signal) {
        String symbol = signal.getSymbol();
        double price  = signal.getPrice();

        BigDecimal quantity = positionManager.getPosition(symbol);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("[下单] {} 无持仓可卖，跳过 SELL", symbol);
            return;
        }

        log.info("[下单] {} 准备卖出: 数量={}, 参考价格={}", symbol, quantity.toPlainString(), price);

        Map<String, Object> req = new HashMap<>();
        req.put("symbol", symbol);
        req.put("side", "sell");
        req.put("type", "market");
        req.put("quantity", quantity.toPlainString());

        try {
            log.info("[下单] {} 向交易所提交卖单...", symbol);
            long t0 = System.currentTimeMillis();
            Object result = exchangeClient.placeOrder(req);
            long elapsed = System.currentTimeMillis() - t0;

            double quoteAmount = quantity.doubleValue() * price;

            positionManager.clearPosition(symbol);
            saveRecord(symbol, "SELL", price, quantity, quoteAmount, result);

            log.info("[下单] {} SELL 成功! 数量={}, 价格={}, 金额约={} USDT, 交易所响应耗时: {}ms",
                    symbol, quantity.toPlainString(), price, String.format("%.2f", quoteAmount), elapsed);
        } catch (Exception e) {
            log.error("[下单] {} SELL 失败: {}", symbol, e.getMessage(), e);
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

        if (exchangeResult instanceof Map<?, ?> map) {
            Object ordId = map.get("ordId");
            if (ordId != null) {
                record.setOrderId(ordId.toString());
                log.info("[下单] {} 交易所订单ID: {}", symbol, ordId);
            }
        }

        try {
            tradeRecordRepository.save(record);
            log.info("[下单] {} 成交记录已保存: {} {} 价格={} 数量={}",
                    symbol, side, symbol, price, quantity.toPlainString());
        } catch (Exception e) {
            log.error("[下单] {} 成交记录保存失败: {}", symbol, e.getMessage(), e);
        }
    }
}
