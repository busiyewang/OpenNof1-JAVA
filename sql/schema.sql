-- ============================================================
-- OpenNof1 加密货币交易系统 - MySQL 建表脚本
-- 数据库: crypto_trader
-- 字符集: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS `crypto_trader`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `crypto_trader`;

-- ------------------------------------------------------------
-- 1. K 线数据表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `klines` (
    `id`             BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`         VARCHAR(20)     NOT NULL COMMENT '交易对编码，如 BTCUSDT',
    `symbol_name`    VARCHAR(50)     DEFAULT NULL COMMENT '交易对显示名称',
    `kline_interval` VARCHAR(10)     NOT NULL COMMENT 'K 线周期: 1m, 5m, 1h, 1d 等',
    `timestamp`      DATETIME(3)     NOT NULL COMMENT 'K 线开盘时间 (UTC)',
    `open`           DECIMAL(20, 8)  DEFAULT NULL COMMENT '开盘价',
    `high`           DECIMAL(20, 8)  DEFAULT NULL COMMENT '最高价',
    `low`            DECIMAL(20, 8)  DEFAULT NULL COMMENT '最低价',
    `close_price`    DECIMAL(20, 8)  DEFAULT NULL COMMENT '收盘价',
    `volume`         DECIMAL(20, 8)  DEFAULT NULL COMMENT '成交额(USDT本位)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_symbol_interval_timestamp` (`symbol`, `kline_interval`, `timestamp`),
    INDEX `idx_symbol_timestamp` (`symbol`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='行情 K 线（蜡烛图）数据';

-- ------------------------------------------------------------
-- 2. 交易信号表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `signals` (
    `id`             BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`         VARCHAR(20)     NOT NULL COMMENT '交易对',
    `timestamp`      DATETIME(3)     NOT NULL COMMENT '信号生成时间 (UTC)',
    `action`         VARCHAR(10)     NOT NULL COMMENT '信号动作: BUY, SELL, HOLD',
    `price`          DOUBLE          DEFAULT NULL COMMENT '信号触发时价格',
    `confidence`     DOUBLE          DEFAULT NULL COMMENT '信号置信度 (0~1)',
    `strategy_name`  VARCHAR(100)    DEFAULT NULL COMMENT '产生信号的策略名称(VOTE:前缀表示加权投票)',
    `reason`         VARCHAR(500)    DEFAULT NULL COMMENT '信号触发原因说明',
    PRIMARY KEY (`id`),
    INDEX `idx_signal_symbol_timestamp` (`symbol`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='策略交易信号';

-- ------------------------------------------------------------
-- 3. 交易记录表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `trade_records` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`          VARCHAR(20)     NOT NULL COMMENT '交易对',
    `timestamp`       DATETIME(3)     NOT NULL COMMENT '成交时间 (UTC)',
    `order_id`        VARCHAR(64)     DEFAULT NULL COMMENT '交易所订单ID',
    `side`            VARCHAR(10)     NOT NULL COMMENT '方向: BUY, SELL',
    `order_type`      VARCHAR(20)     NOT NULL COMMENT '订单类型: MARKET, LIMIT',
    `price`           DECIMAL(20, 8)  DEFAULT NULL COMMENT '成交价格',
    `quantity`        DECIMAL(20, 8)  DEFAULT NULL COMMENT '成交基础货币数量',
    `quote_quantity`  DECIMAL(20, 8)  DEFAULT NULL COMMENT '成交报价货币金额 (USDT)',
    `fee`             DECIMAL(20, 8)  DEFAULT NULL COMMENT '手续费',
    `fee_asset`       VARCHAR(10)     DEFAULT NULL COMMENT '手续费币种',
    `status`          VARCHAR(20)     NOT NULL COMMENT '订单状态: FILLED, PARTIALLY_FILLED, CANCELLED',
    PRIMARY KEY (`id`),
    INDEX `idx_trade_symbol_timestamp` (`symbol`, `timestamp`),
    INDEX `idx_trade_timestamp` (`timestamp`),
    INDEX `idx_trade_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='交易成交记录';

-- ------------------------------------------------------------
-- 4. 链上指标表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `onchain_metrics` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`          VARCHAR(20)     NOT NULL COMMENT '资产/交易对',
    `metric_name`     VARCHAR(50)     NOT NULL COMMENT '指标名称，如 whale_transfer_volume, nupl, sopr, funding_rate 等',
    `timestamp`       DATETIME(3)     NOT NULL COMMENT '数据时间点 (UTC)',
    `metric_value`    DECIMAL(30, 8)  DEFAULT NULL COMMENT '指标数值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_onchain_symbol_metric_ts` (`symbol`, `metric_name`, `timestamp`),
    INDEX `idx_onchain_symbol_ts` (`symbol`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='链上数据指标（Glassnode）+ 市场情绪指标（Coinglass/FearGreed）';

-- ------------------------------------------------------------
-- 5. 技术指标值表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `indicator_values` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`          VARCHAR(20)     NOT NULL COMMENT '交易对',
    `indicator_name`  VARCHAR(50)     NOT NULL COMMENT '指标名称，如 MACD, RSI, BB_UPPER',
    `timestamp`       DATETIME(3)     NOT NULL COMMENT '数据时间点 (UTC)',
    `indicator_value` DECIMAL(30, 8)  DEFAULT NULL COMMENT '指标数值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_indicator_symbol_name_ts` (`symbol`, `indicator_name`, `timestamp`),
    INDEX `idx_indicator_symbol_ts` (`symbol`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='技术指标计算结果';

-- ------------------------------------------------------------
-- 6. 分析报告表（含交易计划）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `analysis_reports` (
    `id`                  BIGINT          NOT NULL AUTO_INCREMENT,
    `symbol`              VARCHAR(20)     NOT NULL COMMENT '交易对',
    `report_type`         VARCHAR(20)     NOT NULL COMMENT '报告类型: DAILY, WEEKLY, ON_DEMAND',
    `created_at`          DATETIME(3)     NOT NULL COMMENT '报告生成时间 (UTC)',
    `trend_direction`     VARCHAR(30)     NOT NULL COMMENT '趋势: STRONGLY_BULLISH/BULLISH/NEUTRAL/BEARISH/STRONGLY_BEARISH',
    `trend_confidence`    DOUBLE          DEFAULT NULL COMMENT '趋势置信度 (0~1)',
    `risk_assessment`     VARCHAR(20)     DEFAULT NULL COMMENT '风险等级: LOW, MODERATE, HIGH, EXTREME',
    `price_current`       DECIMAL(20, 8)  DEFAULT NULL COMMENT '当前价格',
    `price_support`       DECIMAL(20, 8)  DEFAULT NULL COMMENT '支撑位',
    `price_resistance`    DECIMAL(20, 8)  DEFAULT NULL COMMENT '阻力位',
    `short_term_outlook`  TEXT            DEFAULT NULL COMMENT '短期展望 (1-3天)',
    `medium_term_outlook` TEXT            DEFAULT NULL COMMENT '中期展望 (1-2周)',
    `timeframes_summary`  MEDIUMTEXT      DEFAULT NULL COMMENT '多时间框架分析摘要 (JSON)',
    `key_indicators`      MEDIUMTEXT      DEFAULT NULL COMMENT '关键技术指标解读 (JSON)',
    `onchain_summary`     MEDIUMTEXT      DEFAULT NULL COMMENT '链上数据分析摘要 (JSON)',
    `risk_factors`        TEXT            DEFAULT NULL COMMENT '风险因子列表 (JSON array)',
    `deepseek_analysis`   MEDIUMTEXT      DEFAULT NULL COMMENT 'DeepSeek 完整分析文本',
    -- 交易计划
    `trade_action`        VARCHAR(20)     DEFAULT NULL COMMENT '操作: BUY_LONG/SELL_SHORT/HOLD/CLOSE',
    `entry_price_range`   VARCHAR(100)    DEFAULT NULL COMMENT '入场区间 JSON: {"low":81500,"high":82000}',
    `stop_loss`           DECIMAL(20, 8)  DEFAULT NULL COMMENT '止损价格',
    `take_profit_1`       DECIMAL(20, 8)  DEFAULT NULL COMMENT '止盈目标1(保守)',
    `take_profit_2`       DECIMAL(20, 8)  DEFAULT NULL COMMENT '止盈目标2(激进)',
    `position_percent`    INT             DEFAULT NULL COMMENT '建议仓位(总资金%)',
    `trading_plan`        TEXT            DEFAULT NULL COMMENT '交易计划详情 (JSON)',
    PRIMARY KEY (`id`),
    INDEX `idx_report_symbol_created` (`symbol`, `created_at`),
    INDEX `idx_report_type_created` (`report_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='分析报告（含AI分析+交易计划）';

-- ------------------------------------------------------------
-- 7. 预测回溯评分表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_scores` (
    `id`                     BIGINT          NOT NULL AUTO_INCREMENT,
    `report_id`              BIGINT          NOT NULL COMMENT '关联的分析报告ID',
    `symbol`                 VARCHAR(20)     NOT NULL COMMENT '交易对',
    `scored_at`              DATETIME(3)     NOT NULL COMMENT '评分时间 (UTC)',
    `score_window_hours`     INT             NOT NULL COMMENT '评分窗口: 24 或 72 小时',
    -- 预测值
    `predicted_trend`        VARCHAR(30)     DEFAULT NULL COMMENT '预测趋势方向',
    `predicted_confidence`   DOUBLE          DEFAULT NULL COMMENT '预测置信度',
    `predicted_support`      DECIMAL(20, 8)  DEFAULT NULL COMMENT '预测支撑位',
    `predicted_resistance`   DECIMAL(20, 8)  DEFAULT NULL COMMENT '预测阻力位',
    `price_at_prediction`    DECIMAL(20, 8)  DEFAULT NULL COMMENT '预测时价格',
    -- 实际值
    `price_at_scoring`       DECIMAL(20, 8)  DEFAULT NULL COMMENT '评分时实际价格',
    `actual_high`            DECIMAL(20, 8)  DEFAULT NULL COMMENT '窗口内最高价',
    `actual_low`             DECIMAL(20, 8)  DEFAULT NULL COMMENT '窗口内最低价',
    `actual_change_percent`  DOUBLE          DEFAULT NULL COMMENT '实际涨跌幅(%)',
    -- 评分结果
    `trend_correct`          TINYINT(1)      DEFAULT NULL COMMENT '趋势方向是否正确',
    `support_valid`          TINYINT(1)      DEFAULT NULL COMMENT '支撑位是否有效',
    `resistance_valid`       TINYINT(1)      DEFAULT NULL COMMENT '阻力位是否有效',
    `total_score`            INT             DEFAULT NULL COMMENT '综合得分 0-100',
    `error_summary`          TEXT            DEFAULT NULL COMMENT '错误摘要(注入Prompt)',
    PRIMARY KEY (`id`),
    INDEX `idx_ps_symbol_scored` (`symbol`, `scored_at`),
    INDEX `idx_ps_report_id` (`report_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='预测回溯评分（24h/72h自动评分，用于Prompt进化）';
