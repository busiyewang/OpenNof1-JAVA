package com.crypto.trader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TraderApplication {
    /**
     * Spring Boot 应用入口。
     *
     * <p>启动后会加载 Spring 容器、扫描组件，并启用 {@code @Scheduled} 定时任务（策略执行与数据采集）。</p>
     *
     * @param args 命令行参数（透传给 Spring Boot）
     */
    public static void main(String[] args) {
        SpringApplication.run(TraderApplication.class, args);
    }
}
