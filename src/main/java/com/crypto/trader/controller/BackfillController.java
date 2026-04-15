package com.crypto.trader.controller;

import com.crypto.trader.service.collector.KlineBackfillService;
import com.crypto.trader.service.collector.KlineBackfillService.BackfillProgress;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K线历史数据回填接口。
 *
 * <h3>使用示例</h3>
 * <pre>
 * # 回填 BTCUSDT 最近3个月的 1h K线
 * curl -X POST "http://localhost:8080/api/backfill/BTCUSDT?interval=1h&months=3"
 *
 * # 回填指定日期范围的 4h K线
 * curl -X POST "http://localhost:8080/api/backfill/BTCUSDT?interval=4h&startDate=2025-01-01&endDate=2025-04-01"
 *
 * # 回填多个周期
 * curl -X POST "http://localhost:8080/api/backfill/BTCUSDT/all?months=3"
 *
 * # 查看回填进度
 * curl "http://localhost:8080/api/backfill/BTCUSDT/progress?interval=1h"
 *
 * # 查看所有回填任务
 * curl "http://localhost:8080/api/backfill/progress"
 * </pre>
 */
@RestController
@RequestMapping("/api/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final KlineBackfillService backfillService;

    /**
     * 触发单个周期的历史数据回填。
     *
     * @param symbol    交易对（如 BTCUSDT）
     * @param interval  K线周期（1m, 5m, 15m, 1h, 4h, 1d）
     * @param months    回填月数（与 startDate/endDate 二选一，默认3个月）
     * @param startDate 起始日期（yyyy-MM-dd，与 months 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，默认今天）
     */
    @PostMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> backfill(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        String sym = symbol.toUpperCase();
        Instant end;
        Instant start;

        if (startDate != null) {
            start = LocalDate.parse(startDate).atStartOfDay().toInstant(ZoneOffset.UTC);
            // endDate 取当天结束（次日 00:00），确保包含 endDate 当天的数据
            end = endDate != null
                    ? LocalDate.parse(endDate).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : Instant.now();
        } else {
            int m = months != null ? months : 3;
            end = Instant.now();
            start = end.minus(m * 30L, ChronoUnit.DAYS);
        }

        // 参数校验
        if (start.isAfter(end)) {
            return ResponseEntity.badRequest().body(Map.of("error", "startDate 不能晚于 endDate"));
        }

        // 检查是否已有任务在执行
        BackfillProgress existing = backfillService.getProgress(sym, interval);
        if (existing != null && existing.getStatus() == KlineBackfillService.BackfillStatus.RUNNING) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ALREADY_RUNNING");
            result.put("message", sym + ":" + interval + " 已有回填任务在执行中");
            result.put("progress", existing);
            return ResponseEntity.ok(result);
        }

        backfillService.backfillAsync(sym, interval, start, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "STARTED");
        result.put("symbol", sym);
        result.put("interval", interval);
        result.put("startTime", start.toString());
        result.put("endTime", end.toString());
        result.put("message", "回填任务已启动，请通过 /api/backfill/" + sym + "/progress?interval=" + interval + " 查看进度");

        return ResponseEntity.ok(result);
    }

    /**
     * 回填多个常用周期（1h, 4h, 1d）。
     */
    @PostMapping("/{symbol}/all")
    public ResponseEntity<Map<String, Object>> backfillAll(
            @PathVariable String symbol,
            @RequestParam(required = false, defaultValue = "3") int months,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        String sym = symbol.toUpperCase();
        Instant end;
        Instant start;

        if (startDate != null) {
            start = LocalDate.parse(startDate).atStartOfDay().toInstant(ZoneOffset.UTC);
            end = endDate != null
                    ? LocalDate.parse(endDate).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : Instant.now();
        } else {
            end = Instant.now();
            start = end.minus(months * 30L, ChronoUnit.DAYS);
        }

        // 回填任务通过 backfillExecutorPool（maxPoolSize=1）串行执行，不会并行打 API
        String[] intervals = {"1h", "4h", "1d"};
        Map<String, String> tasks = new LinkedHashMap<>();

        for (String interval : intervals) {
            BackfillProgress existing = backfillService.getProgress(sym, interval);
            if (existing != null && existing.getStatus() == KlineBackfillService.BackfillStatus.RUNNING) {
                tasks.put(interval, "ALREADY_RUNNING");
            } else {
                backfillService.backfillAsync(sym, interval, start, end);
                tasks.put(interval, "STARTED");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", sym);
        result.put("startTime", start.toString());
        result.put("endTime", end.toString());
        result.put("tasks", tasks);
        result.put("progressUrl", "/api/backfill/progress");

        return ResponseEntity.ok(result);
    }

    /**
     * 查询指定任务的回填进度。
     */
    @GetMapping("/{symbol}/progress")
    public ResponseEntity<?> getProgress(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval) {

        BackfillProgress progress = backfillService.getProgress(symbol.toUpperCase(), interval);
        if (progress == null) {
            return ResponseEntity.ok(Map.of("message", "无回填任务记录"));
        }
        return ResponseEntity.ok(progress);
    }

    /**
     * 查询所有回填任务的状态。
     */
    @GetMapping("/progress")
    public ResponseEntity<?> getAllProgress() {
        return ResponseEntity.ok(backfillService.getAllProgress());
    }
}
