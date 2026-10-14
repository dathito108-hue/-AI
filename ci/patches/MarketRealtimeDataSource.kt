package com.localtrading.council.ai

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln

enum class MarketFeed { BINANCE_SPOT, TWELVE_DATA }

data class AutonomousMarketTarget(
    val symbol: String,
    val feed: MarketFeed,
    val quoteVolume24h: Double = 0.0,
    val changePercent24h: Double = 0.0,
    val discoveryScore: Double = 0.0
)

data class LiveMarketQuote(
    val symbol: String,
    val feed: MarketFeed,
    val lastPrice: Double,
    val changePercent24h: Double,
    val quoteVolume24h: Double,
    val eventTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long
) {
    val isStale: Boolean get() = System.currentTimeMillis() - receivedAtEpochMillis > 30_000L
    fun displayLine(): String = symbol + "  " +
        String.format(Locale.US, "%.8f", lastPrice) + "  " +
        String.format(Locale.US, "%+.2f%%", changePercent24h) +
        (if (quoteVolume24h > 0.0) "  24h vol " + String.format(Locale.US, "%.0f", quoteVolume24h) else "") +
        (if (isStale) "  STALE" else "  LIVE")
}

/** Public Binance spot data needs no key. FX/metals/stocks use the user's Twelve Data key. */
object MarketRealtimeDataSource : Closeable {
    private const val BINANCE_REST = "https://data-api.binance.vision"
    private const val BINANCE_WS = "wss://data-stream.binance.vision/stream"
    private const val TWELVE_REST = "https://api.twelvedata.com"
    private const val TWELVE_WS = "wss://ws.twelvedata.com/v1/quotes/price"
    private const val HISTORY_BARS = 6048
    private const val PAGE_SIZE = 1000

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val latestQuotes = ConcurrentHashMap<String, LiveMarketQuote>()
    @Volatile private var stopped = true
    @Volatile private var quoteCallback: ((LiveMarketQuote) -> Unit)? = null
    @Volatile private var statusCallback: ((String) -> Unit)? = null
    private var heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null

    private val ignoredBases = setOf(
        "USDT", "USDC", "FDUSD", "BUSD", "TUSD", "USDP", "DAI", "EUR", "TRY",
        "BRL", "GBP", "AUD", "BIDR", "IDRT", "UAH", "RUB", "NGN", "ZAR", "PLN"
    )
    private val leveragedSuffixes = listOf("UP", "DOWN", "BULL", "BEAR", "2L", "2S", "3L", "3S", "5L", "5S")

    fun normalizeBinanceSymbol(input: String): String =
        input.trim().uppercase(Locale.ROOT).replace("/", "").replace("-", "").replace("_", "")
            .also { require(it.matches(Regex("[A-Z0-9]{5,20}"))) { "Mã Binance không hợp lệ." } }

    fun quoteSnapshot(): List<LiveMarketQuote> =
        latestQuotes.values.sortedByDescending { it.receivedAtEpochMillis }.take(24)

    fun currentQuote(symbol: String): LiveMarketQuote? =
        latestQuotes[symbol.uppercase(Locale.ROOT)]?.takeIf { !it.isStale }

