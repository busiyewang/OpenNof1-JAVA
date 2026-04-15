package com.crypto.trader.backtest;

import com.crypto.trader.backtest.model.BacktestReport;
import com.crypto.trader.backtest.model.BacktestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测接口。
 *
 * <h3>使用示例</h3>
 * <pre>
 * # 回测所有策略（3个月 1h K线）
 * curl -X POST http://localhost:8080/api/backtest \
 *   -H "Content-Type: application/json" \
 *   -d '{"symbol":"BTCUSDT","interval":"1h","startDate":"2026-01-01","endDate":"2026-04-01"}'
 *
 * # 只回测缠论策略
 * curl -X POST http://localhost:8080/api/backtest \
 *   -H "Content-Type: application/json" \
 *   -d '{"symbol":"BTCUSDT","interval":"1h","startDate":"2026-01-01","strategies":["CHAN"]}'
 *
 * # 多策略投票模式
 * curl -X POST http://localhost:8080/api/backtest \
 *   -H "Content-Type: application/json" \
 *   -d '{"symbol":"BTCUSDT","interval":"4h","startDate":"2026-01-01","voteMode":true,"voteThreshold":3}'
 *
 * # 自定义止损止盈和仓位
 * curl -X POST http://localhost:8080/api/backtest \
 *   -H "Content-Type: application/json" \
 *   -d '{"symbol":"BTCUSDT","interval":"1h","startDate":"2026-01-01",
 *        "stopLossPercent":2.0,"takeProfitPercent":4.0,"positionSizePercent":0.3,"feePercent":0.1}'
 *
 * # 快速回测（使用默认参数，GET 方式）
 * curl "http://localhost:8080/api/backtest/quick/BTCUSDT?interval=1h&startDate=2026-01-01"
 * </pre>
 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    /**
     * 完整回测（POST JSON）。
     */
    @PostMapping
    public ResponseEntity<?> runBacktest(@RequestBody BacktestRequest request) {
        if (request.getStartDate() == null || request.getStartDate().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "startDate 不能为空"));
        }

        List<BacktestReport> reports = backtestService.runBacktest(request);
        if (reports.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "K线数据不足或无匹配策略，无法回测"));
        }

        // 返回汇总 + 各策略详情
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStrategies", reports.size());

        if (reports.size() > 1) {
            // 多策略对比摘要
            List<Map<String, Object>> summary = reports.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("strategy", r.getStrategyName());
                m.put("totalReturn", r.getTotalReturnPercent() + "%");
                m.put("winRate", r.getWinRate() + "%");
                m.put("maxDrawdown", r.getMaxDrawdownPercent() + "%");
                m.put("sharpe", r.getSharpeRatio());
                m.put("trades", r.getTotalTrades());
                m.put("profitLossRatio", r.getProfitLossRatio());
                return m;
            }).toList();
            result.put("comparison", summary);
        }

        result.put("reports", reports);
        return ResponseEntity.ok(result);
    }

    /**
     * 快速回测（GET 方式，使用默认参数）。
     */
    @GetMapping("/quick/{symbol}")
    public ResponseEntity<?> quickBacktest(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) List<String> strategies) {

        BacktestRequest request = new BacktestRequest();
        request.setSymbol(symbol.toUpperCase());
        request.setInterval(interval);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setStrategies(strategies);

        List<BacktestReport> reports = backtestService.runBacktest(request);
        if (reports.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "K线数据不足或无匹配策略"));
        }

        return ResponseEntity.ok(reports);
    }
}
