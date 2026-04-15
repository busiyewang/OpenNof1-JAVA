package com.crypto.trader.controller;

import com.crypto.trader.model.Kline;
import com.crypto.trader.model.OnChainMetric;
import com.crypto.trader.repository.KlineRepository;
import com.crypto.trader.repository.OnChainMetricRepository;
import com.crypto.trader.service.ml.FeatureEngineerService;
import com.crypto.trader.service.ml.MlModelService;
import com.crypto.trader.service.ml.MlPrediction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ML 模型管理 REST 接口。
 */
@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
public class MlController {

    private final MlModelService mlModelService;
    private final FeatureEngineerService featureEngineer;
    private final KlineRepository klineRepository;
    private final OnChainMetricRepository onChainRepository;

    /**
     * 手动触发模型训练。
     * POST /api/ml/train/{symbol}?interval=1h
     */
    @PostMapping("/train/{symbol}")
    public ResponseEntity<Map<String, String>> train(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval) {
        String report = mlModelService.train(symbol, interval);
        return ResponseEntity.ok(Map.of("result", report));
    }

    /**
     * 使用最新数据进行实时预测。
     * GET /api/ml/predict/{symbol}?interval=1h
     */
    @GetMapping("/predict/{symbol}")
    public ResponseEntity<?> predict(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval) {

        if (!mlModelService.isModelReady(symbol, interval)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "模型未就绪，请先调用 POST /api/ml/train/" + symbol));
        }

        // 加载最新 K 线
        List<Kline> klines = klineRepository.findLatestKlines(symbol, interval, 100);
        if (klines.size() < FeatureEngineerService.MIN_KLINES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "K线数据不足: " + klines.size()));
        }
        klines.sort(Comparator.comparing(Kline::getTimestamp));

        // 加载链上数据
        List<OnChainMetric> allMetrics = new ArrayList<>();
        for (String m : List.of("whale_transfer_volume", "nupl", "sopr",
                "exchange_net_flow", "exchange_inflow", "exchange_outflow")) {
            allMetrics.addAll(onChainRepository.findTop100BySymbol(symbol, m));
        }
        Map<String, BigDecimal> onChainMap = featureEngineer.buildOnChainMap(allMetrics);

        MlPrediction prediction = mlModelService.predict(symbol, interval, klines, onChainMap);
        if (prediction == null) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "预测失败"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("direction", prediction.getDirection());
        result.put("confidence", String.format("%.1f%%", prediction.getConfidence() * 100));
        result.put("probabilities", Map.of(
                "bearish", String.format("%.1f%%", prediction.getProbabilities()[0] * 100),
                "neutral", String.format("%.1f%%", prediction.getProbabilities()[1] * 100),
                "bullish", String.format("%.1f%%", prediction.getProbabilities()[2] * 100)
        ));
        result.put("currentPrice", klines.get(klines.size() - 1).getClose());

        return ResponseEntity.ok(result);
    }

    /**
     * 查看模型状态。
     * GET /api/ml/status/{symbol}?interval=1h
     */
    @GetMapping("/status/{symbol}")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("symbol", symbol);
        status.put("interval", interval);
        status.put("modelReady", mlModelService.isModelReady(symbol, interval));
        status.put("featureCount", FeatureEngineerService.FEATURE_COUNT);
        status.put("featureNames", FeatureEngineerService.FEATURE_NAMES);
        return ResponseEntity.ok(status);
    }
}