    suspend fun discoverBinanceMarkets(limit: Int = 16): List<AutonomousMarketTarget> =
        withContext(Dispatchers.IO) {
            require(limit in 1..40)
            val rows = JSONArray(getJson("$BINANCE_REST/api/v3/ticker/24hr"))
            val result = ArrayList<AutonomousMarketTarget>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val symbol = item.optString("symbol").uppercase(Locale.ROOT)
                if (!symbol.endsWith("USDT") || symbol.length < 6) continue
                val base = symbol.removeSuffix("USDT")
                if (base in ignoredBases || leveragedSuffixes.any { base.endsWith(it) }) continue
                val price = item.optString("lastPrice").toDoubleOrNull() ?: continue
                val volume = item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
                val change = item.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                if (!price.isFinite() || price <= 0.0 || !volume.isFinite() || volume < 5_000_000.0) continue
                // Liquidity × absolute move is only a cost-saving prescreener, not the trading decision.
                val score = ln(1.0 + volume) * abs(change).coerceIn(0.1, 15.0)
                result += AutonomousMarketTarget(symbol, MarketFeed.BINANCE_SPOT, volume, change, score)
            }
            result.sortedByDescending { it.discoveryScore }.take(limit)
        }

    fun connect(
        targets: List<AutonomousMarketTarget>,
        apiKey: String,
        onQuote: (LiveMarketQuote) -> Unit,
        onStatus: (String) -> Unit
    ) {
        stopSocketsOnly()
        stopped = false
        quoteCallback = onQuote
        statusCallback = onStatus
        val binance = targets.filter { it.feed == MarketFeed.BINANCE_SPOT }
            .map { it.symbol.lowercase(Locale.ROOT) }.distinct()
        val twelve = targets.filter { it.feed == MarketFeed.TWELVE_DATA }.map { it.symbol }.distinct()
        if (binance.isNotEmpty()) connectBinance(binance)
        if (twelve.isNotEmpty() && apiKey.isNotBlank()) connectTwelveData(twelve, apiKey)
    }

    private fun connectBinance(symbols: List<String>) {
        val streams = symbols.joinToString("/") { it + "@miniTicker" }
        val request = Request.Builder().url(BINANCE_WS + "?streams=" + streams)
            .header("User-Agent", "MultiAITradingCouncil-Android/0.4").build()
        sockets += http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!stopped) statusCallback?.invoke("Binance spot stream đang hoạt động cho " + symbols.size + " cặp.")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped) return
                runCatching {
                    val envelope = JSONObject(text)
                    val item = envelope.optJSONObject("data") ?: envelope
                    val symbol = item.optString("s").uppercase(Locale.ROOT)
                    val close = item.optString("c").toDoubleOrNull() ?: return
                    val open = item.optString("o").toDoubleOrNull() ?: close
                    val volume = item.optString("q").toDoubleOrNull() ?: 0.0
                    val eventTime = item.optLong("E", System.currentTimeMillis())
                    val change = if (open > 0) (close / open - 1.0) * 100.0 else 0.0
                    if (symbol.isNotBlank() && close > 0 && close.isFinite()) {
                        publish(LiveMarketQuote(symbol, MarketFeed.BINANCE_SPOT, close, change, volume, eventTime, System.currentTimeMillis()))
                    }
                }.onFailure { statusCallback?.invoke("Bỏ qua một bản tin Binance không hợp lệ.") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) statusCallback?.invoke("Binance stream đóng ($code); sẽ khởi tạo lại ở chu kỳ quét sau.")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped) statusCallback?.invoke("Binance realtime tạm lỗi: " + t.javaClass.simpleName + ".")
            }
        })
    }

    private fun connectTwelveData(symbols: List<String>, apiKey: String) {
        val key = URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8.name())
        val request = Request.Builder().url(TWELVE_WS + "?apikey=" + key)
            .header("User-Agent", "MultiAITradingCouncil-Android/0.4").build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("action", "subscribe")
                    .put("params", JSONObject().put("symbols", symbols.joinToString(","))).toString())
                statusCallback?.invoke("Đã gửi đăng ký Twelve Data cho " + symbols.size + " mã; quyền truy cập tùy gói.")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped) return
                runCatching {
                    val item = JSONObject(text)
                    when (item.optString("event").lowercase(Locale.ROOT)) {
                        "price" -> {
                            val symbol = item.optString("symbol").uppercase(Locale.ROOT)
                            val price = item.optString("price").toDoubleOrNull() ?: item.optDouble("price", Double.NaN)
                            val pct = item.optString("day_change_percentage").toDoubleOrNull()
                                ?: item.optString("percent_change").toDoubleOrNull() ?: 0.0
                            val rawTime = item.optLong("timestamp", System.currentTimeMillis() / 1000L)
                            val time = if (rawTime < 10_000_000_000L) rawTime * 1000L else rawTime
                            if (symbol.isNotBlank() && price.isFinite() && price > 0.0) {
                                publish(LiveMarketQuote(symbol, MarketFeed.TWELVE_DATA, price, pct, 0.0, time, System.currentTimeMillis()))
                            }
                        }
                        "error" -> statusCallback?.invoke("Twelve Data từ chối stream hoặc mã; kiểm tra quyền symbol/gói API.")
                    }
                }.onFailure { statusCallback?.invoke("Bỏ qua một bản tin Twelve Data không hợp lệ.") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeat?.cancel(false)
                if (!stopped) statusCallback?.invoke("Twelve Data stream đóng ($code).")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeat?.cancel(false)
                if (!stopped) statusCallback?.invoke("Twelve Data realtime tạm lỗi: " + t.javaClass.simpleName + ".")
            }
        })
        sockets += socket
        runCatching {
            heartbeat?.cancel(false)
            heartbeat = heartbeatExecutor.scheduleAtFixedRate({
                if (!stopped) sockets.lastOrNull()?.send(JSONObject().put("action", "heartbeat").toString())
            }, 10L, 10L, TimeUnit.SECONDS)
        }
    }

    private fun publish(quote: LiveMarketQuote) {
        latestQuotes[quote.symbol.uppercase(Locale.ROOT)] = quote
        quoteCallback?.invoke(quote)
    }

    suspend fun loadHistory(target: AutonomousMarketTarget, apiKey: String): List<MarketCandle15m> =
        when (target.feed) {
            MarketFeed.BINANCE_SPOT -> loadBinanceHistory(target.symbol)
            MarketFeed.TWELVE_DATA -> loadTwelveHistory(target.symbol, apiKey)
        }

    private suspend fun loadBinanceHistory(symbol: String): List<MarketCandle15m> = withContext(Dispatchers.IO) {
        val clean = normalizeBinanceSymbol(symbol)
        require(clean.endsWith("USDT")) { "Binance adapter hiện hỗ trợ cặp USDT, ví dụ BTCUSDT." }
        val rows = LinkedHashMap<Long, MarketCandle15m>()
        var endTime = System.currentTimeMillis()
        var attempts = 0
        while (rows.size < HISTORY_BARS && attempts < 10) {
            val url = HttpUrl.Builder().scheme("https").host("data-api.binance.vision")
                .addPathSegments("api/v3/klines").addQueryParameter("symbol", clean)
                .addQueryParameter("interval", "15m").addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("endTime", endTime.toString()).build()
            val page = JSONArray(getJson(url.toString()))
            if (page.length() == 0) break
            var oldestOpen: Long? = null
            for (i in 0 until page.length()) {
                val row = page.optJSONArray(i) ?: continue
                if (row.length() < 7) continue
                val openMillis = row.optLong(0)
                val closeMillis = row.optLong(6)
                if (closeMillis >= System.currentTimeMillis()) continue
                val open = row.optString(1).toDoubleOrNull() ?: continue
                val high = row.optString(2).toDoubleOrNull() ?: continue
                val low = row.optString(3).toDoubleOrNull() ?: continue
                val close = row.optString(4).toDoubleOrNull() ?: continue
                val volume = row.optString(5).toDoubleOrNull() ?: 0.0
                if (open <= 0 || high < maxOf(open, close, low) || low <= 0 || close <= 0) continue
                val seconds = openMillis / 1000L
                rows[seconds] = MarketCandle15m(seconds, open, high, low, close, volume)
                if (oldestOpen == null || openMillis < oldestOpen) oldestOpen = openMillis
            }
            attempts++
            if (oldestOpen == null) break
            endTime = oldestOpen - 1L
            if (page.length() < PAGE_SIZE && rows.size < HISTORY_BARS) break
        }
        val candles = rows.values.sortedBy { it.timestampEpochSeconds }.takeLast(HISTORY_BARS)
        require(candles.size >= HISTORY_BARS) {
            "Binance chỉ trả " + candles.size + "/" + HISTORY_BARS + " nến M15 hoàn chỉnh cho " + clean + "."
        }
        candles
    }

    private suspend fun loadTwelveHistory(symbol: String, apiKey: String): List<MarketCandle15m> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Cần Twelve Data API key cho forex, kim loại hoặc cổ phiếu." }
        val first = fetchTwelveSeries(symbol, apiKey, 5000, null)
        val all = LinkedHashMap<Long, MarketCandle15m>()
        first.forEach { all[it.timestampEpochSeconds] = it }
        if (all.size < HISTORY_BARS && all.isNotEmpty()) {
            val endDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(all.keys.minOrNull()!! - 900L), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            fetchTwelveSeries(symbol, apiKey, 2000, endDate).forEach { all[it.timestampEpochSeconds] = it }
        }
        val candles = all.values.sortedBy { it.timestampEpochSeconds }.takeLast(HISTORY_BARS)
        require(candles.size >= HISTORY_BARS) {
            "Twelve Data trả " + candles.size + "/" + HISTORY_BARS + " nến M15 cho " + symbol + "; kiểm tra quyền lịch sử/gói API."
        }
        candles
    }

    private fun fetchTwelveSeries(symbol: String, apiKey: String, outputSize: Int, endDate: String?): List<MarketCandle15m> {
        val builder = HttpUrl.Builder().scheme("https").host("api.twelvedata.com").addPathSegments("time_series")
            .addQueryParameter("symbol", symbol).addQueryParameter("interval", "15min")
            .addQueryParameter("outputsize", outputSize.toString()).addQueryParameter("timezone", "UTC")
            .addQueryParameter("format", "JSON")
        if (endDate != null) builder.addQueryParameter("end_date", endDate)
        val request = Request.Builder().url(builder.build()).header("Authorization", "apikey " + apiKey)
            .header("User-Agent", "MultiAITradingCouncil-Android/0.4").get().build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: error("Twelve Data phản hồi rỗng.")
            require(response.isSuccessful) { "Twelve Data HTTP " + response.code + "; kiểm tra hạn mức và quyền mã." }
            val json = JSONObject(body)
            require(!json.has("status") || json.optString("status") != "error") {
                "Twelve Data API: " + json.optString("message", "không có dữ liệu hoặc hết hạn mức")
            }
            val values = json.optJSONArray("values") ?: error("Twelve Data không có chuỗi thời gian cho " + symbol + ".")
            val result = ArrayList<MarketCandle15m>(values.length())
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val dateText = item.optString("datetime")
                val time = parseTwelveDate(dateText) ?: continue
                val open = item.optString("open").toDoubleOrNull() ?: continue
                val high = item.optString("high").toDoubleOrNull() ?: continue
                val low = item.optString("low").toDoubleOrNull() ?: continue
                val close = item.optString("close").toDoubleOrNull() ?: continue
                val volume = item.optString("volume").toDoubleOrNull() ?: 0.0
                if (open > 0 && high >= maxOf(open, close, low) && low > 0 && close > 0 && volume >= 0) {
                    result += MarketCandle15m(time, open, high, low, close, volume)
                }
            }
            result.distinctBy { it.timestampEpochSeconds }.sortedBy { it.timestampEpochSeconds }
        }
    }

    private fun parseTwelveDate(value: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (pattern in patterns) {
            val parsed = runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).toEpochSecond(ZoneOffset.UTC)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    suspend fun fetchLiveQuote(target: AutonomousMarketTarget, apiKey: String): LiveMarketQuote = withContext(Dispatchers.IO) {
        when (target.feed) {
            MarketFeed.BINANCE_SPOT -> {
                val url = HttpUrl.Builder().scheme("https").host("data-api.binance.vision")
                    .addPathSegments("api/v3/ticker/24hr").addQueryParameter("symbol", target.symbol).build()
                val item = JSONObject(getJson(url.toString()))
                val price = item.optString("lastPrice").toDoubleOrNull() ?: error("Binance không có giá.")
                val change = item.optString("priceChangePercent").toDoubleOrNull() ?: target.changePercent24h
                LiveMarketQuote(target.symbol, target.feed, price, change,
                    item.optString("quoteVolume").toDoubleOrNull() ?: target.quoteVolume24h,
                    System.currentTimeMillis(), System.currentTimeMillis())
            }
            MarketFeed.TWELVE_DATA -> {
                require(apiKey.isNotBlank()) { "Twelve Data key is required for " + target.symbol + "." }
                val url = HttpUrl.Builder().scheme("https").host("api.twelvedata.com").addPathSegments("price")
                    .addQueryParameter("symbol", target.symbol).build()
                val request = Request.Builder().url(url).header("Authorization", "apikey " + apiKey).get().build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: error("Twelve Data price response empty.")
                    require(response.isSuccessful) { "Twelve Data HTTP " + response.code + "." }
                    val json = JSONObject(body)
                    require(!json.has("status") || json.optString("status") != "error") {
                        "Twelve Data cannot quote " + target.symbol + ": " + json.optString("message", "provider error")
                    }
                    val price = json.optString("price").toDoubleOrNull() ?: error("Twelve Data trả về không có giá.")
                    LiveMarketQuote(target.symbol, target.feed, price, target.changePercent24h, 0.0,
                        System.currentTimeMillis(), System.currentTimeMillis())
                }
            }
        }
    }

    private fun getJson(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "MultiAITradingCouncil-Android/0.4").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: error("Nguồn giá trả về rỗng.")
            if (response.code == 429) error("Nguồn giá đã chạm giới hạn; bộ quét sẽ thử lại ở chu kỳ sau.")
            require(response.isSuccessful) { "Market data HTTP " + response.code + "." }
            return body
        }
    }

    private fun stopSocketsOnly() {
        stopped = true
        heartbeat?.cancel(false)
        heartbeat = null
        sockets.forEach { runCatching { it.close(1000, "scan cycle reset") } }
        sockets.clear()
    }

    override fun close() {
        stopSocketsOnly()
        heartbeatExecutor.shutdownNow()
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        quoteCallback = null
        statusCallback = null
    }
}

