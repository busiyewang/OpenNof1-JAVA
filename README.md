# OpenNof1 - 加密货币智能分析系统

基于 **Spring Boot + MySQL + TA4J + GradientTreeBoost + DeepSeek AI + 缠论** 的加密货币分析预测系统。定时采集 OKX 多时间框架 K 线数据、Glassnode 链上指标和 Coinglass 市场情绪数据，结合 ML 专用预测模型、缠论技术分析与 DeepSeek 大模型进行结构化分析，生成专业 HTML 邮件报告。支持预测回溯评分与 Prompt 自动进化、多策略回测与市场状态自适应投票。

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                   数据采集层（含重试）                  │
├─────────────────────────────────────────────────────┤
│  [OKX]         WebSocket(实时) + REST(回补/历史回填)   │
│  [Glassnode]   巨鲸/NUPL/SOPR/交易所资金流/活跃地址    │
│  [Coinglass]   资金费率/持仓量/恐惧贪婪/爆仓数据       │
│  ↳ 全部 API 客户端集成 RetryUtil 指数退避重试          │
└──────────────────────┬──────────────────────────────┘
                       ↓
              MySQL (klines + onchain_metrics)
                       ↓
┌─────────────────────────────────────────────────────┐
│               TA4J 统一指标计算层                      │
│  一次构建 BarSeries → 全量指标 IndicatorSnapshot       │
│  MACD / RSI / ATR / ADX / BB / KDJ / OBV / CCI / WR │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│           市场状态检测 MarketRegimeDetector            │
│  ADX + BB带宽 + ATR% → TRENDING / RANGING / VOLATILE │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│              分析引擎（三轨并行）                       │
├─────────────────────────────────────────────────────┤
│  策略引擎（每5分钟 × 6个策略并行）                      │
│  ├── 缠论策略（五重背驰验证 + 成交量 + OBV）            │
│  ├── MACD 策略（零轴强弱 + RSI + ADX + Hist过滤）      │
│  ├── 布林带策略（强趋势过滤 + RSI/KDJ/CCI 多重确认）    │
│  ├── 巨鲸策略（+ RSI + ADX + OBV + KDJ）              │
│  ├── GBT-ML 策略（81维多时间框架特征）                  │
│  └── MCP AI 预测策略（DeepSeek 直接预测）              │
│  └→ StrategyExecutor 市场状态感知加权投票              │
├─────────────────────────────────────────────────────┤
│  GradientTreeBoost 专用预测模型                       │
│  ├── 81维量化特征（多TF TA4J指标 + 价格 + 量 + 链上）  │
│  ├── 3分类输出: P(跌) / P(横盘) / P(涨)               │
│  ├── Walk-forward 验证 + Z-score 标准化               │
│  ├── 每日凌晨3点自动重训练                              │
│  └── 预测结果注入 DeepSeek Prompt                     │
├─────────────────────────────────────────────────────┤
│  AI 分析引擎（每日/每周）                              │
│  ├── 多时间框架 TA4J 全量指标                          │
│  ├── 链上数据 + 市场情绪数据                            │
│  ├── ML预测 + 策略信号 + 缠论分析 → PromptBuilder      │
│  ├── DeepSeek AI 结构化分析                           │
│  └── 预测回溯评分 → Prompt 自动进化                    │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│                  回测 + 风控系统                       │
│  ├── 滑点模拟（可配置 basis points）                    │
│  ├── 动态仓位（置信度 × 波动率倒数）                    │
│  ├── 三级风控熔断（回撤/日亏损/连败）                    │
│  └── Sharpe / 最大回撤 / 胜率 / 盈亏比                 │
└──────────────────────┬──────────────────────────────┘
                       ↓
         AnalysisEmailSender（异步）/ Telegram
              └── 结构化交易计划邮件报告
