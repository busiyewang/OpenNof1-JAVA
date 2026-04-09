# OpenNof1 - 加密货币智能分析系统

基于 **Spring Boot + MySQL + ta4j + DeepSeek AI + 缠论** 的加密货币分析预测系统。定时采集 OKX 多时间框架 K 线数据和 Glassnode 链上指标，结合缠论技术分析与 DeepSeek 大模型进行结构化分析，生成专业 HTML 邮件报告。支持预测回溯评分与 Prompt 自动进化。

## 系统架构

```
[OKX API]
  ├── WebSocket: 实时 K 线推送 (1m/1h/4h/1d)
  ├── REST API:  回补 K 线数据
  └── 历史回填:  一次性拉取数月历史数据（自动分页）
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

              ┌─────────────────────────────────┐
              │        分析引擎（双轨并行）         │
              ├─────────────────────────────────┤
              │  策略引擎（每5分钟）               │
              │  ├── 缠论策略（笔/中枢/背驰/买卖点） │
              │  ├── MACD 策略                   │
              │  ├── 布林带策略                   │
              │  ├── 鲸鱼联合策略                  │
              │  └── MCP 预测策略                 │
              ├─────────────────────────────────┤
              │  AI 分析引擎（每日/每周）           │
              │  ├── 多时间框架技术指标计算          │
              │  ├── 链上数据汇总                  │
              │  ├── PromptBuilder（含历史反馈）    │
              │  ├── DeepSeek AI 分析             │
              │  └── 预测回溯评分 → Prompt 进化     │
              └─────────────────────────────────┘
                    ↓
              AnalysisEmailSender（异步）
                └── Thymeleaf HTML 邮件模板 → 发送报告
```

## 功能概览

| 模块 | 说明 |
|------|------|
| 多时间框架 K 线采集 | OKX WebSocket + REST 双模式，支持 1m/1h/4h/1d |
| 历史数据回填 | HTTP 接口一键回填数月 K 线，自动分页、去重、进度追踪 |
| 链上数据采集 | Glassnode 7 种指标：巨鲸转账、NUPL、SOPR、交易所资金流、活跃地址 |
| 缠论分析 | K线包含处理→分型（强弱）→笔→线段（特征序列）→中枢→背驰（三重验证）→三类买卖点 |
| DeepSeek AI 分析 | 结构化 JSON 输出：趋势方向、置信度、支撑/阻力位、风险评估、展望 |
| 预测进化系统 | 24h/72h 自动回溯评分，历史表现反馈注入 Prompt，AI 推理持续进化 |
| HTML 邮件报告 | Thymeleaf 模板，颜色编码趋势，多时间框架表格，链上数据解读 |
| 定时调度 | 每日早 8 点 + 每周一早 9 点分析，每 5 分钟策略评估，每 6 小时预测评分 |
| REST API | 按需触发分析、查询报告、查看预测表现、历史数据回填 |

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

复制 `application.yml.example` 为 `src/main/resources/application.yml`，填入实际配置：

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

> **注意**：`application.yml` 已加入 `.gitignore`，不会提交到仓库。

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

### 5) 回填历史数据（首次启动推荐）

```bash
# 回填 BTCUSDT 最近3个月的 1h/4h/1d K线
curl -X POST "http://localhost:8080/api/backfill/BTCUSDT/all?months=3"

# 回填指定日期范围
curl -X POST "http://localhost:8080/api/backfill/BTCUSDT?interval=1h&startDate=2025-01-01&endDate=2026-04-10"

# 查看回填进度
curl "http://localhost:8080/api/backfill/progress"
```

## API 接口

### 分析接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `POST` | `/api/analysis/{symbol}` | 按需触发分析并发送邮件 |
| `GET` | `/api/analysis/{symbol}/latest` | 获取最新分析报告 |
| `GET` | `/api/analysis/{symbol}/history?from=&to=` | 查询历史报告 |
| `GET` | `/api/analysis/{symbol}/performance` | 查看预测表现（准确率、评分） |
| `POST` | `/api/analysis/{symbol}/score` | 手动触发预测评分 |