/** AUTO discovers markets; specified symbols narrow the scan. Research only, no order execution. */
object AutonomousMarketScanner {
    private const val AUTO_UNIVERSE = 16
    private const val MAX_AUTO_ANALYSES = 3
    private const val CYCLE_MILLIS = 15L * 60L * 1000L

    suspend fun runContinuously(
        context: Context,
        marketScope: String,
        twelveDataApiKey: String,
        onStatus: (String) -> Unit,
        onQuote: (LiveMarketQuote) -> Unit,
        onOpportunity: (AutonomousMarketTarget, TradingCouncilReport) -> Unit,
        onProgress: (String, Int) -> Unit
    ) {
        val raw = marketScope.trim()
        val auto = raw.isBlank() || raw.equals("AUTO", true) || raw.equals("TỰ ĐỘNG", true)
        val manual = if (auto) emptyList() else raw.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(auto || manual.isNotEmpty()) { "Nhập AUTO hoặc mã thị trường." }
        require(manual.size <= 10) { "Nhập tối đa 10 mã trong một phiên quét." }
        try {
            while (currentCoroutineContext().isActive) {
                val candidates: List<AutonomousMarketTarget> = if (auto) {
                    onStatus("Đang tự khám phá cặp crypto USDT theo thanh khoản và biến động 24h.")
                    val list = MarketRealtimeDataSource.discoverBinanceMarkets(AUTO_UNIVERSE).toMutableList()
                    if (twelveDataApiKey.isNotBlank()) list += listOf(
                        AutonomousMarketTarget("XAU/USD", MarketFeed.TWELVE_DATA),
                        AutonomousMarketTarget("EUR/USD", MarketFeed.TWELVE_DATA),
                        AutonomousMarketTarget("AAPL", MarketFeed.TWELVE_DATA)
                    )
                    list
                } else manual.map { symbol ->
                    val normalized = symbol.trim().uppercase(Locale.ROOT)
                    if (normalized.matches(Regex("[A-Z0-9]{1,16}USDT"))) {
                        AutonomousMarketTarget(MarketRealtimeDataSource.normalizeBinanceSymbol(normalized), MarketFeed.BINANCE_SPOT)
                    } else {
                        require(twelveDataApiKey.isNotBlank()) {
                            "Mã '$symbol' cần Twelve Data API key. Crypto USDT trên Binance không cần key."
                        }
                        AutonomousMarketTarget(normalized, MarketFeed.TWELVE_DATA)
                    }
                }
                require(candidates.isNotEmpty()) { "Không tìm thấy thị trường đủ dữ liệu/thanh khoản." }

                MarketRealtimeDataSource.connect(candidates, twelveDataApiKey, onQuote, onStatus)
                delay(2500L)
                val selected = if (auto) {
                    val crypto = candidates.filter { it.feed == MarketFeed.BINANCE_SPOT }
                        .sortedByDescending { target ->
                            val q = MarketRealtimeDataSource.currentQuote(target.symbol)
                            val change = q?.changePercent24h ?: target.changePercent24h
                            val volume = q?.quoteVolume24h?.takeIf { it > 0.0 } ?: target.quoteVolume24h
                            ln(1.0 + volume) * abs(change).coerceIn(0.1, 15.0)
                        }.take(if (twelveDataApiKey.isBlank()) MAX_AUTO_ANALYSES else 2)
                    val other = candidates.filter { it.feed == MarketFeed.TWELVE_DATA }
                        .mapNotNull { target ->
                            val quote = MarketRealtimeDataSource.currentQuote(target.symbol) ?: runCatching {
                                MarketRealtimeDataSource.fetchLiveQuote(target, twelveDataApiKey)
                            }.getOrNull()
                            if (quote == null) null else target.copy(
                                changePercent24h = quote.changePercent24h,
                                discoveryScore = abs(quote.changePercent24h) + 0.01
                            )
                        }.sortedByDescending { it.discoveryScore }.take(1)
                    (crypto + other).take(MAX_AUTO_ANALYSES)
                } else candidates

                onStatus("Realtime stream đang mở; AI lần lượt nghiên cứu " + selected.size + " mã. Mục tiêu chu kỳ: 15 phút.")
                for ((index, target) in selected.withIndex()) {
                    if (!currentCoroutineContext().isActive) break
                    try {
                        onStatus("(" + (index + 1) + "/" + selected.size + ") Đang tải 6.048 nến M15 hoàn chỉnh: " + target.symbol)
                        val bars = MarketRealtimeDataSource.loadHistory(target, twelveDataApiKey)
                        MarketOpportunityJournal.evaluateMatured(context, target.symbol, bars)
                        val priorResearch = MarketOpportunityJournal.recentFeedback(context, target.symbol, 24)
                        onStatus("Đang chạy Chronos-2, kiểm định ngoài mẫu và hội đồng AI: " + target.symbol)
                        val costBps = if (target.feed == MarketFeed.BINANCE_SPOT) 20.0 else 30.0
                        val report = TradingCouncilEngine.run(
                            context = context,
                            instrument = target.symbol,
                            candles = bars,
                            roundTripCostBps = costBps,
                            newsText = "",
                            priorResearchContext = priorResearch,
                            onProgress = onProgress
                        )
                        MarketOpportunityJournal.append(context, target, report)
                        onOpportunity(target, report)
                        onStatus("Đã lưu biên bản " + target.symbol + ". Không có lệnh tiền thật nào được gửi.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onStatus(target.symbol + ": bỏ qua lượt này — " + (e.message ?: e.javaClass.simpleName))
                    }
                }
                onStatus("Hoàn tất lượt phân tích. Stream đang tiếp tục; lượt AI mới sau 15 phút.")
                delay(CYCLE_MILLIS)
            }
        } finally {
            MarketRealtimeDataSource.close()
            onStatus("Đã dừng bộ quét tự động.")
        }
    }
}

object MarketOpportunityJournal {
    private const val NAME = "market-opportunity-journal.jsonl"
    private const val MAX_BYTES = 16L * 1024L * 1024L
    private const val KEEP_LINES = 15000
    private val lock = Any()