```

## 功能概览

| 模块 | 说明 |
|------|------|
| 多时间框架 K 线采集 | OKX WebSocket + REST 双模式，支持 1m/1h/4h/1d |
| 历史数据回填 | HTTP 接口一键回填数月 K 线，自动分页、去重、进度追踪 |
| 链上数据采集 | Glassnode 7 种指标 + Coinglass 8 种市场情绪指标 |
| TA4J 统一指标 | 11种技术指标一次计算：MACD/RSI/ATR/ADX/BB/KDJ/OBV/CCI/WR/SMA/EMA |
| 缠论分析 | 五重背驰验证（幅度+MACD+斜率+成交量+RSI）+ ADX走势确认 + OBV量价确认 |
| GBT ML 模型 | 81维多时间框架特征训练，3分类预测（跌/横盘/涨），walk-forward 验证 |
| DeepSeek AI 分析 | 结构化 JSON 输出：趋势、置信度、支撑阻力、交易计划（入场/止损/止盈） |
| 市场状态检测 | ADX + BB带宽 + ATR% 自动分类 TRENDING/RANGING/VOLATILE |
| 策略加权投票 | 市场状态感知的置信度加权投票，自动调整各策略权重 |
| 预测进化系统 | 24h/72h 自动回溯评分，历史表现反馈注入 Prompt |
| HTML 邮件报告 | Thymeleaf 模板，含完整交易计划和多维度数据 |
| 回测系统 | 多策略对比回测，滑点模拟 + 动态仓位 + 风控集成 |
| 风控管理 | 三级熔断：最大回撤、单日亏损限额、连败缩仓/暂停 |
| API 重试 | 全部 HTTP 客户端指数退避重试，429 限速感知 |

## 技术栈

- **Java 21** + **Spring Boot 3.2.5**
- **MySQL 8**（数据持久化）
- **Spring Data JPA**（ORM）
- **Spring WebFlux**（WebClient 异步 HTTP）
- **ta4j 0.15**（统一技术指标计算：MACD/RSI/ATR/ADX/BB/KDJ/OBV/CCI/WR）
- **Smile 2.6.0**（GradientTreeBoost 纯 Java ML 模型，无 native 依赖）
- **DeepSeek API**（AI 分析，兼容 OpenAI 格式）
- **Thymeleaf**（HTML 邮件模板）
- **JUnit 5**（40 个单元测试）
- **Lombok**

## 快速开始

### 1) 准备数据库

```bash
mysql -u root -p -e "CREATE DATABASE crypto_trader CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

启动时 Hibernate 会自动建表（`ddl-auto: update`），或手动执行 `sql/schema.sql`。

### 2) 配置参数

复制 `application-example.yml` 为 `src/main/resources/application.yml`，填入实际配置：

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
  exchange:
    okx:
      api-key: ${OKX_API_KEY:your-key}
      secret-key: ${OKX_SECRET_KEY:your-secret}
      passphrase: ${OKX_PASSPHRASE:your-passphrase}
  glassnode:
    api-key: ${GLASSNODE_API_KEY:your-key}
  mcp:
    enabled: true
    api-key: ${DEEPSEEK_API_KEY:your-key}
  ml:
    model-dir: ./ml-models
    train-months: 6
    train-cron: "0 0 3 * * ?"
    label-look-ahead: 5           # ML 标签前瞻K线数（可配置，默认5）
  analysis:
    timeframes: 1h,4h,1d
    daily-cron: "0 0 8 * * ?"
    weekly-cron: "0 0 9 ? * MON"
```

> **注意**：`application.yml` 已加入 `.gitignore`，不会提交到仓库。

### 3) 配置 API Key（环境变量）

| 变量名 | 说明 | 必需 |
|--------|------|------|
| `OKX_API_KEY` | OKX API Key | 否（K线公开接口无需） |
| `GLASSNODE_API_KEY` | Glassnode API Key | 是（链上数据） |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 是（AI 分析） |
| `COINGLASS_API_KEY` | Coinglass API Key | 否（市场情绪数据） |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP 邮箱 | 是（邮件发送） |
| `ML_MODEL_DIR` | ML模型存储目录 | 否（默认 ./ml-models） |

### 4) 启动

```bash
mvn spring-boot:run
```

### 5) 回填历史数据 + 训练模型（首次启动推荐）

```bash
# 回填 BTCUSDT 最近6个月的 1h/4h/1d K线
curl -X POST "http://localhost:8080/api/backfill/BTCUSDT/all?months=6"

