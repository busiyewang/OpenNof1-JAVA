# OpenNof1 - 加密货币智能分析系统

基于 **Spring Boot + MySQL + TA4J + XGBoost + DeepSeek AI + 缠论** 的加密货币分析预测系统。定时采集 OKX 多时间框架 K 线数据、Glassnode 链上指标和 Coinglass 市场情绪数据，结合 XGBoost 专用预测模型、缠论技术分析与 DeepSeek 大模型进行结构化分析，生成专业 HTML 邮件报告。支持预测回溯评分与 Prompt 自动进化。

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                   数据采集层                          │
├─────────────────────────────────────────────────────┤
│  [OKX]         WebSocket(实时) + REST(回补/历史回填)   │
│  [Glassnode]   巨鲸/NUPL/SOPR/交易所资金流/活跃地址    │
│  [Coinglass]   资金费率/持仓量/恐惧贪婪/爆仓数据       │
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
│              分析引擎（三轨并行）                       │
├─────────────────────────────────────────────────────┤
│  策略引擎（每5分钟 × 6个策略并行）                      │
│  ├── 缠论策略（五重背驰验证 + 成交量 + OBV）            │
│  ├── MACD 策略（+ RSI过滤 + ADX趋势强度）              │
│  ├── 布林带策略（+ RSI/KDJ/CCI 多重确认）              │
│  ├── 巨鲸策略（+ RSI + ADX + OBV + KDJ）              │
│  ├── XGBoost-ML 策略（40维特征专用模型）                │
│  └── MCP AI 预测策略（DeepSeek 直接预测）              │
├─────────────────────────────────────────────────────┤
│  XGBoost 专用预测模型                                 │
│  ├── 40维量化特征（TA4J指标 + 价格 + 量 + 链上）        │
│  ├── 3分类输出: P(跌) / P(横盘) / P(涨)               │
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
| XGBoost ML 模型 | 40维特征训练，3分类预测（跌/横盘/涨），自动重训练 |
| DeepSeek AI 分析 | 结构化 JSON 输出：趋势、置信度、支撑阻力、交易计划（入场/止损/止盈） |
| 预测进化系统 | 24h/72h 自动回溯评分，历史表现反馈注入 Prompt |
| HTML 邮件报告 | Thymeleaf 模板，含完整交易计划和多维度数据 |
| 回测系统 | 多策略对比回测，Sharpe/最大回撤/胜率/盈亏比等指标 |

## 技术栈

- **Java 21** + **Spring Boot 3.2.5**
- **MySQL 8**（数据持久化）
- **Spring Data JPA**（ORM）
- **Spring WebFlux**（WebClient 异步 HTTP）
- **ta4j 0.15**（统一技术指标计算：MACD/RSI/ATR/ADX/BB/KDJ/OBV/CCI/WR）
- **Smile 2.6.0**（GradientTreeBoost 纯 Java ML 模型，无 native 依赖）
- **DeepSeek API**（AI 分析，兼容 OpenAI 格式）
- **Thymeleaf**（HTML 邮件模板）
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

## TA4J 技术指标体系

系统通过 `Ta4jIndicatorService` 统一管理所有技术指标，一次构建 BarSeries 计算全量指标：

| 指标 | 参数 | 策略/模型用途 |
|------|------|---------------|
| **MACD** | 12/26/9 | 趋势方向 + 缠论背驰力度 |
| **RSI** | 7/14 | 超买超卖确认 + 缠论RSI背驰 |
| **ATR** | 14 | 波动率衡量 + 止损距离计算 |
| **ADX** | 14 | 趋势强度 + 缠论走势类型确认 |
| **Bollinger** | 20/2σ | 超买超卖 + BB squeeze检测 |
| **Stochastic** | 14 | KDJ 双重超买超卖验证 |
| **OBV** | - | 量价背离 + 缠论OBV确认 |
| **CCI** | 20 | 极端超买超卖 |
| **Williams %R** | 14 | 辅助超买超卖确认 |
| **SMA** | 5/10/20/60 | 均线系统 |
| **EMA** | 12/26 | 趋势跟踪 |

## XGBoost ML 预测模型

### 架构设计

```
数据 → FeatureEngineerService（40维特征）→ XGBoost → P(跌)/P(横盘)/P(涨)
                                                        ↓
DeepSeek ← ML预测结果 + 策略信号 + 缠论 + 链上数据 → 分析报告
```

**核心思路**：让 ML 模型做预测，让 LLM 做报告。DeepSeek 不再猜测涨跌，而是综合 ML 预测和多维数据生成可执行的交易计划。

### 40维特征

| 类别 | 特征 | 来源 |
|------|------|------|
| 价格 (6) | 1/3/5/10期收益率、振幅、收盘/开盘比 | K线计算 |
| 成交量 (3) | 量比、1/3期量变化率 | K线计算 |
| MACD (3) | value/signal/histogram | TA4J |
| 布林带 (3) | 位置/带宽/偏离中轨 | TA4J |
| RSI (2) | RSI7/RSI14 | TA4J |
| ATR (2) | ATR14/波动率% | TA4J |
| ADX (1) | ADX14 | TA4J |
| K线形态 (3) | 上影线/下影线/实体比 | K线计算 |
| 均线 (5) | SMA5斜率/SMA20斜率/偏离/EMA交叉/短长期比 | TA4J |
| KDJ (2) | Stochastic K/D | TA4J |
| OBV (2) | 斜率/方向 | TA4J |
| CCI (1) | CCI20 | TA4J |
| Williams %R (1) | WR14 | TA4J |
| 链上 (6) | 巨鲸/NUPL/SOPR/交易所净流入/流入/流出 | Glassnode |