    fun append(context: Context, target: AutonomousMarketTarget, report: TradingCouncilReport) {
        val file = File(context.filesDir, NAME)
        synchronized(lock) {
            runCatching {
                val horizons = JSONArray()
                report.forecast.horizons.forEach { h ->
                    horizons.put(JSONObject()
                        .put("steps", h.steps15m)
                        .put("median", h.medianPrice)
                        .put("p10", h.p10Price)
                        .put("p90", h.p90Price))
                }
                val id = target.symbol + "|" + report.forecast.originBarTimestampEpochSeconds + "|" +
                    report.forecast.forecastGeneratedAtEpochSeconds
                val line = JSONObject()
                    .put("kind", "forecast")
                    .put("forecast_id", id)
                    .put("recorded_at_epoch_seconds", Instant.now().epochSecond)
                    .put("symbol", target.symbol.uppercase(Locale.ROOT))
                    .put("feed", target.feed.name)
                    .put("origin_bar_epoch_seconds", report.forecast.originBarTimestampEpochSeconds)
                    .put("forecast_generated_at_epoch_seconds", report.forecast.forecastGeneratedAtEpochSeconds)
                    .put("price_at_origin", report.forecast.lastClose)
                    .put("forecast_model", report.forecast.modelName)
                    .put("forecast_revision", report.forecast.modelRevision)
                    .put("horizons", horizons)
                    .put("backtest_pass", report.backtest.passesPreliminaryGate)
                    .put("backtest", report.backtest.compactSummary())
                    .put("final_decision", report.finalDecision.take(2200))
                    .toString()
                FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
                trimIfNeeded(file)
            }
        }
    }