# 手动触发 ML 模型训练（启动5分钟后也会自动训练）
curl -X POST "http://localhost:8080/api/ml/train/BTCUSDT?interval=1h"

# 查看模型状态
curl "http://localhost:8080/api/ml/status/BTCUSDT"
```

### 6) 运行测试

```bash
mvn test
# 40 个测试: 回测引擎(5) + 指标计算(5) + ML特征工程(8) + 特征缩放(7) + 风控(8) + 重试(7)
```

## API 接口

### 分析接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/analysis/{symbol}` | 按需触发分析并发送邮件 |
| `GET` | `/api/analysis/{symbol}/latest` | 获取最新分析报告 |
| `GET` | `/api/analysis/{symbol}/history?from=&to=` | 查询历史报告 |
| `GET` | `/api/analysis/{symbol}/performance` | 查看预测表现 |

### ML 模型接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/ml/train/{symbol}?interval=1h` | 手动触发模型训练 |
| `GET` | `/api/ml/predict/{symbol}?interval=1h` | 实时 ML 预测 |
| `GET` | `/api/ml/status/{symbol}?interval=1h` | 模型状态（就绪/特征数） |

### 数据回填接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/backfill/{symbol}/all?months=6` | 回填多个周期 |
| `POST` | `/api/backfill/{symbol}?interval=1h&startDate=2025-01-01&endDate=2026-04-01` | 指定日期范围 |
| `GET` | `/api/backfill/progress` | 查看回填进度 |

### 回测接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/backtest` | 完整回测配置（JSON body） |
| `GET` | `/api/backtest/quick/{symbol}?interval=1h&startDate=2026-01-01` | 快速回测 |

回测请求支持参数：

```json
{
  "symbol": "BTCUSDT",
  "interval": "1h",
  "windowSize": 100,
  "stopLossPercent": 3.0,
  "takeProfitPercent": 5.0,
  "slippageBps": 5.0,
  "feePercent": 0.1,
  "dynamicPositionSizing": true,
  "positionSizePercent": 0.5,
  "minPositionSizePercent": 0.1,
  "maxPositionSizePercent": 0.8,
  "riskManagementEnabled": true,
  "maxDrawdownPercent": 15.0,
  "dailyLossLimitPercent": 5.0,
  "consecutiveLossPauseThreshold": 5
}
```

## TA4J 技术指标体系

系统通过 `Ta4jIndicatorService` 统一管理所有技术指标，一次构建 BarSeries 计算全量指标：

| 指标 | 参数 | 策略/模型用途 |
|------|------|---------------|
| **MACD** | 12/26/9 | 趋势方向 + 零轴强弱 + 缠论背驰力度 |
| **RSI** | 7/14 | 超买超卖确认 + 缠论RSI背驰 |
| **ATR** | 14 | 波动率衡量 + 止损距离 + 动态仓位 |
| **ADX** | 14 | 趋势强度 + 市场状态分类 + 缠论走势确认 |
| **Bollinger** | 20/2σ | 超买超卖 + BB squeeze + 市场状态(带宽) |
| **Stochastic** | 14 | KDJ 双重超买超卖验证 |
| **OBV** | - | 量价背离 + 缠论OBV确认 |
| **CCI** | 20 | 极端超买超卖 |
| **Williams %R** | 14 | 辅助超买超卖确认 |
| **SMA** | 5/10/20/60 | 均线系统 + 趋势方向 |
| **EMA** | 12/26 | 趋势跟踪 + 强趋势判断 |

## ML 预测模型

### 架构设计

```
                ┌── 1h K线 → 22个 TA4J 指标特征
多时间框架数据 → ├── 4h K线 → 22个 TA4J 指标特征    → FeatureEngineerService
                └── 1d K线 → 22个 TA4J 指标特征        (81维特征向量)
                + 9个价格/成交量/K线形态特征                  ↓
                + 6个链上数据特征               → Z-score 标准化(FeatureScaler)
                                                          ↓
                                               → Smile GradientTreeBoost
                                                          ↓
                                               → P(跌) / P(横盘) / P(涨)
                                                          ↓
                               DeepSeek ← ML预测 + 策略信号 + 缠论 + 链上
                                                    → 分析报告
```

