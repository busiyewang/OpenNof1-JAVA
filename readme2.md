整体视角
这个项目启动后，会变成一个“后台交易机器人服务”：
启动 Spring Boot 应用，连上 Postgres。
周期性采集行情 K 线、链上鲸鱼指标，写入数据库。
周期性对关注的 symbol 跑多种策略（指标 + 链上 + 大模型），融合成一个最终信号。
把信号通过通知器发出去；如果是 live 模式，再调用执行器准备下单（当前实现还只是日志）。
下面按时间顺序说一遍。
1. 启动阶段
   入口：TraderApplication.main
   启动 Spring Boot，上下文加载所有 @Component/@Service/@Repository。
   @EnableScheduling 生效，注册所有 @Scheduled 定时任务。
   配置加载：
   读取 application.yml 或环境变量，例如：
   spring.datasource.*（Postgres）
   crypto.watch-list
   crypto.trading.mode（paper / live）
   crypto.mcp.*、crypto.exchange.binance.*、crypto.glassnode.* 等。
   Bean 初始化：
   WebClientConfig.webClientBuilder() 提供统一 WebClient.Builder。
   BinanceClient / GlassnodeClient / McpClient 在 @PostConstruct 里用 base-url 建好各自的 WebClient。
   各种 Repository、策略类、调度器、采集器、执行器等全部装配好。
2. 数据采集流程（Data → DB）
   2.1 K 线采集（每 60 秒）
   调度器：DataCollectionScheduler.collectKlines()
   @Scheduled(fixedDelay = 60000)
   遍历 crypto.watch-list 里的每个 symbol：
   调用 KlineCollector.collect(symbol, "1m")。
   采集器：KlineCollector.collect
   计算时间窗口：now-1h ~ now（最近 1 小时）。
   exchangeClient.getKlines(symbol, "1m", start, end) 调交易所客户端（目标是 BinanceClient）。
   当前 BinanceClient#getKlines 还返回空列表，需要你接上真实 API。
   若返回非空：
   调 KlineRepository.saveAll(klines) 写入 klines 表。
> 注意：当前每分钟抓 1 小时窗口，会和上一次大量重叠，需要你后续做去重/幂等设计。
2.2 链上鲸鱼指标采集（每小时）
调度器：DataCollectionScheduler.collectOnChainMetrics()
@Scheduled(cron = "0 0 */1 * * ?")（每小时整点）
遍历 watch-list：
调用 OnChainCollector.collectWhaleMetrics(symbol)。
采集器：OnChainCollector.collectWhaleMetrics
时间窗口：now-24h ~ now。
调 GlassnodeClient.getWhaleTransactionCount(symbol, from, to) 拉取数据（当前还返回空列表）。
非空时 OnChainMetricRepository.saveAll(metrics) 写入 onchain_metrics 表。
3. 策略执行与信号融合（DB → Signal）
   3.1 调度触发（每 60 秒）
   调度器：StrategyScheduler.runStrategies()
   @Scheduled(fixedDelay = 60000)
   对 watch-list 做 parallelStream()：
   每个 symbol 并行调用 strategyExecutor.execute(symbol)。
   也就是说，多个交易对的策略是并行执行的。
   3.2 读取数据并统一排序
   执行器：StrategyExecutor.execute(symbol)
   从库读取：
   klineRepository.findTop100BySymbolOrderByTimestampDesc(symbol)（最新在前）。
   onChainRepository.findTop100BySymbol(symbol, "whale_transaction_count")。
   如果没有 K 线，直接返回。
   把 klines 按 timestamp ASC 排序，保证：
   序列是从旧到新的正序。
   各策略用 klines.get(klines.size()-1) 取的是“最新价格”。
   3.3 逐个策略生成信号
   自动注入的策略列表：List<TradingStrategy> strategies，包括：
   MacdStrategy
   BollingerStrategy
   WhaleCombinedStrategy
   McpPredictionStrategy（若 crypto.mcp.enabled=true 才真正有意义）
   执行逻辑：
   signals = strategies.stream()  .map(s -> s.evaluate(symbol, klines, metrics))  .filter(s -> s.getAction() != HOLD)  .collect(toList());
   各策略里大致行为：
   MacdStrategy：用 MacdCalculator（ta4j）算 MACD、Signal、Histogram
   多头交叉 → BUY（置信度 0.7）
   空头交叉 → SELL
   否则 HOLD
   BollingerStrategy：用 BollingerBandsCalculator 算布林带
   价格 > 上轨 → SELL
   价格 < 下轨 → BUY
   否则 HOLD
   WhaleCombinedStrategy：
   WhaleAnalyzer.analyzeWhaleActivity(symbol) 看最近链上鲸鱼交易数走势（1/-1/0）
   再结合 MACD 方向，同向时给出 BUY/SELL，置信度 0.85。
   McpPredictionStrategy（大模型）
   若 crypto.mcp.enabled=false 或没有 K 线 → 直接 HOLD。
   构建 prompt：最近几根 K 线 + 少量 on-chain 指标，要求模型输出：
   ACTION: BUY/SELL/HOLD, CONFIDENCE: 0.xx, REASON: ...
   McpClient.predict(prompt) 通过 WebClient 调大模型 REST API。
   文本解析出动作和置信度，生成一个 Signal。
   3.4 融合出最终信号
   如果所有策略都是 HOLD（过滤后列表为空）：
   记录 debug 日志，结束。
   否则：
   从 signals 中取 confidence 最大 的一个作为 finalSignal：
   max(Comparator.comparingDouble(Signal::getConfidence))。
4. 通知与执行（Signal → 通知 / 下单）
   发送通知：
   notifier.notify(finalSignal)：
   具体实现可能是 TelegramNotifier / EmailNotifier（都实现了 Notifier 接口）。
   当前实现只是 log.info("Telegram notification: ...") / log.info("Email notification: ...")，真实发送需要你接 API。
   执行交易：
   如果 finalSignal.action != HOLD 且 crypto.trading.mode=live：
   调 orderExecutor.execute(finalSignal)：
   目前只打印 Executing order: ...，实际需要你：
   调 ExchangeClient.placeOrder(...)
   做 RiskManager / PositionManager 风控检查
   把结果写入 TradeRecord。
   否则（paper 模式或 HOLD）：
   打 [Paper] Would execute: ... 日志，用于观察策略行为。
5. MCP 调用在整个流程中的位置
   MCP 只在 某一个策略 中被用到：McpPredictionStrategy.evaluate(...)。
   流程是：
   策略执行器统一拉数据、排好序。
   调各策略的 evaluate，其中 McpPredictionStrategy 会：
   构造 prompt。
   通过 McpClient 用 WebClient.post() 调 REST 接口（例如 OpenAI）。
   阻塞拿到结果 .block()（因为这是在定时任务的后台线程里，并非 Web 请求链路）。
   所有策略的信号一起参与“最大置信度”竞争。