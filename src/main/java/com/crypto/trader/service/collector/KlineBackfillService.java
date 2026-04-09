package com.crypto.trader.service.collector;

import com.crypto.trader.client.exchange.OkxClient;
import com.crypto.trader.model.Kline;
import com.crypto.trader.repository.KlineRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * K线历史数据回填服务。
 *
 * <p>支持一次性拉取数月的历史K线数据，自动分页、去重、入库。</p>
 * <p>异步执行，提供进度查询接口。</p>
 */
@Service
@Slf4j
public class KlineBackfillService {

    @Autowired
    private OkxClient okxClient;

    @Autowired
    private KlineRepository klineRepository;

    /** 每次 API 请求间隔（毫秒），OKX 限制 3次/秒/IP */
    private static final long API_SLEEP_MS = 350;

    /** 1m 级别单次拉取上限（避免 OOM），超过此值分段拉取 */
    private static final int MAX_MEMORY_KLINES = 20000;

    /** 正在执行的回填任务状态 */
    private final ConcurrentHashMap<String, BackfillProgress> progressMap = new ConcurrentHashMap<>();

    /**
     * 异步执行历史数据回填。
     */
    @Async("backfillExecutorPool")
    public void backfillAsync(String symbol, String interval, Instant startTime, Instant endTime) {
        String taskKey = symbol + ":" + interval;

        if (progressMap.containsKey(taskKey) && progressMap.get(taskKey).getStatus() == BackfillStatus.RUNNING) {
            log.warn("[回填] {} 已有回填任务在执行中，跳过", taskKey);
            return;
        }

        BackfillProgress progress = new BackfillProgress();
        progress.setSymbol(symbol);
        progress.setInterval(interval);
        progress.setStatus(BackfillStatus.RUNNING);
        progress.setStartTime(startTime);
        progress.setEndTime(endTime);
        progress.setStartedAt(Instant.now());
        progressMap.put(taskKey, progress);

        try {
            log.info("[回填] ========== {} 开始回填 {} ~ {} ==========", taskKey, startTime, endTime);

            // 根据 interval 和时间跨度决定是否分段拉取
            long spanMs = endTime.toEpochMilli() - startTime.toEpochMilli();
            long intervalMs = intervalToMs(interval);
            long estimatedCount = intervalMs > 0 ? spanMs / intervalMs : 0;

            if (estimatedCount > MAX_MEMORY_KLINES) {
                // 分段拉取，每段 MAX_MEMORY_KLINES 条
                backfillInSegments(symbol, interval, startTime, endTime, progress);
            } else {
                // 一次性拉取
                backfillOnce(symbol, interval, startTime, endTime, progress);
            }

            progress.setStatus(BackfillStatus.COMPLETED);
            progress.setFinishedAt(Instant.now());
            progress.setMessage(String.format("完成：新增%d条，跳过%d条重复",
                    progress.getSavedCount(), progress.getSkippedCount()));

            log.info("[回填] ========== {} 回填完成：新增{}条，跳过{}条 ==========",
                    taskKey, progress.getSavedCount(), progress.getSkippedCount());

        } catch (Exception e) {
            progress.setStatus(BackfillStatus.FAILED);
            progress.setFinishedAt(Instant.now());
            progress.setMessage("回填失败: " + e.getMessage());
            log.error("[回填] {} 回填异常: {}", taskKey, e.getMessage(), e);
        }
    }

    /**
     * 一次性拉取并入库（数据量 < MAX_MEMORY_KLINES 时使用）。
     */
    private void backfillOnce(String symbol, String interval, Instant startTime, Instant endTime,
                               BackfillProgress progress) {
        List<Kline> allKlines = okxClient.getKlinesHistory(
                symbol, interval, startTime.toEpochMilli(), endTime.toEpochMilli(), API_SLEEP_MS);

        progress.setTotalFetched(allKlines.size());

        if (!allKlines.isEmpty()) {
            batchSaveWithDedup(allKlines, progress);
        }
    }

    /**
     * 分段拉取（1m 级别数月数据，避免 OOM）。
     * 将时间范围分成多段，每段拉取后立即入库释放内存。
     */
    private void backfillInSegments(String symbol, String interval, Instant startTime, Instant endTime,
                                     BackfillProgress progress) {
        long intervalMs = intervalToMs(interval);
        long segmentMs = intervalMs * MAX_MEMORY_KLINES;
        long cursor = endTime.toEpochMilli();

        while (cursor > startTime.toEpochMilli()) {
            long segStart = Math.max(cursor - segmentMs, startTime.toEpochMilli());

            log.info("[回填] {}:{} 分段拉取: {} ~ {}", symbol, interval,
                    Instant.ofEpochMilli(segStart), Instant.ofEpochMilli(cursor));

            List<Kline> segment = okxClient.getKlinesHistory(
                    symbol, interval, segStart, cursor, API_SLEEP_MS);

            progress.setTotalFetched(progress.getTotalFetched() + segment.size());

            if (!segment.isEmpty()) {
                batchSaveWithDedup(segment, progress);
            }

            cursor = segStart;

            // 段间也要限流
            if (cursor > startTime.toEpochMilli()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * 批量去重入库（用批量查询替代 N+1）。
     */
    private void batchSaveWithDedup(List<Kline> klines, BackfillProgress progress) {
        int batchSize = 500;

        for (int i = 0; i < klines.size(); i += batchSize) {
            int end = Math.min(i + batchSize, klines.size());
            List<Kline> batch = klines.subList(i, end);

            // 批量查询已存在的时间戳，替代逐条 exists 查询
            String symbol = batch.get(0).getSymbol();
            String interval = batch.get(0).getInterval();
            List<Instant> timestamps = batch.stream()
                    .map(Kline::getTimestamp)
                    .collect(Collectors.toList());

            Set<Instant> existingTimestamps = new HashSet<>(
                    klineRepository.findExistingTimestamps(symbol, interval, timestamps));

            List<Kline> newKlines = batch.stream()
                    .filter(k -> !existingTimestamps.contains(k.getTimestamp()))
                    .collect(Collectors.toList());

            if (!newKlines.isEmpty()) {
                klineRepository.saveAll(newKlines);
                progress.setSavedCount(progress.getSavedCount() + newKlines.size());
            }
            progress.setSkippedCount(progress.getSkippedCount() + (batch.size() - newKlines.size()));

            log.debug("[回填] 批次 {}-{}: 新增={}, 跳过={}",
                    i, end, newKlines.size(), batch.size() - newKlines.size());
        }
    }

    /**
     * interval 转毫秒数（用于估算数据量）。
     */
    private long intervalToMs(String interval) {
        return switch (interval) {
            case "1m"  -> 60_000L;
            case "3m"  -> 180_000L;
            case "5m"  -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h"  -> 3_600_000L;
            case "2h"  -> 7_200_000L;
            case "4h"  -> 14_400_000L;
            case "1d"  -> 86_400_000L;
            default    -> 60_000L;
        };
    }

    public BackfillProgress getProgress(String symbol, String interval) {
        return progressMap.get(symbol + ":" + interval);
    }

    public ConcurrentHashMap<String, BackfillProgress> getAllProgress() {
        return progressMap;
    }

    public enum BackfillStatus {
        RUNNING, COMPLETED, FAILED
    }

    @Data
    public static class BackfillProgress {
        private String symbol;
        private String interval;
        private BackfillStatus status;
        private Instant startTime;
        private Instant endTime;
        private Instant startedAt;
        private Instant finishedAt;
        private int totalFetched;
        private int savedCount;
        private int skippedCount;
        private String message;
    }
}