### 81维多时间框架特征

| 类别 | 特征数 | 来源 |
|------|--------|------|
| TA4J 指标 (×3 TF) | 66 | 1h/4h/1d 各22个：MACD×3, BB×3, RSI×2, ATR×2, ADX, SMA斜率×2, SMA偏离, EMA交叉, 短长期比, KDJ×2, OBV×2, CCI, WR |
| 价格/成交量/形态 | 9 | 1/3/5/10期收益率、振幅、收开比、量比、量变化率、上下影线、实体比 |
| 链上数据 | 6 | 巨鲸转账量/NUPL/SOPR/交易所净流入/流入/流出 |
| **合计** | **81** | |

### 训练与调度

- 启动5分钟后自动首次训练
- 每日凌晨3点自动重训练（使用最近6个月数据）
- Walk-forward 验证（80/20 分割）
- Z-score 标准化（FeatureScaler），处理常量特征
- 标签前瞻 K 线数可配置（`crypto.ml.label-look-ahead`），避免 look-ahead bias
- 衰减加权标签：近期 K 线影响更大（decay=0.7）
- 模型文件持久化到 `ml-models/` 目录

## 缠论分析模块

### 分析流程

```
K线 → 包含处理 → 分型识别(强/弱) → 笔构建 → 线段构建(特征序列)
   → 中枢识别(ZG/ZD/GG/DD) → 走势分类(+ADX确认) → 五重背驰验证 → 买卖点
```

### 五重背驰验证（第一类买卖点）

| 维度 | 方法 | 来源 |
|------|------|------|
| 价格幅度 | 最后一笔幅度 < 前同向笔幅度 | 原生 |
| MACD面积 | 最后一笔MACD柱面积 < 前笔 | TA4J |
| 斜率 | 最后一笔斜率 < 前笔 | 原生 |
| 成交量 | 最后一笔平均量 < 前笔 | K线成交量 |
| RSI | 价格新低/高但RSI未新低/高 | TA4J |

辅助确认：**OBV 背离**（资金流向与价格方向不一致）→ 额外加分

### 买卖点识别

| 买卖点 | 判断逻辑 | 置信度 |
|--------|---------|--------|
| **一买/一卖** | 五重背驰验证（核心三维≥2通过） | 0.60-0.95 |
| **二买/二卖** | 回调不破 + 低点抬高/高点降低 | 0.70-0.85 |
| **三买/三卖** | 中枢突破回踩 + 成交量放量确认 + OBV方向确认 | 0.50-0.95 |

### ADX 走势类型增强

- 中枢结构判断为趋势但 ADX < 20 → 降级为盘整（避免弱趋势中误判为趋势背驰）

## 市场状态检测与自适应投票

### MarketRegimeDetector

根据技术指标自动分类当前市场状态：

| 状态 | 判定条件 | 含义 |
|------|---------|------|
| **TRENDING** | ADX ≥ 25 | 强趋势行情 |
| **RANGING** | ADX < 20 或 BB带宽 < 0.03 | 震荡/盘整 |
| **VOLATILE** | ATR% > 3.0 或 BB带宽 > 0.08 | 高波动行情 |

### 状态感知策略权重

不同市场状态下各策略的权重乘数：

| 策略 | TRENDING | RANGING | VOLATILE |
|------|----------|---------|----------|
| MACD | **1.5** | 0.5 | 0.6 |
| 缠论 | **1.3** | 1.2 | 0.7 |
| 布林带 | 0.3 | **1.5** | 0.5 |
| GBT-ML | 1.2 | 1.2 | 0.8 |
| 巨鲸 | 1.0 | 1.0 | 0.8 |
| MCP-AI | 0.5 | 0.5 | 0.3 |

