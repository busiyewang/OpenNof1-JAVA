package com.crypto.trader.service.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureScalerTest {

    @Test
    void shouldFitAndTransformCorrectly() {
        FeatureScaler scaler = new FeatureScaler();
        double[][] data = {
                {10, 100},
                {20, 200},
                {30, 300}
        };

        scaler.fit(data);
        assertTrue(scaler.isFitted());

        float[] input = {20f, 200f};
        float[] result = scaler.transform(input);

        // 均值=20, std≈8.16 → z-score=0
        assertEquals(0.0f, result[0], 0.01f);
        assertEquals(0.0f, result[1], 0.01f);
    }

    @Test
    void shouldTransformInPlace() {
        FeatureScaler scaler = new FeatureScaler();
        double[][] data = {
                {10, 100},
                {20, 200},
                {30, 300}
        };

        scaler.fit(data);

        double[][] toTransform = {
                {10, 100},
                {30, 300}
        };
        scaler.transformInPlace(toTransform);

        // 均值=20, std≈8.16: (10-20)/8.16 ≈ -1.22
        assertTrue(toTransform[0][0] < 0);
        assertTrue(toTransform[1][0] > 0);
    }

    @Test
    void shouldHandleConstantFeature() {
        FeatureScaler scaler = new FeatureScaler();
        double[][] data = {
                {5, 100},
                {5, 200},
                {5, 300}
        };

        scaler.fit(data);

        // 第一个特征是常量，std=0 → 应该设为1避免除零
        float[] result = scaler.transform(new float[]{5f, 200f});
        assertFalse(Float.isNaN(result[0]));
        assertFalse(Float.isInfinite(result[0]));
    }

    @Test
    void shouldNotBeFittedByDefault() {
        FeatureScaler scaler = new FeatureScaler();
        assertFalse(scaler.isFitted());
    }

    @Test
    void shouldHandleNullFit() {
        FeatureScaler scaler = new FeatureScaler();
        scaler.fit(null);
        assertFalse(scaler.isFitted());
    }

    @Test
    void shouldHandleEmptyFit() {
        FeatureScaler scaler = new FeatureScaler();
        scaler.fit(new double[0][]);
        assertFalse(scaler.isFitted());
    }

    @Test
    void shouldApproximateZeroMeanUnitVariance() {
        FeatureScaler scaler = new FeatureScaler();
        int n = 1000;
        double[][] data = new double[n][2];
        for (int i = 0; i < n; i++) {
            data[i][0] = i;
            data[i][1] = i * 10;
        }

        scaler.fit(data);

        // 对训练数据做变换后，均值应约为 0
        double[][] copy = new double[n][2];
        for (int i = 0; i < n; i++) {
            copy[i][0] = data[i][0];
            copy[i][1] = data[i][1];
        }
        scaler.transformInPlace(copy);

        double sum0 = 0, sum1 = 0;
        for (int i = 0; i < n; i++) {
            sum0 += copy[i][0];
            sum1 += copy[i][1];
        }
        assertEquals(0, sum0 / n, 0.01);
        assertEquals(0, sum1 / n, 0.01);
    }
}