### 数据回填接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/backfill/{symbol}?interval=1h&months=3` | 回填单个周期 |
| `POST` | `/api/backfill/{symbol}/all?months=3` | 回填多个周期（1h/4h/1d） |
| `POST` | `/api/backfill/{symbol}?interval=4h&startDate=2025-01-01&endDate=2026-04-01` | 指定日期范围回填 |
| `GET` | `/api/backfill/{symbol}/progress?interval=1h` | 查看单个回填进度 |
| `GET` | `/api/backfill/progress` | 查看所有回填任务 |

### 示例

```bash
# 立即触发 BTC 分析
curl -X POST http://localhost:8080/api/analysis/BTCUSDT

# 查看最新报告
curl http://localhost:8080/api/analysis/BTCUSDT/latest

# 查看预测准确率
curl http://localhost:8080/api/analysis/BTCUSDT/performance

# 回填3个月数据
curl -X POST "http://localhost:8080/api/backfill/BTCUSDT/all?months=3"
```

## 缠论分析模块

系统集成了完整的缠论（缠中说禅）技术分析：

### 分析流程

```
K线输入 → 包含处理 → 分型识别(强/弱) → 笔构建 → 线段构建(特征序列)
       → 中枢识别(ZG/ZD/GG/DD) → 走势分类(趋势/盘整) → 背驰判断 → 买卖点
```

### 买卖点识别

| 买卖点 | 判断逻辑 | 置信度 |
|--------|---------|--------|
| **一买** | 下跌趋势背驰（三重验证：幅度+MACD面积+斜率） | 0.60-0.95 |
| **一卖** | 上涨趋势背驰（三重验证） | 0.60-0.95 |
| **二买** | 一买后回调不创新低，低点抬高 | 0.70-0.85 |
| **二卖** | 一卖后反弹不创新高，高点降低 | 0.70-0.85 |
| **三买** | 突破中枢上沿后回踩不入中枢 | 0.75-0.90 |
| **三卖** | 跌破中枢下沿后反弹不入中枢 | 0.75-0.90 |

### 置信度影响因素

- **背驰类型**：趋势背驰（≥2中枢）> 盘整背驰（1中枢）
- **分型强弱**：强分型 > 弱分型
- **验证通过数**：3/3 通过 > 2/3 通过
- **多信号共振**：同方向多个买卖点同时出现 → 加分

## 预测进化系统

系统会自动评估 AI 预测的准确性，并将反馈注入后续分析，形成自我进化的闭环：

```
分析报告生成 → 24h/72h 后自动回溯评分 → 统计准确率和错误模式
    → 下次分析时注入 Prompt → DeepSeek 基于反馈改进推理
```

### 评分规则

| 维度 | 满分 | 判定 |
|------|------|------|
| 趋势方向 | 50 | 预测方向与实际涨跌一致 |
| 支撑位 | 25 | 实际最低价未跌破预测支撑位（1%容差） |
| 阻力位 | 25 | 实际最高价未突破预测阻力位（1%容差） |
| 置信度校准 | ±10 | 高置信度错误扣分，低置信度正确加分 |

### Prompt 进化示例

随着数据积累，Prompt 中会自动注入类似反馈：

> 过去30天预测表现：共28次分析，趋势准确率64%，平均得分58/100。
> 近期典型错误：
> - 趋势判断错误：预测看多，实际下跌-3.21%
> - 支撑位失效：预测82000，实际最低80150
> 请在本次分析中特别注意避免以上错误模式。

## 邮件报告

报告包含以下板块：
- **趋势摘要**：方向（看多/看空/中性）、置信度、风险等级
- **价格概览**：当前价格、支撑位、阻力位
- **多时间框架分析**：1h/4h/1d 的 MACD 状态和布林带位置
- **链上数据解读**：NUPL、SOPR、交易所资金流、鲸鱼活动
- **市场展望**：短期（1-3天）和中期（1-2周）
- **风险因子**：当前主要风险列表
- **AI 详细分析**：DeepSeek 完整推理