**逻辑**：趋势行情中趋势策略（MACD/缠论）加权、均值回归（布林带）降权；震荡行情反之；高波动行情所有策略降权，风控优先。LLM 预测（MCP-AI）因不可靠在所有状态下降权。

### StrategyExecutor 投票流程

```
6个策略并行评估 → 各产出 Signal(action, confidence, strategyName)
      ↓
MarketRegimeDetector.detect(snap) → 当前市场状态
      ↓
每个信号: adjustedConfidence = confidence × regimeWeight(状态, 策略名)
      ↓
BUY总分 = Σ adjustedConfidence(BUY信号)
SELL总分 = Σ adjustedConfidence(SELL信号)
      ↓
矛盾检测: |BUY-SELL| / (BUY+SELL) < 0.2 → HOLD（信号冲突）
      ↓
最终方向 = 得分高者, 最终置信度 = 胜方得分 / 总得分
```

## 策略引擎（6个并行策略）

| 策略 | 核心逻辑 | 改进点 | 置信度 |
|------|---------|--------|--------|
| **缠论** | 五重背驰验证 → 三类买卖点 | + ADX走势确认 + OBV量价背离 | 0.50-0.95 |
| **MACD** | 金叉/死叉 + 零轴强弱 | + Histogram 强度过滤 + RSI超买超卖惩罚 + ADX<20横盘过滤 + OBV量价配合 | 0.35-0.95 |
| **布林带** | BB突破 + 多振荡器确认 | + 强趋势过滤(ADX≥30不逆势) + BB squeeze检测 + CCI/WR/KDJ多重确认 | 0.40-0.95 |
| **巨鲸联合** | 巨鲸活动 + 技术确认 | + RSI + ADX + OBV + KDJ 多重确认 | 0.75-0.95 |
| **GBT-ML** | 81维特征 GradientTreeBoost | 3分类概率输出，walk-forward 验证 | 0.45-0.95 |
| **MCP AI** | DeepSeek 直接预测 | 结构化 JSON 输出 | 0.50-0.90 |

## 回测系统

### 核心能力

- **滑动窗口回测**：逐 K 线滚动执行策略，模拟真实交易
- **滑点模拟**：开平仓价格加入可配置滑点（basis points），买入价上浮、卖出价下浮
- **动态仓位**：`仓位 = 基础比例 × 置信度 × (1 / 波动率因子) × 风控缩仓系数`，自动在 min/max 之间调整
- **止损/止盈**：可配置百分比止损和止盈
- **双向交易**：SELL 信号平掉 LONG 后自动开 SHORT（反向开仓）

### 风控管理（RiskManager）

三级熔断机制，防止一次大亏吃掉所有利润：

| 风控层级 | 触发条件 | 效果 |
|---------|---------|------|
| **最大回撤熔断** | 累计回撤 ≥ 阈值（默认15%） | 完全停止交易 |
| **单日亏损限额** | 当日亏损 ≥ 阈值（默认5%） | 暂停当日交易，次日自动重置 |
| **连败缩仓** | 连续亏损 ≥ 阈值（默认3次） | 仓位 × 0.5^n，最低缩至10%；连败≥5次暂停 |

### 回测指标

| 指标 | 说明 |
|------|------|
| 总收益率 | 累计PnL / 初始资金 |
| Sharpe Ratio | 风险调整收益 |
| 最大回撤 | 峰值到谷底最大跌幅 |
| 胜率 | 盈利交易数 / 总交易数 |
| 盈亏比 | 平均盈利 / 平均亏损 |
| 总滑点成本 | 所有交易累计滑点 |
| 平均仓位比例 | 动态仓位实际使用的平均比例 |
| 风控触发次数 | 回撤熔断 + 日亏损限额的触发统计 |

## API 重试机制（RetryUtil）

所有 HTTP 客户端统一使用 `RetryUtil.withRetry()` 指数退避重试：

- **默认参数**：最多3次重试，初始延迟1秒，退避系数2.0
- **429 限速**：自动重试并加倍延迟
- **4xx 客户端错误**（非429）：不重试，直接抛出
- **5xx 服务端错误**：重试