### 训练与调度

- 启动5分钟后自动首次训练
- 每日凌晨3点自动重训练（使用最近6个月数据）
- 80/20 训练/验证集分割
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

## 策略引擎（6个并行策略）

| 策略 | 改进点 | 置信度范围 |
|------|--------|-----------|
| **缠论** | 五重背驰验证 + ADX + OBV | 0.60-0.95 |
| **MACD** | + RSI过滤超买超卖 + ADX<20不出信号 + OBV量价配合 | 0.35-0.95 |
| **布林带** | + RSI/KDJ/CCI/WilliamsR 多重确认 + BB squeeze检测 | 0.40-0.95 |
| **巨鲸联合** | + RSI + ADX + OBV + KDJ 多重确认 | 0.75-0.95 |
| **GBT-ML** | 40维特征 Smile GradientTreeBoost，概率输出 | 0.45-0.95 |
| **MCP AI** | DeepSeek 直接预测 | 0.50-0.90 |

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
- **XGBoost ML 预测**：概率分布和方向
- **缠论分析**：笔/中枢/走势类型/买卖点信号
- **链上数据**：NUPL、SOPR、交易所资金流、鲸鱼活动
- **市场情绪**：资金费率、持仓量、恐惧贪婪指数、爆仓数据

## 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| K线补漏（1m） | 每 60 秒 | REST 回补 WebSocket 可能遗漏的K线 |
| K线补漏（多周期） | 每 5 分钟 | REST 回补 1h/4h/1d K线 |
| 链上数据采集 | 每小时 | Glassnode 7 种指标 |
| 策略评估 | 每 5 分钟 | 6 种策略并行评估 |
| ML 模型训练 | 每日 3:00 | XGBoost 自动重训练 |
| 每日分析 | 每日 8:00 | DeepSeek AI 分析 + 邮件报告 |
| 每周分析 | 每周一 9:00 | 周度分析 + 邮件报告 |
| 预测评分 | 每 6 小时 | 24h/72h 回溯评分 |

## 项目结构

```
src/main/java/com/crypto/trader/
├── backtest/                      # 回测系统
│   ├── BacktestController             # 回测 API
│   ├── BacktestService                # 回测编排
│   └── engine/
│       ├── BacktestEngine             # 回测引擎（滑动窗口）
│       └── MetricsCalculator          # 收益/Sharpe/回撤计算
├── client/
│   ├── exchange/                  # OKX REST + WebSocket
│   ├── glassnode/                 # Glassnode 链上数据
│   ├── market/                    # Coinglass + FearGreed
│   └── mcp/                      # DeepSeek AI + DTO
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
└── service/
    ├── analysis/                  # AI 分析核心
    │   ├── AnalysisService            # 编排器（ML+策略+缠论+AI）
    │   ├── PromptBuilder              # Prompt 构建（含ML预测+历史反馈）
    │   ├── AnalysisResponseParser     # JSON 解析
    │   └── PredictionScorerService    # 预测回溯评分
    ├── collector/                 # 数据采集
    ├── indicator/                 # 技术指标
    │   ├── Ta4jIndicatorService       # TA4J 统一指标（11种，一次计算）
    │   ├── ChanCalculator             # 缠论（五重验证+ADX+OBV）
    │   ├── MacdCalculator             # MACD（兼容旧调用）
    │   ├── BollingerBandsCalculator   # 布林带（兼容旧调用）
    │   └── chan/                       # 缠论数据模型
    ├── ml/                        # XGBoost ML 模型
    │   ├── MlModelService             # 训练/预测/持久化
    │   ├── FeatureEngineerService     # 40维特征工程（基于TA4J）
    │   └── MlPrediction               # 预测结果 DTO
    ├── notifier/                  # 通知
    │   ├── AnalysisEmailSender        # HTML 报告邮件
    │   ├── EmailNotifier              # 邮件
    │   └── TelegramNotifier           # Telegram
    └── strategy/                  # 交易策略（6个）
        ├── ChanStrategy               # 缠论
        ├── MacdStrategy               # MACD（+RSI+ADX+OBV）
        ├── BollingerStrategy          # 布林带（+RSI+KDJ+CCI+WR）
        ├── WhaleCombinedStrategy      # 巨鲸（+RSI+ADX+OBV+KDJ）
        ├── MlPredictionStrategy       # XGBoost ML 预测
        ├── McpPredictionStrategy      # DeepSeek AI 预测
        └── StrategyExecutor           # 策略执行器
```

## 安全提示

- **不要**把真实 API Key 写死在 `application.yml` 中，优先使用环境变量
- `application.yml` 已加入 `.gitignore`，不会提交到仓库
- 本系统仅用于**分析预测**，不执行真实交易
- 分析报告仅供参考，不构成投资建议