    /**
     * Score forecasts only after the stated horizon has matured in subsequently observed candles.
     * This evaluates price forecasts, not hypothetical or realised trade P&L.
     */
    fun evaluateMatured(
        context: Context,
        symbol: String,
        candles: List<MarketCandle15m>
    ): Int = synchronized(lock) {
        val file = File(context.filesDir, NAME)
        if (!file.isFile || candles.size < 2) return@synchronized 0
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrElse { return@synchronized 0 }
        val targetSymbol = symbol.uppercase(Locale.ROOT)
        val existingKeys = HashSet<String>()
        val forecasts = ArrayList<JSONObject>()
        for (line in lines) {
            val item = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (item.optString("kind") == "evaluation") existingKeys.add(item.optString("evaluation_key"))
            if (item.optString("kind") == "forecast" && item.optString("symbol").uppercase(Locale.ROOT) == targetSymbol) {
                forecasts.add(item)
            }
        }
        val indexByTimestamp = HashMap<Long, Int>(candles.size)
        candles.forEachIndexed { index, candle -> indexByTimestamp[candle.timestampEpochSeconds] = index }
        val newRecords = ArrayList<String>()
        for (forecast in forecasts) {
            val id = forecast.optString("forecast_id")
            val origin = forecast.optLong("origin_bar_epoch_seconds", -1L)
            val originIndex = indexByTimestamp[origin] ?: continue
            val baseline = forecast.optDouble("price_at_origin", Double.NaN)
            if (!baseline.isFinite() || baseline <= 0.0) continue
            val horizons = forecast.optJSONArray("horizons") ?: continue
            for (h in 0 until horizons.length()) {
                val predicted = horizons.optJSONObject(h) ?: continue
                val steps = predicted.optInt("steps", 0)
                if (steps <= 0) continue
                val evaluationKey = id + "|" + steps
                if (evaluationKey in existingKeys) continue
                val actualIndex = originIndex + steps
                if (actualIndex >= candles.size) continue
                val actual = candles[actualIndex].close
                val median = predicted.optDouble("median", Double.NaN)
                val p10 = predicted.optDouble("p10", Double.NaN)
                val p90 = predicted.optDouble("p90", Double.NaN)
                if (!actual.isFinite() || actual <= 0 || !median.isFinite() || !p10.isFinite() || !p90.isFinite()) continue
                val predReturn = (median / baseline - 1.0) * 100.0
                val actualReturn = (actual / baseline - 1.0) * 100.0
                val directionValid = abs(actualReturn) >= 0.001
                val directionHit = directionValid && ((predReturn >= 0.0) == (actualReturn >= 0.0))
                val record = JSONObject()
                    .put("kind", "evaluation")
                    .put("evaluation_key", evaluationKey)
                    .put("forecast_id", id)
                    .put("recorded_at_epoch_seconds", Instant.now().epochSecond)
                    .put("symbol", targetSymbol)
                    .put("feed", forecast.optString("feed"))
                    .put("origin_bar_epoch_seconds", origin)
                    .put("target_bar_epoch_seconds", candles[actualIndex].timestampEpochSeconds)
                    .put("steps", steps)
                    .put("model", forecast.optString("forecast_model"))
                    .put("baseline_price", baseline)
                    .put("predicted_median_price", median)
                    .put("actual_close", actual)
                    .put("predicted_return_pct", predReturn)
                    .put("actual_return_pct", actualReturn)
                    .put("absolute_error_pct", abs(median - actual) / actual * 100.0)
                    .put("direction_valid", directionValid)
                    .put("direction_hit", directionHit)
                    .put("interval_covered", p10 <= actual && actual <= p90)
                    .toString()
                newRecords.add(record)
                existingKeys.add(evaluationKey)
            }
        }
        if (newRecords.isNotEmpty()) {
            runCatching {
                FileOutputStream(file, true).use { output ->
                    newRecords.forEach { output.write((it + "\n").toByteArray(Charsets.UTF_8)) }
                }
                trimIfNeeded(file)
            }
        }
        newRecords.size
    }

