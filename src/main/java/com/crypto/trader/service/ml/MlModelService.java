package com.crypto.trader.service.ml;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XGBoost 模型管理服务 — 负责训练、预测、持久化。
 *
 * <p>训练数据来源：数据库中的历史 K 线 + 链上数据。
 * 标签生成：根据下一根 K 线的涨跌幅分为 3 类（跌/横盘/涨）。</p>
 *
 * <p>模型按 symbol+interval 维度管理，文件保存到 {@code crypto.ml.model-dir} 目录。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MlModelService {

    private final FeatureEngineerService featureEngineer;
    private final KlineRepository klineRepository;
    private final OnChainMetricRepository onChainRepository;

    /** 内存中缓存已加载的 Booster，key = "symbol:interval" */
    private final ConcurrentHashMap<String, Booster> modelCache = new ConcurrentHashMap<>();

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

    // ======================== 训练 ========================

    /**
     * 使用历史数据训练 XGBoost 模型。
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

        // 2. 加载链上数据
        List<OnChainMetric> allOnChain = new ArrayList<>();
        for (String metric : ONCHAIN_METRICS) {
            allOnChain.addAll(onChainRepository.findTop100BySymbol(symbol, metric));
        }
        Map<String, BigDecimal> onChainMap = featureEngineer.buildOnChainMap(allOnChain);

        // 3. 滑动窗口生成样本
        List<float[]> featureList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();

        int windowSize = FeatureEngineerService.MIN_KLINES;
        for (int i = windowSize; i < allKlines.size() - 1; i++) {
            List<Kline> window = allKlines.subList(i - windowSize, i + 1);
            float[] features = featureEngineer.extractFeatures(window, onChainMap);
            if (features == null) continue;

            double currentClose = allKlines.get(i).getClose().doubleValue();
            int label = featureEngineer.generateLabel(allKlines.get(i + 1), currentClose, labelThreshold);

            featureList.add(features);
            labelList.add(label);
        }

        if (featureList.isEmpty()) {
            String msg = "[ML训练] " + symbol + " " + interval + " 无有效样本";
            log.warn(msg);
            return msg;
        }

        log.info("[ML训练] {} {} 生成 {} 个训练样本", symbol, interval, featureList.size());

        // 4. 构建 DMatrix 并训练
        try {
            int numSamples = featureList.size();
            int numFeatures = FeatureEngineerService.FEATURE_COUNT;

            // 展平为一维数组
            float[] flatData = new float[numSamples * numFeatures];
            float[] labels = new float[numSamples];
            for (int i = 0; i < numSamples; i++) {
                System.arraycopy(featureList.get(i), 0, flatData, i * numFeatures, numFeatures);
                labels[i] = labelList.get(i);
            }

            // 分割训练集/验证集 (80/20)
            int trainSize = (int) (numSamples * 0.8);
            float[] trainData = Arrays.copyOf(flatData, trainSize * numFeatures);
            float[] trainLabels = Arrays.copyOf(labels, trainSize);
            float[] valData = Arrays.copyOfRange(flatData, trainSize * numFeatures, numSamples * numFeatures);
            float[] valLabels = Arrays.copyOfRange(labels, trainSize, numSamples);

            DMatrix trainMatrix = new DMatrix(trainData, trainSize, numFeatures);
            trainMatrix.setLabel(trainLabels);

            DMatrix valMatrix = new DMatrix(valData, numSamples - trainSize, numFeatures);
            valMatrix.setLabel(valLabels);

            // XGBoost 参数
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("objective", "multi:softprob");
            params.put("num_class", 3);
            params.put("max_depth", 6);
            params.put("eta", 0.1);
            params.put("subsample", 0.8);
            params.put("colsample_bytree", 0.8);
            params.put("min_child_weight", 3);
            params.put("eval_metric", "mlogloss");
            params.put("nthread", 4);

            Map<String, DMatrix> watches = new LinkedHashMap<>();
            watches.put("train", trainMatrix);
            watches.put("val", valMatrix);

            int numRounds = 200;
            Booster booster = XGBoost.train(trainMatrix, params, numRounds, watches, null, null);

            // 5. 保存模型
            String modelKey = modelKey(symbol, interval);
            Path modelPath = Path.of(modelDir, modelKey + ".xgb");
            Files.createDirectories(modelPath.getParent());
            booster.saveModel(modelPath.toString());

            modelCache.put(modelKey, booster);

            // 6. 计算验证集准确率
            float[][] valPreds = booster.predict(valMatrix);
            int correct = 0;
            for (int i = 0; i < valPreds.length; i++) {
                int predClass = argmax(valPreds[i]);
                if (predClass == (int) valLabels[i]) correct++;
            }
            double accuracy = (double) correct / valPreds.length * 100;

            long elapsed = System.currentTimeMillis() - t0;

            // 统计类别分布
            int[] classCounts = new int[3];
            for (int l : labelList) classCounts[l]++;

            String report = String.format(
                    "[ML训练] %s %s 完成! 样本=%d (跌:%d/横盘:%d/涨:%d), 验证准确率=%.1f%%, 耗时=%dms",
                    symbol, interval, numSamples,
                    classCounts[0], classCounts[1], classCounts[2],
                    accuracy, elapsed);
            log.info(report);
            return report;

        } catch (XGBoostError e) {
            String msg = "[ML训练] XGBoost 训练失败: " + e.getMessage();
            log.error(msg, e);
            return msg;
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
     * @param symbol   交易对
     * @param interval K 线周期
     * @param klines   最新 K 线（按时间正序，至少 30 根）
     * @param onChainData 链上指标最新值
     * @return 预测结果，或 null（模型未就绪时）
     */
    public MlPrediction predict(String symbol, String interval,
                                 List<Kline> klines, Map<String, BigDecimal> onChainData) {
        Booster booster = getModel(symbol, interval);
        if (booster == null) {
            log.debug("[ML预测] {} {} 模型未加载，跳过预测", symbol, interval);
            return null;
        }

        float[] features = featureEngineer.extractFeatures(klines, onChainData);
        if (features == null) {
            log.warn("[ML预测] {} {} 特征提取失败", symbol, interval);
            return null;
        }

        try {
            DMatrix dm = new DMatrix(features, 1, FeatureEngineerService.FEATURE_COUNT);
            float[][] preds = booster.predict(dm);
            if (preds.length == 0) return null;

            MlPrediction prediction = MlPrediction.fromProbabilities(preds[0]);
            log.info("[ML预测] {} {} → {} (置信度={}%, P[跌]={}%, P[横盘]={}%, P[涨]={}%)",
                    symbol, interval, prediction.getDirection(),
                    String.format("%.1f", prediction.getConfidence() * 100),
                    String.format("%.1f", preds[0][0] * 100),
                    String.format("%.1f", preds[0][1] * 100),
                    String.format("%.1f", preds[0][2] * 100));
            return prediction;

        } catch (XGBoostError e) {
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

    private Booster getModel(String symbol, String interval) {
        String key = modelKey(symbol, interval);
        Booster booster = modelCache.get(key);
        if (booster != null) return booster;

        // 尝试从文件加载
        Path modelPath = Path.of(modelDir, key + ".xgb");
        if (Files.exists(modelPath)) {
            try {
                booster = XGBoost.loadModel(modelPath.toString());
                modelCache.put(key, booster);
                log.info("[ML] 从文件加载模型: {}", modelPath);
                return booster;
            } catch (XGBoostError e) {
                log.error("[ML] 模型加载失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private String modelKey(String symbol, String interval) {
        return symbol.toLowerCase() + "_" + interval;
    }

    private int argmax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) best = i;
        }
        return best;
    }
}
