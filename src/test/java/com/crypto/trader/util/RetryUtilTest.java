package com.crypto.trader.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryUtilTest {

    @Test
    void shouldReturnResultOnFirstSuccess() {
        String result = RetryUtil.withRetry(() -> "ok", "test-success");
        assertEquals("ok", result);
    }

    @Test
    void shouldRetryOnTransientFailureThenSucceed() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.withRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient error");
            }
            return "recovered";
        }, 3, 10, 1.0, "test-retry");

        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void shouldReturnNullAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.withRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("persistent error");
        }, 2, 10, 1.0, "test-exhausted");

        assertNull(result);
        assertEquals(3, attempts.get()); // 1 initial + 2 retries
    }

    @Test
    void shouldNotRetryOn4xxClientError() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.withRetry(() -> {
            attempts.incrementAndGet();
            throw WebClientResponseException.create(
                    400, "Bad Request", HttpHeaders.EMPTY,
                    new byte[0], StandardCharsets.UTF_8);
        }, 3, 10, 1.0, "test-4xx");

        assertNull(result);
        assertEquals(1, attempts.get()); // 不重试
    }

    @Test
    void shouldRetryOn429RateLimit() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.withRetry(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw WebClientResponseException.create(
                        429, "Too Many Requests", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8);
            }
            return "rate-limit-recovered";
        }, 3, 10, 1.0, "test-429");

        assertEquals("rate-limit-recovered", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void shouldRetryOn500ServerError() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryUtil.withRetry(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw WebClientResponseException.create(
                        500, "Internal Server Error", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8);
            }
            return "server-recovered";
        }, 3, 10, 1.0, "test-500");

        assertEquals("server-recovered", result);
    }

    @Test
    void shouldUseExponentialBackoff() {
        AtomicInteger attempts = new AtomicInteger(0);
        long[] timestamps = new long[4];

        RetryUtil.withRetry(() -> {
            int idx = attempts.getAndIncrement();
            timestamps[idx] = System.currentTimeMillis();
            if (idx < 3) {
                throw new RuntimeException("fail");
            }
            return "done";
        }, 3, 50, 2.0, "test-backoff");

        // 第二次间隔 >= 50ms, 第三次间隔 >= 100ms
        assertTrue(timestamps[1] - timestamps[0] >= 40); // 容差
        assertTrue(timestamps[2] - timestamps[1] >= 80);
    }
}
