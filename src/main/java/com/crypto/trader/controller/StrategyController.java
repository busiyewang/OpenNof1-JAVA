package com.crypto.trader.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StrategyController {

    /**
     * 健康检查接口，用于确认服务存活与 HTTP 路由可达。
     *
     * @return 固定字符串 {@code "OK"}
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
