# OpenNof1 - 加密货币分析系统

基于 **Spring Boot + MySQL + ta4j + DeepSeek AI** 的加密货币分析预测系统。定时采集 OKX 多时间框架 K 线数据和 Glassnode 链上指标，调用 DeepSeek 大模型进行结构化分析，生成专业 HTML 邮件报告。

## 系统架构

```
[OKX API]
  ├── WebSocket: 实时 K 线推送 (1m/1h/4h/1d)
  └── REST API:  回补 K 线数据
                    ↓
              DataCollectionScheduler → klines 表

[Glassnode API]
  ├── 巨鲸转账量 (whale_transfer_volume)
  ├── NUPL (净未实现盈亏)
  ├── SOPR (花费产出利润率)
  ├── 交易所净流入/流入/流出
  └── 活跃地址数
                    ↓
              OnChainCollector → onchain_metrics 表

              AnalysisReportScheduler
                    ↓
              AnalysisService (核心编排)
                ├── 多时间框架技术指标计算 (MACD, 布林带)
                ├── 链上数据汇总
                ├── PromptBuilder 构建分析提示词
                ├── McpClient → DeepSeek AI 分析
                ├── AnalysisResponseParser 解析 JSON 结果
                └── AnalysisReport 持久化
                    ↓
              AnalysisEmailSender
                └── Thymeleaf HTML 邮件模板 → 发送报告
```

## 功能概览

| 模块 | 说明 |
|------|------|
| 多时间框架 K 线采集 | OKX WebSocket + REST 双模式，支持 1m/1h/4h/1d |
| 链上数据采集 | Glassnode 7 种指标：巨鲸转账、NUPL、SOPR、交易所资金流、活跃地址 |
| DeepSeek AI 分析 | 结构化 JSON 输出：趋势方向、置信度、支撑/阻力位、风险评估、展望 |
| HTML 邮件报告 | 专业 Thymeleaf 模板，颜色编码趋势，多时间框架表格，链上数据解读 |
| 定时调度 | 每日早 8 点 + 每周一早 9 点自动分析发送邮件 |
| REST API | 按需触发分析、查询最新/历史报告 |
| 传统策略信号 | MACD、布林带、鲸鱼联合策略（保留兼容） |

## 技术栈

- **Java 21** + **Spring Boot 2.7.x**
- **MySQL 8**（数据持久化）
- **Spring Data JPA**（ORM）
- **Spring WebFlux**（WebClient 异步 HTTP）
- **ta4j 0.15**（技术指标计算）
- **Thymeleaf**（HTML 邮件模板）
- **DeepSeek API**（AI 分析，兼容 OpenAI 格式）
- **Lombok**

## 快速开始

### 1) 准备数据库

```bash
mysql -u root -p -e "CREATE DATABASE crypto_trader CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

启动时 Hibernate 会自动建表（`ddl-auto: update`），或手动执行 `sql/schema.sql`。

### 2) 配置参数

编辑 `src/main/resources/application.yml` 或通过环境变量覆盖：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/crypto_trader
    username: root
    password: your_password
  mail:
    host: smtp.qq.com
    port: 465
    username: your_email@qq.com
    password: your_smtp_authorization_code

crypto:
  watch-list: BTCUSDT
  analysis:
    timeframes: 1h,4h,1d
    daily-cron: "0 0 8 * * ?"
    weekly-cron: "0 0 9 ? * MON"
    timezone: Asia/Shanghai
```

### 3) 配置 API Key（环境变量）

| 变量名 | 说明 | 必需 |
|--------|------|------|
| `OKX_API_KEY` | OKX API Key | 否（K线公开接口无需） |
| `GLASSNODE_API_KEY` | Glassnode API Key | 是（链上数据） |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 是（AI 分析） |
| `MAIL_USERNAME` | SMTP 邮箱地址 | 是（邮件发送） |
| `MAIL_PASSWORD` | SMTP 授权码 | 是（邮件发送） |
| `MAIL_FROM` | 发件人地址 | 是 |
| `MAIL_TO` | 收件人地址 | 是 |

### 4) 启动

```bash
mvn spring-boot:run
```

### 5) API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `POST` | `/api/analysis/{symbol}` | 按需触发分析并发送邮件 |
| `GET` | `/api/analysis/{symbol}/latest` | 获取最新分析报告 |
| `GET` | `/api/analysis/{symbol}/history?from=&to=` | 查询历史报告 |

示例：
```bash
# 立即触发 BTC 分析
curl -X POST http://localhost:8080/api/analysis/BTCUSDT

# 查看最新报告
curl http://localhost:8080/api/analysis/BTCUSDT/latest
```

## 邮件报告预览

报告包含以下板块：
- **趋势摘要**：方向（看多/看空/中性）、置信度、风险等级
- **价格概览**：当前价格、支撑位、阻力位
- **多时间框架分析**：1h/4h/1d 的 MACD 状态和布林带位置
- **链上数据解读**：NUPL、SOPR、交易所资金流、鲸鱼活动
- **市场展望**：短期（1-3天）和中期（1-2周）
- **风险因子**：当前主要风险列表
- **AI 详细分析**：DeepSeek 完整推理

## 数据库表

| 表 | 说明 |
|----|------|
| `klines` | K 线数据 (OHLCV)，唯一索引: (symbol, interval, timestamp) |
| `onchain_metrics` | 链上指标，唯一索引: (symbol, metric_name, timestamp) |
| `signals` | 策略交易信号 |
| `indicator_values` | 技术指标计算缓存 |
| `analysis_reports` | 分析报告（含 AI 分析结果） |

## 项目结构

```
src/main/java/com/crypto/trader/
├── client/
│   ├── exchange/          # OKX REST + WebSocket 客户端
│   ├── glassnode/         # Glassnode 链上数据客户端
│   └── mcp/              # DeepSeek AI 客户端 + DTO
├── controller/            # REST API (AnalysisController)
├── model/                 # JPA 实体 (Kline, AnalysisReport, ...)
├── repository/            # Spring Data 仓库
├── scheduler/             # 定时任务 (数据采集, 分析报告)
└── service/
    ├── analysis/          # 核心: AnalysisService, PromptBuilder, ResponseParser
    ├── analyzer/          # 鲸鱼分析器
    ├── collector/         # 数据采集 (KlineCollector, OnChainCollector)
    ├── indicator/         # 技术指标 (MACD, Bollinger)
    ├── notifier/          # 通知 (EmailNotifier, AnalysisEmailSender)
    └── strategy/          # 交易策略 (MACD, Bollinger, Whale, MCP)
```

## 安全提示

- **不要**把真实 API Key 写死在 `application.yml` 中，优先使用环境变量
- 本系统仅用于**分析预测**，不执行真实交易
- 分析报告仅供参考，不构成投资建议
