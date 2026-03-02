# crypto-trader-agent

一个基于 **Spring Boot + PostgreSQL + ta4j** 的加密货币交易机器人（Agent）骨架：定时采集行情/链上数据落库，定时运行多策略生成交易信号，推送通知，并在 `live` 模式下触发下单。

## 适用场景

- 你想要一个“可扩展的交易系统骨架”，把 **数据采集 → 指标计算 → 策略 → 信号融合 → 风控/仓位 → 执行/通知** 串成一条可运行链路
- 你希望在本地用数据库沉淀数据，便于回测/分析/调参

> 注意：本项目默认 `paper` 模式不会真实下单；若要启用 `live`，务必先补齐交易所签名鉴权、风控与仓位管理，并使用小资金验证。

## 功能概览

- **行情采集**：从交易所拉取 K 线（当前以 Binance 作为目标实现）
- **链上采集**：从 Glassnode 拉取链上指标（当前以“巨鲸交易数”作为示例）
- **策略库**：
  - `MacdStrategy`（MACD 交叉）
  - `BollingerStrategy`（布林带突破）
  - `WhaleCombinedStrategy`（鲸鱼指标 + MACD 联合）
  - `McpPredictionStrategy`（可选：调用大模型预测）
- **信号融合**：过滤 `HOLD` 后选择置信度最高信号作为最终信号
- **通知**：Telegram / Email（当前实现为占位：仅日志输出）
- **执行**：`paper` 仅日志，`live` 走 `OrderExecutor`（当前同样为占位：仅日志输出）

## 运行依赖

- Java 21
- Maven 3.x
- PostgreSQL 13+（或你本地可用版本）

## 快速开始

### 1) 准备数据库

创建数据库：

```bash
createdb crypto_trader
```

或使用你自己的 PostgreSQL，并在 `application.yml` 中修改连接信息。

### 2) 配置参数（建议用环境变量）

项目读取 `src/main/resources/application.yml`。你可以直接复制一份示例配置：

- 将 `src/main/resources/application.example.yml` 复制为 `src/main/resources/application.yml`（或仅通过环境变量覆盖）
- 将下列环境变量填好（没有真实接入时也可以先不配，但相应模块会拿不到数据）

关键环境变量：

- `BINANCE_API_KEY` / `BINANCE_SECRET_KEY`
- `GLASSNODE_API_KEY`
- `MCP_API_KEY`（当 `crypto.mcp.enabled=true`）
- `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`

### 3) 启动

```bash
mvn spring-boot:run
```

健康检查：

- `GET /health` 返回 `OK`

## 架构与代码入口

### 定时任务

- `com.crypto.trader.scheduler.DataCollectionScheduler`
  - 每 60 秒：采集 `crypto.watch-list` 的 1m K 线 → `klines` 表
  - 每小时：采集链上指标（whale）→ `onchain_metrics` 表
- `com.crypto.trader.scheduler.StrategyScheduler`
  - 每 60 秒：对 `crypto.watch-list` 并行执行策略（注意并发安全）

### 策略执行链路

- `com.crypto.trader.service.strategy.StrategyExecutor`
  - 从数据库读取近期 `Kline` 与 `OnChainMetric`
  - 遍历所有 `TradingStrategy` 生成信号（过滤掉 `HOLD`）
  - 选择最高 `confidence` 的信号
  - 通知：`Notifier`
  - 执行：`crypto.trading.mode=live` 时调用 `OrderExecutor`

### 数据模型

- `Kline` → 表 `klines`
- `OnChainMetric` → 表 `onchain_metrics`
- `Signal` → 表 `signals`（当前仅作为实体存在；执行链路暂未落库）
- `TradeRecord` → 表 `trade_records`（当前仅作为实体存在；真实下单后可用于记录成交/订单）

## 配置说明（application.yml）

常用项：

- `crypto.watch-list`: 关注交易对列表（例：`BTCUSDT,ETHUSDT`）
- `crypto.trading.mode`: `paper` 或 `live`
- `crypto.trading.max-position-size-usdt`: 单仓最大名义金额（尚未在风控里落地）
- `crypto.trading.max-daily-trades`: 每日最大交易次数（尚未在风控里落地）
- `crypto.mcp.enabled`: 是否启用大模型策略

## 当前缺口（你接下来通常要补的）

- **交易所接入**：`BinanceClient#getKlines`、签名鉴权、下单 DTO/响应解析、账户查询
- **链上接入**：`GlassnodeClient` endpoint 拼接与响应映射
- **通知接入**：Telegram Bot API / SMTP
- **风控与仓位**：`RiskManager`、`PositionManager` 的真实约束落地
- **数据与一致性**：定时采集与策略执行的顺序/延迟、K 线排序与去重、异常与重试策略

## 安全提示

- 不要把真实 API Key 写死在配置文件里；优先使用环境变量
- 启用 `live` 前请先：
  - 完成风控（最大仓位/频率/止损止盈/滑点等）
  - 在 `paper` 或沙盒环境压测与验证
  - 小资金逐步放量

