package com.crypto.trader.service.ml;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import smile.classification.GradientTreeBoost;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ML 模型管理服务 — 基于 Smile GradientTreeBoost（纯 Java，无 native 依赖）。
 *
 * <p>训练数据来源：数据库中的历史 K 线 + 链上数据。
 * 标签生成：根据下一根 K 线的涨跌幅分为 3 类（跌/横盘/涨）。</p>
 *
 * <p>模型按 symbol+interval 维度管理，序列化到 {@code crypto.ml.model-dir} 目录。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MlModelService {

    private final FeatureEngineerService featureEngineer;
    private final KlineRepository klineRepository;
    private final OnChainMetricRepository onChainRepository;

    /** 内存中缓存已加载的模型，key = "symbol:interval" */
    private final ConcurrentHashMap<String, GradientTreeBoost> modelCache = new ConcurrentHashMap<>();

    @Value("${crypto.ml.model-dir:./ml-models}")
    private String modelDir;

    @Value("${crypto.ml.label-threshold:0.3}")
    private double labelThreshold;

    @Value("${crypto.ml.train-months:6}")
    private int trainMonths;

    /** 链上指标名列表 */
    private static final List<String> ONCHAIN_METRICS = List.of(
            "whale_transfer_volume", "nupl", "sopr",
            "exchange_net_flow", "exchange_inflow", "exchange_outflow"
    );

    /** 类别数 */
    private static final int NUM_CLASSES = 3;

    // ======================== 训练 ========================

    /**
     * 使用历史数据训练 GradientTreeBoost 模型。
     *
     * @param symbol   交易对
     * @param interval K 线周期（如 1h）
     * @return 训练报告信息
     */
    public String train(String symbol, String interval) {
        log.info("[ML训练] 开始训练 {} {} 模型，回溯 {} 个月", symbol, interval, trainMonths);
        long t0 = System.currentTimeMillis();

        // 1. 加载历史 K 线
        Instant startTime = Instant.now().minus(trainMonths * 30L, ChronoUnit.DAYS);
        List<Kline> allKlines = klineRepository.findBySymbolAndIntervalAndTimestampBetween(
                symbol, interval, startTime, Instant.now());
        allKlines.sort(Comparator.comparing(Kline::getTimestamp));

        if (allKlines.size() < FeatureEngineerService.MIN_KLINES + 10) {
            String msg = String.format("[ML训练] %s %s 数据不足: %d 根K线，至少需要 %d",
                    symbol, interval, allKlines.size(), FeatureEngineerService.MIN_KLINES + 10);
            log.warn(msg);
            return msg;
        }

        // 2-3. 滑动窗口生成样本（链上数据按时间对齐，标签看未来5根K线加权收益）
        List<double[]> featureList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();
        int labelLookAhead = 5;

        int windowSize = FeatureEngineerService.MIN_KLINES;
        // 链上数据缓存：每10根K线刷新一次（链上数据是日级别，不需要每根都查）
        Map<String, BigDecimal> onChainMap = new HashMap<>();
        Instant lastOnChainRefresh = Instant.MIN;

        for (int i = windowSize; i < allKlines.size() - labelLookAhead; i++) {
            Instant barTime = allKlines.get(i).getTimestamp();

            // 每隔10根K线或跨天时刷新链上数据快照
            if (Duration.between(lastOnChainRefresh, barTime).toHours() >= 24) {
                onChainMap.clear();
                for (String metric : ONCHAIN_METRICS) {
                    onChainRepository.findLatestBefore(symbol, metric, barTime)
                            .ifPresent(m -> onChainMap.put(m.getMetricName(), m.getValue()));
                }
                lastOnChainRefresh = barTime;
            }

            List<Kline> window = allKlines.subList(i - windowSize, i + 1);
            float[] features = featureEngineer.extractFeatures(window, onChainMap);
            if (features == null) continue;

            double currentClose = allKlines.get(i).getClose().doubleValue();
            List<Kline> futureKlines = allKlines.subList(i + 1,
                    Math.min(i + 1 + labelLookAhead, allKlines.size()));
            int label = featureEngineer.generateLabel(futureKlines, currentClose, labelThreshold);

            featureList.add(toDouble(features));
            labelList.add(label);
        }

        if (featureList.isEmpty()) {
            String msg = "[ML训练] " + symbol + " " + interval + " 无有效样本";
            log.warn(msg);
            return msg;
        }

        log.info("[ML训练] {} {} 生成 {} 个训练样本", symbol, interval, featureList.size());

        // 4. 转换为 Smile 要求的数据格式并训练
        try {
            int numSamples = featureList.size();

            double[][] x = featureList.toArray(new double[0][]);
            int[] y = labelList.stream().mapToInt(Integer::intValue).toArray();

            // 分割训练集/验证集 (80/20)
            int trainSize = (int) (numSamples * 0.8);
            double[][] trainX = Arrays.copyOf(x, trainSize);
            int[] trainY = Arrays.copyOf(y, trainSize);
            double[][] valX = Arrays.copyOfRange(x, trainSize, numSamples);
            int[] valY = Arrays.copyOfRange(y, trainSize, numSamples);

            // Smile GradientTreeBoost 参数:
            // ntrees=200, maxDepth=6, maxNodes=20, nodeSize=3, shrinkage=0.1, subsample=0.8
            GradientTreeBoost model = GradientTreeBoost.fit(trainX, trainY,
                    200,    // ntrees
                    6,      // maxDepth
                    20,     // maxNodes
                    3,      // nodeSize (min samples per leaf)
                    0.1,    // shrinkage (learning rate)
                    0.8     // subsample
            );

            // 5. 保存模型（Java 序列化）
            String modelKey = modelKey(symbol, interval);
            Path modelPath = Path.of(modelDir, modelKey + ".model");
            Files.createDirectories(modelPath.getParent());
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(modelPath.toFile())))) {
                oos.writeObject(model);
            }

            modelCache.put(modelKey, model);

            // 6. 计算验证集准确率
            int correct = 0;
            for (int i = 0; i < valX.length; i++) {
                int predicted = model.predict(valX[i]);
                if (predicted == valY[i]) correct++;
            }
            double accuracy = valX.length > 0 ? (double) correct / valX.length * 100 : 0;

            long elapsed = System.currentTimeMillis() - t0;

            // 统计类别分布
            int[] classCounts = new int[NUM_CLASSES];
            for (int l : labelList) classCounts[l]++;

            String report = String.format(
                    "[ML训练] %s %s 完成! 样本=%d (跌:%d/横盘:%d/涨:%d), 验证准确率=%.1f%%, 耗时=%dms",
                    symbol, interval, numSamples,
                    classCounts[0], classCounts[1], classCounts[2],
                    accuracy, elapsed);
            log.info(report);
            return report;

        } catch (Exception e) {
            String msg = "[ML训练] 训练异常: " + e.getMessage();
            log.error(msg, e);
            return msg;
        }
    }

    // ======================== 预测 ========================

    /**
     * 使用训练好的模型进行预测。
     *
     * @param symbol      交易对
     * @param interval    K 线周期
     * @param klines      最新 K 线（按时间正序，至少 30 根）
     * @param onChainData 链上指标最新值
     * @return 预测结果，或 null（模型未就绪时）
     */
    public MlPrediction predict(String symbol, String interval,
                                 List<Kline> klines, Map<String, BigDecimal> onChainData) {
        GradientTreeBoost model = getModel(symbol, interval);
        if (model == null) {
            log.debug("[ML预测] {} {} 模型未加载，跳过预测", symbol, interval);
            return null;
        }

        float[] features = featureEngineer.extractFeatures(klines, onChainData);
        if (features == null) {
            log.warn("[ML预测] {} {} 特征提取失败", symbol, interval);
            return null;
        }

        try {
            double[] sample = toDouble(features);
            double[] posteriori = new double[NUM_CLASSES];
            int predicted = model.predict(sample, posteriori);

            // 转为 float[] 兼容 MlPrediction
            float[] probs = new float[NUM_CLASSES];
            for (int i = 0; i < NUM_CLASSES; i++) {
                probs[i] = (float) posteriori[i];
            }

            MlPrediction prediction = MlPrediction.fromProbabilities(probs);
            log.info("[ML预测] {} {} → {} (置信度={}%, P[跌]={}%, P[横盘]={}%, P[涨]={}%)",
                    symbol, interval, prediction.getDirection(),
                    String.format("%.1f", prediction.getConfidence() * 100),
                    String.format("%.1f", probs[0] * 100),
                    String.format("%.1f", probs[1] * 100),
                    String.format("%.1f", probs[2] * 100));
            return prediction;

        } catch (Exception e) {
            log.error("[ML预测] {} {} 预测失败: {}", symbol, interval, e.getMessage());
            return null;
        }
    }

    /**
     * 检查模型是否可用。
     */
    public boolean isModelReady(String symbol, String interval) {
        return getModel(symbol, interval) != null;
    }

    // ======================== 内部方法 ========================

    private GradientTreeBoost getModel(String symbol, String interval) {
        String key = modelKey(symbol, interval);
        GradientTreeBoost model = modelCache.get(key);
        if (model != null) return model;

        // 尝试从文件加载
        Path modelPath = Path.of(modelDir, key + ".model");
        if (Files.exists(modelPath)) {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(new FileInputStream(modelPath.toFile())))) {
                model = (GradientTreeBoost) ois.readObject();
                modelCache.put(key, model);
                log.info("[ML] 从文件加载模型: {}", modelPath);
                return model;
            } catch (Exception e) {
                log.error("[ML] 模型加载失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private String modelKey(String symbol, String interval) {
        return symbol.toLowerCase() + "_" + interval;
    }

    /** float[] → double[] 转换（Smile 使用 double）*/
    private double[] toDouble(float[] arr) {
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = arr[i];
        }
        return result;
    }
}
