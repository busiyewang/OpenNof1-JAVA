package com.crypto.trader.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.function.Supplier;

/**
 * HTTP 请求重试工具 — 指数退避 + 429 限流感知。
 *
 * <p>重试策略：</p>
 * <ul>
 *   <li>5xx / 网络错误 → 重试（指数退避）</li>
 *   <li>429 Too Many Requests → 重试（尊重 Retry-After 或退避等待）</li>
 *   <li>其它 4xx → 不重试（客户端错误）</li>
 * </ul>
 */
@Slf4j
public final class RetryUtil {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 1000;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private RetryUtil() {}

    /**
     * 带重试执行操作（使用默认参数：3次重试，1秒初始延迟，2倍退避）。
     */
    public static <T> T withRetry(Supplier<T> action, String operationName) {
        return withRetry(action, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY_MS,
                DEFAULT_BACKOFF_MULTIPLIER, operationName);
    }

    /**
     * 带重试执行操作。
     *
     * @param action             要执行的操作
     * @param maxRetries         最大重试次数
     * @param initialDelayMs     初始退避延迟（毫秒）
     * @param backoffMultiplier  退避乘数
     * @param operationName      操作名称（用于日志）
     * @return 操作结果，全部失败返回 null
     */
    public static <T> T withRetry(Supplier<T> action, int maxRetries, long initialDelayMs,
                                   double backoffMultiplier, String operationName) {
        long delayMs = initialDelayMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    log.warn("[重试] {} 不可重试错误: {}", operationName, e.getMessage());
                    return null;
                }

                if (attempt == maxRetries) {
                    log.error("[重试] {} 已达最大重试次数({}), 放弃. 最后错误: {}",
                            operationName, maxRetries, e.getMessage());
                    return null;
                }

                long waitMs = getWaitMs(e, delayMs);
                log.warn("[重试] {} 第{}次重试, {}ms后重试. 错误: {}",
                        operationName, attempt + 1, waitMs, e.getMessage());

                sleep(waitMs);
                delayMs = (long) (delayMs * backoffMultiplier);
            }
        }

        return null;
    }

    /**
     * 判断异常是否值得重试。
     */
    private static boolean isRetryable(Exception e) {
        if (e instanceof WebClientResponseException wce) {
            int status = wce.getStatusCode().value();
            // 429 (限流) 和 5xx (服务端错误) 可重试
            return status == 429 || status >= 500;
        }
        // 网络错误（连接超时、DNS失败等）可重试
        return true;
    }

    /**
     * 获取等待时间（优先使用 429 的 Retry-After 头）。
     */
    private static long getWaitMs(Exception e, long defaultDelayMs) {
        if (e instanceof WebClientResponseException wce && wce.getStatusCode().value() == 429) {
            String retryAfter = wce.getHeaders().getFirst("Retry-After");
            if (retryAfter != null) {
                try {
                    return Long.parseLong(retryAfter) * 1000;
                } catch (NumberFormatException ignored) {}
            }
        }
        return defaultDelayMs;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