覆盖的 API 客户端（6个）：
- `OkxClient` — K线数据
- `GlassnodeClient` — 链上指标
- `CoinglassClient` — 爆仓数据
- `FearGreedClient` — 恐惧贪婪指数
- `OkxMarketDataClient` — 资金费率/持仓量
- `DeepSeekClient` — AI 分析

## 预测进化系统

```
分析报告 → 24h/72h 回溯评分 → 统计准确率和错误模式 → 注入下次 Prompt
```

### 评分规则

| 维度 | 满分 | 判定 |
|------|------|------|
| 趋势方向 | 50 | 预测方向与实际涨跌一致 |
| 支撑位 | 25 | 实际最低价未跌破预测支撑位（1%容差） |
| 阻力位 | 25 | 实际最高价未突破预测阻力位（1%容差） |
| 置信度校准 | ±10 | 高置信度错误扣分，低置信度正确加分 |

## 邮件报告

报告包含以下板块：
- **趋势摘要**：方向、置信度、风险等级
- **交易计划**：具体入场价、止损、止盈1/止盈2、仓位比例、入场条件、出场条件
- **多时间框架分析**：11种技术指标数据
- **ML 预测**：概率分布和方向（含预测前瞻周期）
- **缠论分析**：笔/中枢/走势类型/买卖点信号
- **链上数据**：NUPL、SOPR、交易所资金流、鲸鱼活动
- **市场情绪**：资金费率、持仓量、恐惧贪婪指数、爆仓数据

## 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| K线补漏（1m） | 每 60 秒 | REST 回补 WebSocket 可能遗漏的K线 |
| K线补漏（多周期） | 每 5 分钟 | REST 回补 1h/4h/1d K线 |
| 链上数据采集 | 每小时 | Glassnode 7 种指标 |
| 策略评估 | 每 5 分钟 | 6 种策略并行评估 + 市场状态感知投票 |
| ML 模型训练 | 每日 3:00 | GradientTreeBoost 自动重训练 |
| 每日分析 | 每日 8:00 | DeepSeek AI 分析 + 邮件报告 |
| 每周分析 | 每周一 9:00 | 周度分析 + 邮件报告 |
| 预测评分 | 每 6 小时 | 24h/72h 回溯评分 |

## 测试覆盖

40 个单元测试，覆盖核心模块：

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|---------|
| `BacktestEngineTest` | 5 | 基础交易、滑点影响、止损触发、动态仓位、风控熔断 |
| `MetricsCalculatorTest` | 5 | 零交易、全胜、全败、最大回撤、滑点统计 |
| `FeatureEngineerServiceTest` | 8 | 涨/跌/中性标签、null处理、单K线、链上数据Map、数据不足、衰减加权 |
| `FeatureScalerTest` | 7 | fit-transform、原地缩放、常量特征、未训练异常、null/空、零均值单位方差 |
| `RiskManagerTest` | 8 | 正常交易、回撤熔断、未触发、连败追踪、连败暂停、日亏损、日重置、最小缩仓 |
| `RetryUtilTest` | 7 | 首次成功、重试成功、重试耗尽、4xx不重试、429重试、指数退避、500重试 |

## 项目结构

