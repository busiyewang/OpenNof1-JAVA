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
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.type.DataTypes;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.vector.IntVector;

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
 * ML 模型管理服务 — Smile GradientTreeBoost + 多时间框架 + Walk-forward + Z-score归一化。
 *
 * <p>P0 改进：</p>
 * <ul>
 *   <li>多时间框架：训练时加载 1h+4h+1d 三个时间框架的K线，81维特征</li>
 *   <li>Walk-forward：时间序列滚动分割验证，消除数据泄漏</li>
 *   <li>Z-score归一化：训练时拟合 FeatureScaler，预测时用相同参数</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MlModelService {

    private final FeatureEngineerService featureEngineer;
    private final KlineRepository klineRepository;
    private final OnChainMetricRepository onChainRepository;

    private final ConcurrentHashMap<String, GradientTreeBoost> modelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StructType> schemaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FeatureScaler> scalerCache = new ConcurrentHashMap<>();

    @Value("${crypto.ml.model-dir:./ml-models}")
    private String modelDir;

    @Value("${crypto.ml.label-threshold:0.3}")
    private double labelThreshold;

    @Value("${crypto.ml.train-months:6}")
    private int trainMonths;

    private static final List<String> ONCHAIN_METRICS = List.of(
            "whale_transfer_volume", "nupl", "sopr",
            "exchange_net_flow", "exchange_inflow", "exchange_outflow"
    );

    private static final int NUM_CLASSES = 3;
    private static final Formula FORMULA = Formula.lhs("label");

    /** 多时间框架配置 */
    private static final List<String> TIMEFRAMES = List.of("1h", "4h", "1d");

    // ======================== 训练 ========================

    public String train(String symbol, String interval) {
        log.info("[ML训练] 开始训练 {} 模型（多时间框架+Walk-forward+Z-score），回溯 {} 个月",
                symbol, trainMonths);
        long t0 = System.currentTimeMillis();

        // 1. 加载多时间框架 K 线
        Instant startTime = Instant.now().minus(trainMonths * 30L, ChronoUnit.DAYS);
        Map<String, List<Kline>> allKlinesByTf = new LinkedHashMap<>();
        for (String tf : TIMEFRAMES) {
            List<Kline> klines = klineRepository.findBySymbolAndIntervalAndTimestampBetween(
                    symbol, tf, startTime, Instant.now());
            klines.sort(Comparator.comparing(Kline::getTimestamp));
            allKlinesByTf.put(tf, klines);
            log.info("[ML训练] {} {} 加载 {} 根K线", symbol, tf, klines.size());
        }

        List<Kline> primaryKlines = allKlinesByTf.get(interval);
        if (primaryKlines == null || primaryKlines.size() < FeatureEngineerService.MIN_KLINES + 10) {
            String msg = String.format("[ML训练] %s %s 主时间框架数据不足: %d",
                    symbol, interval, primaryKlines == null ? 0 : primaryKlines.size());
            log.warn(msg);
            return msg;
        }

        // 2. 滑动窗口生成样本（多时间框架 + 链上数据时间对齐）
        List<double[]> featureList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();
        int labelLookAhead = 5;
        int windowSize = FeatureEngineerService.MIN_KLINES;

        Map<String, BigDecimal> onChainMap = new HashMap<>();
        Instant lastOnChainRefresh = Instant.MIN;

        for (int i = windowSize; i < primaryKlines.size() - labelLookAhead; i++) {
            Instant barTime = primaryKlines.get(i).getTimestamp();

            // 链上数据按时间对齐
            if (Duration.between(lastOnChainRefresh, barTime).toHours() >= 24) {
                onChainMap.clear();
                for (String metric : ONCHAIN_METRICS) {
                    onChainRepository.findLatestBefore(symbol, metric, barTime)
                            .ifPresent(m -> onChainMap.put(m.getMetricName(), m.getValue()));
                }
                lastOnChainRefresh = barTime;
            }

            // 为每个时间框架提取对应窗口的K线
            Map<String, List<Kline>> windowByTf = buildWindowByTf(allKlinesByTf, barTime, windowSize);

            float[] features = featureEngineer.extractMultiTfFeatures(windowByTf, onChainMap);
            if (features == null) continue;

            double currentClose = primaryKlines.get(i).getClose().doubleValue();
            List<Kline> futureKlines = primaryKlines.subList(i + 1,
                    Math.min(i + 1 + labelLookAhead, primaryKlines.size()));
            int label = featureEngineer.generateLabel(futureKlines, currentClose, labelThreshold);

            featureList.add(toDouble(features));
            labelList.add(label);
        }

        if (featureList.size() < 50) {
            String msg = "[ML训练] " + symbol + " 有效样本不足: " + featureList.size();
            log.warn(msg);
            return msg;
        }

        log.info("[ML训练] {} 生成 {} 个样本 ({}维特征)", symbol, featureList.size(),
                FeatureEngineerService.FEATURE_COUNT);

        // 3. Walk-forward 时间序列验证 + Z-score归一化 + 训练
        try {
            return trainWithWalkForward(symbol, interval, featureList, labelList, t0);
        } catch (Exception e) {
            String msg = "[ML训练] 训练异常: " + e.getMessage();
            log.error(msg, e);
            return msg;
        }
    }

    /**
     * Walk-forward 训练：滚动窗口验证，最后用全量数据训练最终模型。
     *
     * <pre>
     * |-------- train1 --------|-- val1 --|
     * |------------ train2 ----------|-- val2 --|
     * |---------------- train3 --------------|-- val3 --|
     * |==================== 最终训练集 ====================|
     * </pre>
     */
    private String trainWithWalkForward(String symbol, String interval,
                                         List<double[]> featureList, List<Integer> labelList,
                                         long t0) throws Exception {
        int numSamples = featureList.size();
        double[][] allX = featureList.toArray(new double[0][]);
        int[] allY = labelList.stream().mapToInt(Integer::intValue).toArray();

        // Walk-forward: 5折时间序列验证
        int numFolds = 5;
        int foldSize = numSamples / (numFolds + 1);
        int minTrainSize = numSamples / 3; // 最少1/3数据用于训练

        double totalAccuracy = 0;
        int validFolds = 0;

        for (int fold = 0; fold < numFolds; fold++) {
            int trainEnd = minTrainSize + fold * foldSize;
            int valEnd = Math.min(trainEnd + foldSize, numSamples);
            if (trainEnd >= numSamples || valEnd > numSamples) break;

            double[][] trainX = Arrays.copyOf(allX, trainEnd);
            int[] trainY = Arrays.copyOf(allY, trainEnd);
            double[][] valX = Arrays.copyOfRange(allX, trainEnd, valEnd);
            int[] valY = Arrays.copyOfRange(allY, trainEnd, valEnd);

            if (valX.length == 0) continue;

            // 归一化（每个fold独立拟合，防止信息泄漏）
            FeatureScaler foldScaler = new FeatureScaler();
            foldScaler.fit(trainX);
            foldScaler.transformInPlace(trainX);
            foldScaler.transformInPlace(valX);

            // 训练
            String[] featureNames = FeatureEngineerService.FEATURE_NAMES.toArray(new String[0]);
            DataFrame trainDf = DataFrame.of(trainX, featureNames).merge(IntVector.of("label", trainY));

            Properties props = buildTrainProps();
            GradientTreeBoost foldModel = GradientTreeBoost.fit(FORMULA, trainDf, props);

            // 验证
            StructType schema = buildFeatureSchema(featureNames);
            int correct = 0;
            for (int i = 0; i < valX.length; i++) {
                Tuple tuple = Tuple.of(valX[i], schema);
                if (foldModel.predict(tuple) == valY[i]) correct++;
            }
            double foldAcc = (double) correct / valX.length * 100;
            totalAccuracy += foldAcc;
            validFolds++;

            log.info("[ML训练] Walk-forward fold {}/{}: train={} val={} 准确率={}%",
                    fold + 1, numFolds, trainX.length, valX.length,
                    String.format("%.1f", foldAcc));
        }

        double avgAccuracy = validFolds > 0 ? totalAccuracy / validFolds : 0;
        log.info("[ML训练] Walk-forward 平均准确率: {}% ({} folds)",
                String.format("%.1f", avgAccuracy), validFolds);

        // 4. 用全量数据训练最终模型 + 拟合最终 Scaler
        FeatureScaler finalScaler = new FeatureScaler();
        finalScaler.fit(allX);
        double[][] scaledX = Arrays.copyOf(allX, allX.length);
        for (int i = 0; i < scaledX.length; i++) {
            scaledX[i] = Arrays.copyOf(allX[i], allX[i].length);
        }
        finalScaler.transformInPlace(scaledX);

        String[] featureNames = FeatureEngineerService.FEATURE_NAMES.toArray(new String[0]);
        DataFrame finalDf = DataFrame.of(scaledX, featureNames).merge(IntVector.of("label", allY));

        Properties props = buildTrainProps();
        GradientTreeBoost finalModel = GradientTreeBoost.fit(FORMULA, finalDf, props);

        // 5. 保存模型 + scaler + schema
        String modelKey = modelKey(symbol, "1h"); // 统一用1h作为key
        Path modelPath = Path.of(modelDir, modelKey + ".model");
        Path scalerPath = Path.of(modelDir, modelKey + ".scaler");
        Path schemaPath = Path.of(modelDir, modelKey + ".schema");
        Files.createDirectories(modelPath.getParent());

        StructType featureSchema = buildFeatureSchema(featureNames);

        saveObject(modelPath, finalModel);
        saveObject(scalerPath, finalScaler);
        saveObject(schemaPath, featureSchema);

        modelCache.put(modelKey, finalModel);
        scalerCache.put(modelKey, finalScaler);
        schemaCache.put(modelKey, featureSchema);

        long elapsed = System.currentTimeMillis() - t0;
        int[] classCounts = new int[NUM_CLASSES];
        for (int l : allY) classCounts[l]++;

        String report = String.format(
                "[ML训练] %s 完成! 样本=%d(%d维) 跌:%d/横盘:%d/涨:%d | WF准确率=%.1f%% | 耗时=%dms",
                symbol, numSamples, FeatureEngineerService.FEATURE_COUNT,
                classCounts[0], classCounts[1], classCounts[2],
                avgAccuracy, elapsed);
        log.info(report);
        return report;
    }

    // ======================== 预测 ========================

    /**
     * 多时间框架预测。
     */
    public MlPrediction predict(String symbol, String interval,
                                 List<Kline> klines, Map<String, BigDecimal> onChainData) {
        return predictMultiTf(symbol, Map.of("1h", klines), onChainData);
    }

    /**
     * 多时间框架预测（完整版）。
     */
    public MlPrediction predictMultiTf(String symbol, Map<String, List<Kline>> klinesByTf,
                                        Map<String, BigDecimal> onChainData) {
        String key = modelKey(symbol, "1h");
        GradientTreeBoost model = getModel(symbol);
        if (model == null) return null;

        StructType schema = schemaCache.get(key);
        FeatureScaler scaler = scalerCache.get(key);
        if (schema == null || scaler == null || !scaler.isFitted()) {
            log.warn("[ML预测] {} schema或scaler未加载", symbol);
            return null;
        }

        float[] features = featureEngineer.extractMultiTfFeatures(klinesByTf, onChainData);
        if (features == null) {
            log.warn("[ML预测] {} 特征提取失败", symbol);
            return null;
        }

        try {
            // 用训练时的 scaler 归一化
            float[] scaled = scaler.transform(features);
            Tuple tuple = Tuple.of(toDouble(scaled), schema);
            double[] posteriori = new double[NUM_CLASSES];
            model.predict(tuple, posteriori);

            float[] probs = new float[NUM_CLASSES];
            for (int i = 0; i < NUM_CLASSES; i++) {
                probs[i] = (float) posteriori[i];
            }

            MlPrediction prediction = MlPrediction.fromProbabilities(probs);
            log.info("[ML预测] {} → {} (置信度={}%, P[跌]={}%, P[横盘]={}%, P[涨]={}%)",
                    symbol, prediction.getDirection(),
                    String.format("%.1f", prediction.getConfidence() * 100),
                    String.format("%.1f", probs[0] * 100),
                    String.format("%.1f", probs[1] * 100),
                    String.format("%.1f", probs[2] * 100));
            return prediction;

        } catch (Exception e) {
            log.error("[ML预测] {} 预测失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    public boolean isModelReady(String symbol, String interval) {
        String key = modelKey(symbol, "1h");
        return getModel(symbol) != null
                && schemaCache.containsKey(key)
                && scalerCache.containsKey(key);
    }

    // ======================== 内部方法 ========================

    /**
     * 为每个时间框架构建对应时间窗口的K线。
     * 对于4h/1d，找 barTime 之前最近的 windowSize 根K线。
     */
    private Map<String, List<Kline>> buildWindowByTf(Map<String, List<Kline>> allKlinesByTf,
                                                      Instant barTime, int windowSize) {
        Map<String, List<Kline>> result = new HashMap<>();
        for (Map.Entry<String, List<Kline>> entry : allKlinesByTf.entrySet()) {
            String tf = entry.getKey();
            List<Kline> allTfKlines = entry.getValue();

            // 找 barTime 之前的最近 windowSize 根
            int endIdx = -1;
            for (int j = allTfKlines.size() - 1; j >= 0; j--) {
                if (!allTfKlines.get(j).getTimestamp().isAfter(barTime)) {
                    endIdx = j;
                    break;
                }
            }

            if (endIdx >= 0) {
                int startIdx = Math.max(0, endIdx - windowSize + 1);
                result.put(tf, allTfKlines.subList(startIdx, endIdx + 1));
            }
        }
        return result;
    }

    private GradientTreeBoost getModel(String symbol) {
        String key = modelKey(symbol, "1h");
        GradientTreeBoost model = modelCache.get(key);
        if (model != null) return model;

        Path modelPath = Path.of(modelDir, key + ".model");
        Path schemaPath = Path.of(modelDir, key + ".schema");
        Path scalerPath = Path.of(modelDir, key + ".scaler");

        if (Files.exists(modelPath) && Files.exists(schemaPath) && Files.exists(scalerPath)) {
            try {
                model = (GradientTreeBoost) loadObject(modelPath);
                schemaCache.put(key, (StructType) loadObject(schemaPath));
                scalerCache.put(key, (FeatureScaler) loadObject(scalerPath));
                modelCache.put(key, model);
                log.info("[ML] 从文件加载模型+scaler: {}", modelPath);
                return model;
            } catch (Exception e) {
                log.error("[ML] 模型加载失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private Properties buildTrainProps() {
        Properties props = new Properties();
        props.setProperty("smile.gbt.trees", "200");
        props.setProperty("smile.gbt.max_depth", "6");
        props.setProperty("smile.gbt.max_nodes", "20");
        props.setProperty("smile.gbt.node_size", "3");
        props.setProperty("smile.gbt.shrinkage", "0.1");
        props.setProperty("smile.gbt.sample_rate", "0.8");
        return props;
    }

    private StructType buildFeatureSchema(String[] featureNames) {
        StructField[] fields = new StructField[featureNames.length];
        for (int i = 0; i < featureNames.length; i++) {
            fields[i] = new StructField(featureNames[i], DataTypes.DoubleType);
        }
        return new StructType(fields);
    }

    private void saveObject(Path path, Serializable obj) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(path.toFile())))) {
            oos.writeObject(obj);
        }
    }

    private Object loadObject(Path path) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(path.toFile())))) {
            return ois.readObject();
        }
    }

    private String modelKey(String symbol, String interval) {
        return symbol.toLowerCase() + "_mtf"; // 多时间框架统一key
    }

    private double[] toDouble(float[] arr) {
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i];
        return result;
    }
}