## 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| K线补漏（1m） | 每 60 秒 | REST 回补 WebSocket 可能遗漏的1分钟K线 |
| K线补漏（多周期） | 每 5 分钟 | REST 回补 1h/4h/1d K线 |
| 链上数据采集 | 每小时 | Glassnode 7 种指标 |
| 策略评估 | 每 5 分钟 | 5 种策略并行评估，取最高置信度信号 |
| 每日分析 | 每日 8:00 | DeepSeek AI 分析 + 邮件报告 |
| 每周分析 | 每周一 9:00 | DeepSeek AI 周度分析 + 邮件报告 |
| 信号摘要 | 每 2 小时 | BUY/SELL 信号统计 |
| 预测评分 | 每 6 小时 | 24h/72h 回溯评分 |

## 数据库表

| 表 | 说明 |
|----|------|
| `klines` | K 线数据 (OHLCV)，唯一索引: (symbol, interval, timestamp) |
| `onchain_metrics` | 链上指标，唯一索引: (symbol, metric_name, timestamp) |
| `signals` | 策略交易信号 |
| `indicator_values` | 技术指标计算缓存 |
| `analysis_reports` | 分析报告（含 AI 分析结果） |
| `prediction_scores` | 预测回溯评分（准确率、错误摘要） |

## 项目结构

```
src/main/java/com/crypto/trader/
├── client/
│   ├── exchange/              # OKX REST + WebSocket 客户端
│   ├── glassnode/             # Glassnode 链上数据客户端
│   └── mcp/                  # DeepSeek AI 客户端 + DTO
├── config/                    # 配置（调度线程池、异步、WebClient）
├── controller/
│   ├── AnalysisController     # 分析 API + 预测表现
│   ├── BackfillController     # 历史数据回填 API
│   └── StrategyController     # 策略 API
├── model/                     # JPA 实体
│   ├── Kline                  # K 线
│   ├── AnalysisReport         # 分析报告
│   ├── PredictionScore        # 预测评分
│   ├── Signal                 # 交易信号
│   └── ...
├── repository/                # Spring Data 仓库
├── scheduler/
│   ├── DataCollectionScheduler    # 数据采集调度
│   ├── AnalysisReportScheduler    # 分析报告调度
│   ├── StrategyScheduler          # 策略评估调度
│   ├── PredictionScoreScheduler   # 预测评分调度
│   └── DailySummaryScheduler      # 信号摘要调度
└── service/
    ├── analysis/              # AI 分析核心
    │   ├── AnalysisService        # 编排器
    │   ├── PromptBuilder          # Prompt 构建（含历史反馈）
    │   ├── AnalysisResponseParser # JSON 解析（括号平衡匹配）
    │   └── PredictionScorerService # 预测回溯评分
    ├── collector/             # 数据采集
    │   ├── KlineCollector         # K 线采集（WS + REST）
    │   ├── KlineBackfillService   # 历史数据回填
    │   └── OnChainCollector       # 链上数据采集（含去重）
    ├── indicator/             # 技术指标
    │   ├── ChanCalculator         # 缠论核心计算器
    │   ├── MacdCalculator         # MACD
    │   ├── BollingerBandsCalculator # 布林带
    │   └── chan/                   # 缠论数据模型
    │       ├── ChanFractal        # 分型（顶/底，强/弱）
    │       ├── ChanBi             # 笔
    │       ├── ChanSegment        # 线段
    │       ├── ChanZhongshu       # 中枢
    │       ├── ChanSignalPoint    # 买卖点（三类六种）
    │       └── ChanResult         # 完整分析结果
    ├── notifier/              # 通知
    │   ├── EmailNotifier          # 邮件通知
    │   ├── TelegramNotifier       # Telegram 通知
    │   └── AnalysisEmailSender    # HTML 分析报告邮件（异步）
    └── strategy/              # 交易策略
        ├── ChanStrategy           # 缠论策略
        ├── MacdStrategy           # MACD 策略
        ├── BollingerStrategy      # 布林带策略
        ├── WhaleCombinedStrategy  # 鲸鱼联合策略
        ├── McpPredictionStrategy  # AI 预测策略
        └── StrategyExecutor       # 策略执行器（线程池）
```

## 安全提示

- **不要**把真实 API Key 写死在 `application.yml` 中，优先使用环境变量
- `application.yml` 已加入 `.gitignore`，不会提交到仓库
- 本系统仅用于**分析预测**，不执行真实交易
- 分析报告仅供参考，不构成投资建议