```
src/main/java/com/crypto/trader/
├── backtest/                      # 回测系统
│   ├── BacktestController             # 回测 API
│   ├── BacktestService                # 回测编排（含风控集成）
│   ├── model/
│   │   ├── BacktestRequest            # 回测配置（滑点/动态仓位/风控参数）
│   │   ├── BacktestReport             # 回测报告（含滑点/仓位/风控统计）
│   │   └── Trade                      # 交易记录（含滑点成本/仓位比例/退出原因）
│   └── engine/
│       ├── BacktestEngine             # 回测引擎（滑点+动态仓位+风控）
│       └── MetricsCalculator          # Sharpe/回撤/胜率/滑点统计
├── client/
│   ├── exchange/                  # OKX REST + WebSocket（含重试）
│   ├── glassnode/                 # Glassnode 链上数据（含重试）
│   ├── market/                    # Coinglass + FearGreed（含重试）
│   └── mcp/                      # DeepSeek AI + DTO（含重试）
├── config/                        # Spring 配置
├── controller/
│   ├── AnalysisController             # 分析 API
│   ├── BackfillController             # 数据回填 API
│   ├── MlController                   # ML 模型训练/预测 API
│   └── StrategyController             # 策略 API
├── model/                         # JPA 实体
├── repository/                    # Spring Data 仓库
├── scheduler/
│   ├── DataCollectionScheduler        # 数据采集
│   ├── AnalysisReportScheduler        # 分析报告
│   ├── StrategyScheduler              # 策略评估
│   ├── MlTrainScheduler               # ML 模型定时训练
│   ├── PredictionScoreScheduler       # 预测评分
│   └── DailySummaryScheduler          # 信号摘要
├── service/
│   ├── analysis/                  # AI 分析核心
│   │   ├── AnalysisService            # 编排器（ML+策略+缠论+AI）
│   │   ├── PromptBuilder              # Prompt 构建（含ML预测+历史反馈）
│   │   ├── AnalysisResponseParser     # JSON 解析
│   │   └── PredictionScorerService    # 预测回溯评分
│   ├── collector/                 # 数据采集
│   ├── indicator/                 # 技术指标
│   │   ├── Ta4jIndicatorService       # TA4J 统一指标（11种，一次计算）
│   │   ├── ChanCalculator             # 缠论（五重验证+ADX+OBV）
│   │   ├── MacdCalculator             # MACD（兼容旧调用）
│   │   ├── BollingerBandsCalculator   # 布林带（兼容旧调用）
│   │   └── chan/                       # 缠论数据模型
│   ├── ml/                        # ML 模型
│   │   ├── MlModelService             # 训练/预测/持久化（可配置前瞻）
│   │   ├── FeatureEngineerService     # 81维多TF特征工程 + 衰减加权标签
│   │   ├── FeatureScaler              # Z-score 标准化
│   │   └── MlPrediction               # 预测结果 DTO（含预测周期）
│   ├── notifier/                  # 通知
│   │   ├── AnalysisEmailSender        # HTML 报告邮件
│   │   ├── EmailNotifier              # 邮件
│   │   └── TelegramNotifier           # Telegram
│   ├── risk/                      # 风控管理
│   │   ├── RiskManager                # 三级熔断（回撤/日亏损/连败）
│   │   └── RiskConfig                 # 风控参数配置
│   └── strategy/                  # 交易策略（6个）
│       ├── TradingStrategy            # 策略接口
│       ├── ChanStrategy               # 缠论
│       ├── MacdStrategy               # MACD（零轴强弱+Hist过滤+RSI+ADX+OBV）
│       ├── BollingerStrategy          # 布林带（强趋势过滤+RSI+KDJ+CCI+WR）
│       ├── WhaleCombinedStrategy      # 巨鲸（+RSI+ADX+OBV+KDJ）
│       ├── MlPredictionStrategy       # GBT ML 预测
│       ├── McpPredictionStrategy      # DeepSeek AI 预测
│       ├── MarketRegimeDetector       # 市场状态分类器
│       └── StrategyExecutor           # 策略执行器（市场状态感知加权投票）
└── util/
    └── RetryUtil                  # HTTP 指数退避重试（429感知）

src/test/java/com/crypto/trader/
├── backtest/engine/
│   ├── BacktestEngineTest             # 回测引擎测试（5）
│   └── MetricsCalculatorTest          # 指标计算测试（5）
├── service/
│   ├── ml/
│   │   ├── FeatureEngineerServiceTest # 特征工程测试（8）
│   │   └── FeatureScalerTest          # 特征缩放测试（7）
│   └── risk/
│       └── RiskManagerTest            # 风控管理测试（8）
└── util/
    └── RetryUtilTest                  # 重试工具测试（7）
```

## 安全提示

- **不要**把真实 API Key 写死在 `application.yml` 中，优先使用环境变量
- `application.yml` 已加入 `.gitignore`，不会提交到仓库
- 本系统仅用于**分析预测**，不执行真实交易
- 分析报告仅供参考，不构成投资建议
