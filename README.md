# crypto-trader-agent

一个基于 **Spring Boot + MySQL + ta4j** 的加密货币量化交易机器人（Agent）骨架：定时采集行情/链上数据落库，定时运行多策略生成交易信号，推送通知，并在 `live` 模式下触发下单。

## 适用场景

- 你想要一个"可扩展的交易系统骨架"，把 **数据采集 → 指标计算 → 策略 → 信号融合 → 风控/仓位 → 执行/通知** 串成一条可运行链路
- 你希望在本地用数据库沉淀数据，便于回测/分析/调参

> 注意：本项目默认 `paper` 模式不会真实下单；若要启用 `live`，务必先补齐风控与仓位管理逻辑，并使用小资金验证。

## 功能概览

| 模块 | 状态 | 说明 |
|------|------|------|
| OKX K 线采集 | ✅ 已实现 | 每 60s 拉取 watch-list 的 1m K 线，存入 `klines` 表 |
| 链上数据采集 | 🔧 占位 | Glassnode 巨鲸指标，每小时采集，存入 `onchain_metrics` 表 |
| MACD 策略 | ✅ 已实现 | EMA 12/26，信号线 9，置信度 0.7 |
| 布林带策略 | ✅ 已实现 | SMA 20，±2 标准差，置信度 0.6 |
| 鲸鱼联合策略 | ✅ 已实现 | 链上鲸鱼指标 + MACD 组合，置信度 0.85 |
| 大模型策略 | 🔧 可选 | 调用 OpenAI 兼容接口进行预测（需配置） |
| 信号融合 | ✅ 已实现 | 过滤 HOLD 后选择置信度最高信号 |
| 下单执行 | 🔧 占位 | paper 模式仅日志，live 模式调用 OKX 下单接口 |
| Telegram 通知 | 🔧 占位 | 仅日志输出，待接入 Bot API |
| 邮件通知 | 🔧 占位 | 仅日志输出，待接入 SMTP |

## 技术栈

- **Java 21** + **Spring Boot 2.7.x**
- **MySQL 8**（运行依赖）
- **Spring Data JPA**（ORM）
- **Spring WebFlux**（WebClient 异步 HTTP 客户端）
- **ta4j 0.15**（技术指标计算）
- **Lombok**

## 运行依赖

- Java 21
- Maven 3.x
- MySQL 8+

## 快速开始

### 1) 准备数据库

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE crypto_trader CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

项目启动时会自动建表（`spring.jpa.hibernate.ddl-auto: update`）。

### 2) 配置参数

复制示例配置并填写你的参数：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

编辑 `application.yml` 或通过环境变量覆盖：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/crypto_trader
    username: root
    password: your_password

crypto:
  watch-list: BTCUSDT,ETHUSDT,SOLUSDT
  trading:
    mode: paper   # paper（模拟）或 live（实盘）
```

### 3) 配置 API Key（环境变量）

| 变量名 | 说明 |
|--------|------|
| `OKX_API_KEY` | OKX API Key |
| `OKX_SECRET_KEY` | OKX Secret Key |
| `OKX_PASSPHRASE` | OKX Passphrase |
| `GLASSNODE_API_KEY` | Glassnode API Key（链上数据，可选） |
| `MCP_API_KEY` | OpenAI 兼容接口 Key（大模型策略，可选） |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot Token（通知，可选） |
| `TELEGRAM_CHAT_ID` | Telegram Chat ID（通知，可选） |

> 没有真实 Key 时可先不配，对应模块会跳过或仅输出日志。

### 4) 启动

```bash
mvn spring-boot:run
```

健康检查：

```
GET /health  →  200 OK
```

## 架构说明

### 数据流

```
[OKX API] ──1m K线──▶ DataCollectionScheduler ──▶ klines 表
[Glassnode]──链上指标─▶                          ──▶ onchain_metrics 表
                                                         │
                               StrategyScheduler ◀───────┘
                                      │
                          ┌───────────┼───────────┐
                          ▼           ▼           ▼
                    MacdStrategy  Bollinger  WhaleCombined  (并行)
                          └───────────┼───────────┘
                                      ▼
                              信号融合（最高置信度）
                                      │
                          ┌───────────┼───────────┐
                          ▼                       ▼
                       Notifier             OrderExecutor
                   (Telegram/Email)      (paper: log / live: OKX)
```

### 关键类

| 类 | 说明 |
|----|------|
| `DataCollectionScheduler` | 定时采集行情与链上数据 |
| `StrategyScheduler` | 定时触发策略执行 |
| `StrategyExecutor` | 策略编排、信号融合、通知与执行 |
| `BinanceClient`* | OKX 交易所客户端（含 K 线、下单、账户查询） |
| `MacdCalculator` | MACD 指标计算 |
| `BollingerBandsCalculator` | 布林带指标计算 |
| `OrderExecutor` | 下单与仓位记录 |
| `RiskManager` | 风控（占位，待实现） |
| `PositionManager` | 仓位管理（内存级，占位） |

> *`BinanceClient` 内部已对接 OKX API，类名为历史遗留，后续可重命名。

### 数据库表

| 表 | 对应实体 | 说明 |
|----|---------|------|
| `klines` | `Kline` | OHLCV K 线，按 (symbol, timestamp) 唯一索引 |
| `onchain_metrics` | `OnChainMetric` | 链上指标，按 (symbol, metricName, timestamp) 唯一索引 |
| `trade_records` | `TradeRecord` | 成交记录（live 模式写入） |

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `crypto.watch-list` | `BTCUSDT,ETHUSDT,SOLUSDT` | 监控交易对，逗号分隔 |
| `crypto.trading.mode` | `paper` | `paper` 模拟 / `live` 实盘 |
| `crypto.trading.max-position-size-usdt` | `1000` | 单仓最大名义金额（USDT） |
| `crypto.trading.max-daily-trades` | `10` | 每日最大交易次数 |
| `crypto.mcp.enabled` | `true` | 是否启用大模型策略 |
| `crypto.mcp.model` | `gpt-4` | 大模型名称 |

## 待完善模块

- **Glassnode 链上接入**：`GlassnodeClient` 的 API endpoint 拼接与响应映射
- **Telegram / Email 通知**：接入真实 Bot API 或 SMTP
- **风控落地**：`RiskManager` 实现最大仓位、频率、止损止盈约束
- **仓位同步**：`PositionManager` 与交易所实际持仓的同步
- **回测支持**：利用 `klines` 历史数据对策略进行离线回测

## 安全提示

- **不要** 把真实 API Key 写死在 `application.yml` 中，优先使用环境变量
- **启用 `live` 前**，请务必：
  1. 实现 `RiskManager` 的真实约束（最大仓位、频率限制、止损等）
  2. 在 `paper` 模式下充分验证策略逻辑
  3. 从小资金开始逐步放量
  4. 确认 OKX 签名鉴权与下单响应解析正确无误