    fun recentFeedback(context: Context, symbol: String, count: Int = 24): String = synchronized(lock) {
        val file = File(context.filesDir, NAME)
        if (!file.isFile) return@synchronized "Chưa có đánh giá dự báo đã trưởng thành."
        val target = symbol.uppercase(Locale.ROOT)
        val records = runCatching { file.readLines(Charsets.UTF_8) }.getOrElse {
            return@synchronized "Không đọc được nhật ký kết quả cũ; không được giả định kết quả."
        }.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { it.optString("kind") == "evaluation" && it.optString("symbol").uppercase(Locale.ROOT) == target }
            .takeLast(count.coerceIn(1, 100))
        if (records.isEmpty()) {
            return@synchronized "Chưa có dự báo đủ thời gian để chấm điểm ngoài mẫu cho " + target +
                ". Chỉ được xem dự báo mới là giả thuyết, chưa có bằng chứng hiệu quả."
        }
        buildString {
            appendLine("Matured price-forecast outcomes for " + target + " (not realised strategy P&L):")
            for (steps in listOf(4, 16, 96, 672)) {
                val group = records.filter { it.optInt("steps") == steps }
                if (group.isEmpty()) continue
                val mae = group.map { it.optDouble("absolute_error_pct", Double.NaN) }.filter { it.isFinite() }.average()
                val validDirections = group.filter { it.optBoolean("direction_valid") }
                val direction = if (validDirections.isEmpty()) Double.NaN else
                    validDirections.count { it.optBoolean("direction_hit") } * 100.0 / validDirections.size
                val coverage = group.count { it.optBoolean("interval_covered") } * 100.0 / group.size
                appendLine("H=" + steps + " bars: samples=" + group.size +
                    ", MAE=" + String.format(Locale.US, "%.3f", mae) + "%, direction hit=" +
                    (if (direction.isFinite()) String.format(Locale.US, "%.1f", direction) + "%" else "n/a") +
                    ", p10-p90 coverage=" + String.format(Locale.US, "%.1f", coverage) + "%.")
            }
            append("Use mature outcomes to revise testable strategy hypotheses; do not infer profitability without fee/slippage-aware trading backtests.")
        }
    }

    fun recent(context: Context, count: Int = 8): String = synchronized(lock) {
        val file = File(context.filesDir, NAME)
        if (!file.isFile) return@synchronized "Chưa có nhật ký nghiên cứu trước đó."
        runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("kind") == "forecast" }.takeLast(count.coerceIn(1, 30))
                .joinToString("\n") { item ->
                    item.optString("symbol") + " @" + item.optLong("recorded_at_epoch_seconds") +
                        ": " + item.optString("backtest") + " | " + item.optString("final_decision").take(350)
                }
        }.getOrDefault("Không thể đọc nhật ký nghiên cứu.")
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        val keep = runCatching { file.readLines(Charsets.UTF_8).takeLast(KEEP_LINES) }.getOrDefault(emptyList())
        file.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
