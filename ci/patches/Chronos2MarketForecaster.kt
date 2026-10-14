package com.localtrading.council.ai

import android.content.Context
import android.os.StatFs
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * Chronos-2 ONNX INT8 fixed interface:
 * input context float32 [4,5760] = ln(close), ln(high), ln(low), ln(1+volume);
 * group_ids int32/int64 [4]; attention_mask [4,5760]; future_covariates
 * and future_covariates_mask [4,672].
 * Output quantile_preds is flattened per series as [21 quantiles,672 time steps].
 * The upstream checkpoint is a generic pretrained time-series model, not finance-fine-tuned.
 */
data class MarketCandle15m(
    val timestampEpochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
data class ForecastHorizon(val steps15m: Int, val medianPrice: Double, val p10Price: Double, val p90Price: Double)
data class MarketForecast(
    val modelName: String,
    val modelRevision: String,
    val instrument: String,
    val intervalMinutes: Int,
    val observations: Int,
    val lastClose: Double,
    val forecastGeneratedAtEpochSeconds: Long,
    val horizons: List<ForecastHorizon>
) {
    fun compactSummary(): String = buildString {
        appendLine("Model: " + modelName + " (" + modelRevision + "), general pretrained time-series foundation model.")
        appendLine("Instrument: " + instrument + "; interval: " + intervalMinutes + "m; input bars: " + observations + ".")
        appendLine("Last close: " + String.format(Locale.US, "%.8f", lastClose) + ".")
        for (h in horizons) {
            val medianPct = (h.medianPrice / lastClose - 1.0) * 100.0
            val p10Pct = (h.p10Price / lastClose - 1.0) * 100.0
            val p90Pct = (h.p90Price / lastClose - 1.0) * 100.0
            appendLine(
                "H=" + h.steps15m + " bars (" + (h.steps15m * 15) + "m): median=" +
                    String.format(Locale.US, "%.8f", h.medianPrice) + " (" +
                    String.format(Locale.US, "%+.3f", medianPct) + "%); p10=" +
                    String.format(Locale.US, "%+.3f", p10Pct) + "%; p90=" +
                    String.format(Locale.US, "%+.3f", p90Pct) + "%."
            )
        }
        append("Quantiles are model estimates, not calibrated probabilities of profit. No trade was placed.")
    }
}

object MarketCsvParser {
    private val formats = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    )
    fun parse(csv: String): List<MarketCandle15m> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2) { "CSV must have a header and OHLCV rows." }
        val delimiter = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        val headers = split(lines[0], delimiter).map { norm(it) }
        fun col(vararg aliases: String): Int {
            val idx = headers.indexOfFirst { aliases.contains(it) }
            require(idx >= 0) { "Missing CSV column: " + aliases.joinToString("/") }
            return idx
        }
        val t = col("timestamp", "time", "datetime", "date", "opentime", "unix", "epoch")
        val o = col("open", "o")
        val h = col("high", "h")
        val l = col("low", "l")
        val c = col("close", "adjclose", "c", "last")
        val v = headers.indexOfFirst { it == "volume" || it == "vol" || it == "v" || it == "tickvolume" }
        val rows = ArrayList<MarketCandle15m>()
        for (i in 1 until lines.size) {
            val cells = split(lines[i], delimiter)
            if (cells.size < headers.size) continue
            val ts = epoch(cells[t]) ?: error("Invalid timestamp at CSV line " + (i + 1))
            fun value(index: Int, name: String): Double {
                val d = cells[index].trim().replace(",", "").toDoubleOrNull()
                require(d != null && d.isFinite()) { "Invalid " + name + " at CSV line " + (i + 1) }
                return d
            }
            val open = value(o, "open")
            val high = value(h, "high")
            val low = value(l, "low")
            val close = value(c, "close")
            val volume = if (v < 0 || cells[v].isBlank()) 0.0 else value(v, "volume")
            require(open > 0 && high > 0 && low > 0 && close > 0 && volume >= 0) { "Invalid OHLCV at CSV line " + (i + 1) }
            require(high >= maxOf(open, close, low) && low <= minOf(open, close, high)) { "Inconsistent OHLC at CSV line " + (i + 1) }
            rows.add(MarketCandle15m(ts, open, high, low, close, volume))
        }
        val sorted = rows.distinctBy { it.timestampEpochSeconds }.sortedBy { it.timestampEpochSeconds }
        require(sorted.size >= CONTEXT) {
            "Chronos-2 requires " + CONTEXT + " M15 candles; got " + sorted.size + "."
        }
        val window = sorted.takeLast(CONTEXT)
        for (i in 1 until window.size) {
            val gap = window[i].timestampEpochSeconds - window[i - 1].timestampEpochSeconds
            require(gap == 900L) {
                "CSV has a gap or non-M15 interval (" + gap + " seconds). Do not fill missing candles with synthetic prices."
            }
        }
        return window
    }
    private fun norm(s: String) = s.trim().trim('"', '\'').lowercase(Locale.ROOT)
        .replace("_", "").replace("-", "").replace(" ", "")
    private fun split(line: String, delimiter: Char): List<String> {
        val result = ArrayList<String>()
        val item = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { item.append('"'); i++ }
                else quoted = !quoted
            } else if (ch == delimiter && !quoted) {
                result.add(item.toString()); item.setLength(0)
            } else item.append(ch)
            i++
        }
        result.add(item.toString())
        return result
    }
    private fun epoch(rawValue: String): Long? {
        val raw = rawValue.trim().trim('"')
        raw.toLongOrNull()?.let {
            return when { it > 100_000_000_000L -> it / 1000L; it > 1_000_000_000L -> it; else -> null }
        }
        runCatching { return Instant.parse(raw).epochSecond }
        runCatching { return OffsetDateTime.parse(raw).toInstant().epochSecond }
        for (f in formats.drop(1)) runCatching { return LocalDateTime.parse(raw, f).toInstant(ZoneOffset.UTC).epochSecond }
        return null
    }
    private const val CONTEXT = 5760
}

