package com.localtrading.council.ai

import android.content.Context
import java.util.Locale

data class WalkForwardBacktestReport(
    val folds: Int,
    val testedBars: Int,
    val modelMaePct: Double,
    val persistenceMaePct: Double,
    val directionalAccuracyPct: Double,
    val p10p90CoveragePct: Double,
    val passesPreliminaryGate: Boolean
) {
    fun compactSummary(): String = buildString {
        appendLine("Recent rolling-origin evaluation: " + folds + " folds × 96 M15 bars = " + testedBars + " out-of-sample bars.")
        appendLine("Chronos median-forecast MAE (% of actual close): " + format(modelMaePct) + "%.")
        appendLine("Naive last-close persistence MAE: " + format(persistenceMaePct) + "%.")
        appendLine("Direction accuracy (relative to each fold origin): " + format(directionalAccuracyPct) + "%.")
        appendLine("Actual closes inside the model p10–p90 interval: " + format(p10p90CoveragePct) + "%.")
        appendLine(
            "Preliminary screen: " + if (passesPreliminaryGate) "PASS" else "FAIL — NO TRADE VETO" +
                ". Gate requires lower MAE than persistence, direction accuracy above 50%, and p10–p90 coverage in 50–98%."
        )
        append("Important: this short recent holdout is only a sanity check, not evidence of profitability or a replacement for longer walk-forward, fee/slippage-aware backtests.")
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}

object MarketWalkForwardBacktester {
    private const val CONTEXT_BARS = 5760
    private const val FOLDS = 3
    private const val TEST_BARS_PER_FOLD = 96
    const val MINIMUM_BARS = CONTEXT_BARS + FOLDS * TEST_BARS_PER_FOLD

    suspend fun run(
        context: Context,
        instrument: String,
        allCandles: List<MarketCandle15m>,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): WalkForwardBacktestReport {
        require(allCandles.size >= MINIMUM_BARS) {
            "The council needs at least " + MINIMUM_BARS +
                " ordered M15 bars: 5,760 input bars plus 3×96 unseen bars for the rolling-origin screen."
        }
        require(allCandles.zipWithNext().all { (a, b) -> b.timestampEpochSeconds > a.timestampEpochSeconds }) {
            "Market data must be sorted by timestamp with duplicates removed."
        }

        var modelErrorSum = 0.0
        var baselineErrorSum = 0.0
        var tested = 0
        var directionHits = 0
        var directionCount = 0
        var intervalHits = 0

        val firstTestStart = allCandles.size - FOLDS * TEST_BARS_PER_FOLD
        for (fold in 0 until FOLDS) {
            val testStart = firstTestStart + fold * TEST_BARS_PER_FOLD
            val originIndex = testStart - 1
            val history = allCandles.subList(testStart - CONTEXT_BARS, testStart).toList()
            val actual = allCandles.subList(testStart, testStart + TEST_BARS_PER_FOLD)
            val originClose = allCandles[originIndex].close

            onProgress("Walk-forward: fold " + (fold + 1) + "/" + FOLDS, fold * 100 / FOLDS)
            val forecast = Chronos2MarketForecaster.forecast(context, instrument, history) { p ->
                if (p < 100) onProgress("Load Chronos-2", p)
            }
            require(forecast.predictionPath.size >= TEST_BARS_PER_FOLD) {
                "Chronos prediction path is shorter than the holdout window."
            }

            for (step in 0 until TEST_BARS_PER_FOLD) {
                val predicted = forecast.predictionPath[step]
                val actualClose = actual[step].close
                modelErrorSum += kotlin.math.abs(predicted.medianPrice - actualClose) / actualClose * 100.0
                baselineErrorSum += kotlin.math.abs(originClose - actualClose) / actualClose * 100.0
                if (predicted.p10Price <= actualClose && actualClose <= predicted.p90Price) intervalHits++

                val predDelta = predicted.medianPrice - originClose
                val actualDelta = actualClose - originClose
                if (kotlin.math.abs(actualDelta) > originClose * 0.000001) {
                    val predDirection = if (predDelta > 0.0) 1 else if (predDelta < 0.0) -1 else 0
                    val actualDirection = if (actualDelta > 0.0) 1 else -1
                    if (predDirection == actualDirection) directionHits++
                    directionCount++
                }
                tested++
            }
            onProgress("Walk-forward: fold " + (fold + 1) + " complete", (fold + 1) * 100 / FOLDS)
        }

        val modelMae = modelErrorSum / tested.coerceAtLeast(1)
        val baselineMae = baselineErrorSum / tested.coerceAtLeast(1)
        val directionAccuracy = if (directionCount == 0) 0.0 else directionHits * 100.0 / directionCount
        val coverage = intervalHits * 100.0 / tested.coerceAtLeast(1)
        val pass = modelMae < baselineMae &&
            directionAccuracy > 50.0 &&
            coverage in 50.0..98.0

        return WalkForwardBacktestReport(
            folds = FOLDS,
            testedBars = tested,
            modelMaePct = modelMae,
            persistenceMaePct = baselineMae,
            directionalAccuracyPct = directionAccuracy,
            p10p90CoveragePct = coverage,
            passesPreliminaryGate = pass
        )
    }
}
