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
import java.util.List;
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

    /** 每次 API 请求间隔（毫秒），OKX 限制 3次/秒 */
    private static final long API_SLEEP_MS = 350;

    /** 正在执行的回填任务状态 */
    private final ConcurrentHashMap<String, BackfillProgress> progressMap = new ConcurrentHashMap<>();

    /**
     * 异步执行历史数据回填。
     *
     * @param symbol    交易对
     * @param interval  K线周期
     * @param startTime 起始时间
     * @param endTime   结束时间
     */
    @Async("strategyExecutorPool")
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

            // 1. 拉取所有历史K线
            List<Kline> allKlines = okxClient.getKlinesHistory(
                    symbol, interval, startTime.toEpochMilli(), endTime.toEpochMilli(), API_SLEEP_MS);

            progress.setTotalFetched(allKlines.size());

            if (allKlines.isEmpty()) {
                progress.setStatus(BackfillStatus.COMPLETED);
                progress.setMessage("API 未返回数据");
                progress.setFinishedAt(Instant.now());
                log.warn("[回填] {} 未获取到任何K线数据", taskKey);
                return;
            }

            // 2. 去重入库（分批处理，每批500条）
            int batchSize = 500;
            AtomicInteger savedCount = new AtomicInteger(0);
            AtomicInteger skippedCount = new AtomicInteger(0);

            for (int i = 0; i < allKlines.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allKlines.size());
                List<Kline> batch = allKlines.subList(i, end);

                List<Kline> newKlines = batch.stream()
                        .filter(k -> !klineRepository.existsBySymbolAndIntervalAndTimestamp(
                                k.getSymbol(), k.getInterval(), k.getTimestamp()))
                        .collect(Collectors.toList());

                if (!newKlines.isEmpty()) {
                    klineRepository.saveAll(newKlines);
                    savedCount.addAndGet(newKlines.size());
                }
                skippedCount.addAndGet(batch.size() - newKlines.size());

                progress.setSavedCount(savedCount.get());
                progress.setSkippedCount(skippedCount.get());

                log.info("[回填] {} 进度: {}/{} 已拉取, 新增={}, 跳过={}",
                        taskKey, end, allKlines.size(), savedCount.get(), skippedCount.get());
            }

            progress.setStatus(BackfillStatus.COMPLETED);
            progress.setFinishedAt(Instant.now());
            progress.setMessage(String.format("完成：拉取%d条，新增%d条，跳过%d条重复",
                    allKlines.size(), savedCount.get(), skippedCount.get()));

            log.info("[回填] ========== {} 回填完成：拉取{}条，新增{}条，跳过{}条 ==========",
                    taskKey, allKlines.size(), savedCount.get(), skippedCount.get());

        } catch (Exception e) {
            progress.setStatus(BackfillStatus.FAILED);
            progress.setFinishedAt(Instant.now());
            progress.setMessage("回填失败: " + e.getMessage());
            log.error("[回填] {} 回填异常: {}", taskKey, e.getMessage(), e);
        }
    }

    /**
     * 查询回填进度。
     */
    public BackfillProgress getProgress(String symbol, String interval) {
        return progressMap.get(symbol + ":" + interval);
    }

    /**
     * 查询所有回填任务状态。
     */
    public ConcurrentHashMap<String, BackfillProgress> getAllProgress() {
        return progressMap;
    }

    // =========================================================================
    // 进度模型
    // =========================================================================

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