object Chronos2ModelRepository {
    const val MODEL_NAME = "Chronos-2 ONNX INT8"
    const val MODEL_REVISION = "d3aa45b0acbedadf69d241954ed1b21167937bae"
    private const val SIZE = 130_818_337L
    private const val SHA256 = "5a4b21df79238050b3815c4bda7e580ff254d1c755f7c5bad9eb11ac91920115"
    private const val URL =
        "https://huggingface.co/OpenSTEF/chronos-2-onnx/resolve/d3aa45b0acbedadf69d241954ed1b21167937bae/chronos-2_int8.onnx"
    suspend fun ensureInstalled(context: Context, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "timeseries").apply { mkdirs() }
            val finalFile = File(dir, "chronos-2-int8.onnx")
            if (finalFile.isFile && finalFile.length() == SIZE && sha256(finalFile) == SHA256) {
                onProgress(100); return@withContext finalFile
            }
            if (finalFile.exists()) finalFile.delete()
            val part = File(dir, "chronos-2-int8.onnx.part")
            var existing = part.length()
            if (existing >= SIZE) { part.delete(); existing = 0L }
            val need = (SIZE - existing).coerceAtLeast(0L) + 96L * 1024L * 1024L
            require(StatFs(dir.absolutePath).availableBytes >= need) {
                "Not enough free storage for Chronos-2 INT8. Need about " + (need / (1024L * 1024L)) + " MiB."
            }
            var conn: HttpURLConnection? = null
            try {
                val request = (URL(URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000; readTimeout = 90_000; instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MultiAI-Trading-Council-Android/0.3")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existing > 0L) setRequestProperty("Range", "bytes=" + existing + "-")
                }
                conn = request
                val code = request.responseCode
                val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
                require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "Chronos-2 download returned HTTP " + code
                }
                if (!append) { existing = 0L; part.delete() }
                val bodyLength = request.contentLengthLong
                val total = request.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: if (bodyLength > 0L) bodyLength + existing else SIZE
                require(total == SIZE) { part.delete(); "Model size differs from pinned upstream artifact." }
                val digest = MessageDigest.getInstance("SHA-256")
                if (append) BufferedInputStream(FileInputStream(part)).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                var count = existing
                var last = -1
                onProgress(((count * 100L) / SIZE).toInt().coerceIn(0, 99))
                BufferedInputStream(request.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(part, append)).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n
                            val p = ((count * 100L) / SIZE).toInt().coerceIn(0, 99)
                            if (p != last) { last = p; onProgress(p) }
                        }
                        output.flush()
                    }
                }
                require(count == SIZE && part.length() == SIZE) { "Incomplete Chronos-2 download; retry to resume." }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (!actual.equals(SHA256, true)) { part.delete(); error("Chronos-2 SHA-256 mismatch; corrupt model was deleted.") }
                require(part.renameTo(finalFile)) { "Cannot install verified Chronos-2 model." }
                onProgress(100); finalFile
            } finally { conn?.disconnect() }
        }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

object Chronos2MarketForecaster {
    private const val CONTEXT = 5760
    private const val HORIZON = 672
    private val quantileLevels = floatArrayOf(
        .01f, .05f, .10f, .15f, .20f, .25f, .30f, .35f, .40f, .45f, .50f,
        .55f, .60f, .65f, .70f, .75f, .80f, .85f, .90f, .95f, .99f
    )
    @Volatile private var cachedPath: String? = null
    @Volatile private var cachedSession: OrtSession? = null
    private val lock = Any()

    suspend fun forecast(
        context: Context,
        instrument: String,
        candles: List<MarketCandle15m>,
        onProgress: (Int) -> Unit = {}
    ): MarketForecast = withContext(Dispatchers.IO) {
        require(candles.size == CONTEXT) { "Expected " + CONTEXT + " valid M15 candles; got " + candles.size }
        require(instrument.isNotBlank()) { "Specify the market instrument." }
        val file = Chronos2ModelRepository.ensureInstalled(context, onProgress)
        val session = sessionFor(file)
        val env = OrtEnvironment.getEnvironment()
        val inputValues = FloatArray(4 * CONTEXT)
        for (i in 0 until CONTEXT) {
            val b = candles[i]
            inputValues[i] = lnPositive(b.close)
            inputValues[CONTEXT + i] = lnPositive(b.high)
            inputValues[2 * CONTEXT + i] = lnPositive(b.low)
            inputValues[3 * CONTEXT + i] = ln1p(b.volume.coerceAtLeast(0.0)).toFloat()
        }
        val ctxShape = longArrayOf(4L, CONTEXT.toLong())
        val futShape = longArrayOf(4L, HORIZON.toLong())
        val allObserved = FloatArray(4 * CONTEXT) { 1f }
        val noFuture = FloatArray(4 * HORIZON)
        val groupIds = LongArray(4)
        val inputs = linkedMapOf<String, OnnxTensor>()
        var output: OrtSession.Result? = null
        try {
            val info = session.inputInfo
            val required = setOf("context", "group_ids", "attention_mask", "future_covariates", "future_covariates_mask")
            require(info.keys.containsAll(required)) { "Chronos-2 ONNX input contract mismatch: " + info.keys.joinToString() }
            inputs["context"] = floatTensor(env, info["context"]!!, inputValues, ctxShape)
            inputs["group_ids"] = integerTensor(env, info["group_ids"]!!, groupIds)
            inputs["attention_mask"] = maskTensor(env, info["attention_mask"]!!, allObserved, ctxShape)
            inputs["future_covariates"] = floatTensor(env, info["future_covariates"]!!, noFuture, futShape)
            inputs["future_covariates_mask"] = maskTensor(env, info["future_covariates_mask"]!!, noFuture, futShape)
            output = session.run(inputs)
            val outputName = if (session.outputInfo.containsKey("quantile_preds")) "quantile_preds"
                else session.outputInfo.keys.firstOrNull() ?: error("Chronos-2 produced no outputs.")
            val tensor = output.get(outputName).orElseThrow() as? OnnxTensor
                ?: error("Chronos-2 output is not a tensor.")
            require(tensor.info.type == OnnxJavaType.FLOAT) { "Expected float32 quantile output, got " + tensor.info.type }
            val fb = tensor.floatBuffer
            fb.rewind()
            val raw = FloatArray(fb.remaining()); fb.get(raw)
            val perSeries = raw.size / 4
            require(perSeries >= quantileLevels.size * HORIZON) {
                "Chronos-2 output dimensions do not match 4 series × 21 quantiles × 672 steps."
            }
            val lastClose = candles.last().close
            fun horizon(steps: Int): ForecastHorizon {
                fun priceAt(q: Int): Double {
                    val value = raw[q * HORIZON + (steps - 1)]
                    require(value.isFinite() && value in -700f..700f) { "Chronos-2 output is not finite; reject the forecast." }
                    return exp(value.toDouble())
                }
                val p10 = priceAt(2); val p50 = priceAt(10); val p90 = priceAt(18)
                require(p10 > 0 && p10 <= p50 && p50 <= p90) { "Chronos-2 quantiles are invalid; reject the forecast." }
                return ForecastHorizon(steps, p50, p10, p90)
            }
            MarketForecast(
                modelName = Chronos2ModelRepository.MODEL_NAME,
                modelRevision = Chronos2ModelRepository.MODEL_REVISION,
                instrument = instrument.trim(),
                intervalMinutes = 15,
                observations = candles.size,
                lastClose = lastClose,
                forecastGeneratedAtEpochSeconds = Instant.now().epochSecond,
                horizons = listOf(horizon(4), horizon(16), horizon(96), horizon(672))
            )
        } finally {
            output?.close()
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun sessionFor(file: File): OrtSession = synchronized(lock) {
        if (cachedPath == file.absolutePath && cachedSession != null) return@synchronized cachedSession!!
        cachedSession?.close()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }
        val created = OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
        options.close()
        cachedPath = file.absolutePath; cachedSession = created; created
    }
    private fun lnPositive(value: Double): Float {
        require(value.isFinite() && value > 0.0) { "Price values must be positive and finite." }
        return ln(value).toFloat()
    }
    private fun tensorInfo(node: NodeInfo) = node.info as? TensorInfo ?: error("Input is not a tensor.")
    private fun floatTensor(env: OrtEnvironment, node: NodeInfo, values: FloatArray, shape: LongArray): OnnxTensor {
        require(tensorInfo(node).type == OnnxJavaType.FLOAT) { "Expected float32 input, got " + tensorInfo(node).type }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
    }
    private fun integerTensor(env: OrtEnvironment, node: NodeInfo, values: LongArray): OnnxTensor {
        return when (tensorInfo(node).type) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(values.size.toLong()))
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(values.map { it.toInt() }.toIntArray()), longArrayOf(values.size.toLong()))
            else -> error("Unsupported group_ids tensor type: " + tensorInfo(node).type)
        }
    }
    private fun maskTensor(env: OrtEnvironment, node: NodeInfo, values: FloatArray, shape: LongArray): OnnxTensor {
        return when (tensorInfo(node).type) {
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
            OnnxJavaType.DOUBLE -> OnnxTensor.createTensor(env, DoubleBuffer.wrap(values.map { it.toDouble() }.toDoubleArray()), shape)
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(values.map { it.toLong() }.toLongArray()), shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(values.map { it.toInt() }.toIntArray()), shape)
            OnnxJavaType.BOOL -> {
                val buffer = ByteBuffer.allocateDirect(values.size).order(ByteOrder.nativeOrder())
                values.forEach { buffer.put(if (it != 0f) 1.toByte() else 0.toByte()) }
                buffer.flip()
                OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.BOOL)
            }
            else -> error("Unsupported mask tensor type: " + tensorInfo(node).type)
        }
    }
}
