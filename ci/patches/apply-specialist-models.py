from pathlib import Path

root = Path("MultiAITradingAndroid/app/src/main/java/com/localtrading/council")
repository_source = Path("ci/patches/SpecialistModelRepository.kt")
repository_target = root / "data" / "SpecialistModelRepository.kt"
repository_target.parent.mkdir(parents=True, exist_ok=True)
repository_target.write_text(repository_source.read_text())

for filename in ("Chronos2MarketForecaster.kt", "TradingCouncilEngine.kt"):
    source_file = Path("ci/patches") / filename
    target_file = root / "ai" / filename
    target_file.parent.mkdir(parents=True, exist_ok=True)
    target_file.write_text(source_file.read_text())

activity_path = root / "MainActivity.kt"
source = activity_path.read_text()

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)

state_anchor = "    var downloadProgress by remember { mutableStateOf<Int?>(null) }\n"
state_new = state_anchor + (
    "    var specialistProgress by remember { mutableStateOf<Int?>(null) }\n"
    "    var downloadingSpecialist by remember { mutableStateOf<com.localtrading.council.data.SpecialistModelId?>(null) }\n"
    "    var activeSpecialist by remember { mutableStateOf<com.localtrading.council.data.SpecialistModelId?>(null) }\n"
    "    var marketSymbol by remember { mutableStateOf(\"XAUUSD\") }\n"
    "    var roundTripCostText by remember { mutableStateOf(\"10\") }\n"
    "    var marketNewsText by remember { mutableStateOf(\"\") }\n"
    "    var marketBars by remember { mutableStateOf<List<com.localtrading.council.ai.MarketCandle15m>>(emptyList()) }\n"
    "    var marketCsvStatus by remember { mutableStateOf(\"Chưa có dữ liệu. Hãy chọn CSV OHLCV M15 gồm 5.760 nến.\") }\n"
    "    var councilReportText by remember { mutableStateOf(\"\") }\n"
    "    var councilProgressText by remember { mutableStateOf(\"\") }\n"
    "    var councilProgress by remember { mutableStateOf<Int?>(null) }\n"
)
source = replace_once(source, state_anchor, state_new, "specialist state")

market_picker = """    val marketCsvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val csvText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Không mở được tệp CSV.")
                    }
                    val parsed = com.localtrading.council.ai.MarketCsvParser.parse(csvText)
                    marketBars = parsed
                    marketCsvStatus = "Đã nạp " + parsed.size + " nến M15 hợp lệ từ CSV."
                    councilReportText = ""
                } catch (e: Exception) {
                    marketBars = emptyList()
                    marketCsvStatus = "CSV bị từ chối: " + (e.message ?: "lỗi định dạng")
                }
            }
        }
    }
    """
source = replace_once(
    source,
    "    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->",
    market_picker + "    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->",
    "market CSV picker"
)


activation_callback = """                        onActivateSpecialist = { modelId ->
                            scope.launch {
                                isBusy = true
                                downloadingSpecialist = modelId
                                specialistProgress = 0
                                toastText = "Chuẩn bị AI chuyên môn: ${modelId.displayName}"
                                try {
                                    val file = com.localtrading.council.data.SpecialistModelRepository.installedFile(context, modelId)
                                        ?: com.localtrading.council.data.SpecialistModelRepository.download(context, modelId) { progress ->
                                            scope.launch(Dispatchers.Main) {
                                                if (downloadingSpecialist == modelId) specialistProgress = progress
                                            }
                                        }
                                    check(LlamaEngine.nativeAvailable) {
                                        "Thư viện suy luận llama.cpp chưa sẵn sàng trong bản APK này."
                                    }
                                    runCatching { LlamaEngine.nativeFree() }
                                    llmReady = false
                                    activeSpecialist = null
                                    val loaded = withContext(Dispatchers.IO) {
                                        runCatching {
                                            LlamaEngine.nativeLoad(file.absolutePath, 2048, 4)
                                        }.getOrDefault(false)
                                    }
                                    check(loaded) {
                                        "Đã tải và xác minh mô hình, nhưng runtime hiện tại không nạp được tệp GGUF."
                                    }
                                    llmReady = true
                                    activeSpecialist = modelId
                                    runtimeMessage = "${modelId.displayName} đã nạp vào RAM cục bộ. Không có lệnh giao dịch tiền thật nào được gửi."
                                    toastText = "Đã nạp AI chuyên môn: ${modelId.displayName}"
                                } catch (e: Exception) {
                                    toastText = "Không nạp được ${modelId.displayName}: ${e.message ?: "lỗi tải hoặc runtime"}"
                                } finally {
                                    isBusy = false
                                    downloadingSpecialist = null
                                    specialistProgress = null
                                }
                            }
                        },
                        activeSpecialist = activeSpecialist,
                        downloadingSpecialist = downloadingSpecialist,
                        specialistProgress = specialistProgress,
                        installedSpecialistIds = com.localtrading.council.data.SpecialistModelId.values()
                            .filter { com.localtrading.council.data.SpecialistModelRepository.installedFile(context, it) != null }
                            .toSet(),
                        marketSymbol = marketSymbol,
                        onMarketSymbolChange = { marketSymbol = it },
                        roundTripCostText = roundTripCostText,
                        onRoundTripCostChange = { roundTripCostText = it },
                        marketNewsText = marketNewsText,
                        onMarketNewsChange = { marketNewsText = it },
                        marketCsvStatus = marketCsvStatus,
                        marketBarsCount = marketBars.size,
                        onImportMarketCsv = { marketCsvPicker.launch(arrayOf("text/csv", "text/*", "application/vnd.ms-excel")) },
                        councilReportText = councilReportText,
                        councilProgressText = councilProgressText,
                        councilProgress = councilProgress,
                        onRunCouncil = { symbol, costBps, news ->
                            scope.launch {
                                isBusy = true
                                councilProgress = 0
                                councilProgressText = "Starting council…"
                                councilReportText = ""
                                try {
                                    val report = com.localtrading.council.ai.TradingCouncilEngine.run(
                                        context = context,
                                        instrument = symbol,
                                        candles = marketBars,
                                        roundTripCostBps = costBps,
                                        newsText = news,
                                        onProgress = { stage, progress ->
                                            scope.launch(Dispatchers.Main) {
                                                councilProgressText = stage
                                                councilProgress = progress
                                            }
                                        }
                                    )
                                    councilReportText = report.toDisplayText()
                                    toastText = "Hội đồng AI đã hoàn tất phân tích nghiên cứu."
                                } catch (e: Exception) {
                                    councilReportText = "Hội đồng chưa thể kết luận: " + (e.message ?: "lỗi mô hình/dữ liệu") +
                                        "\nKhông có giao dịch nào được thực hiện."
                                    toastText = "Phân tích chưa hoàn tất."
                                } finally {
                                    isBusy = false
                                    councilProgress = null
                                }
                            }
                        },
"""
source = replace_once(
    source,
    "                        onRoleMenu = { roleMenuExpanded = it },",
    activation_callback + "                        onRoleMenu = { roleMenuExpanded = it },",
    "specialist callback in ModelScreen"
)

signature_anchor = "    onDownloadRecommended: () -> Unit,\n    onRoleMenu: (Boolean) -> Unit,"
signature_new = """    onDownloadRecommended: () -> Unit,
    onActivateSpecialist: (com.localtrading.council.data.SpecialistModelId) -> Unit,
    activeSpecialist: com.localtrading.council.data.SpecialistModelId?,
    downloadingSpecialist: com.localtrading.council.data.SpecialistModelId?,
    specialistProgress: Int?,
    installedSpecialistIds: Set<com.localtrading.council.data.SpecialistModelId>,
    marketSymbol: String,
    onMarketSymbolChange: (String) -> Unit,
    roundTripCostText: String,
    onRoundTripCostChange: (String) -> Unit,
    marketNewsText: String,
    onMarketNewsChange: (String) -> Unit,
    marketCsvStatus: String,
    marketBarsCount: Int,
    onImportMarketCsv: () -> Unit,
    councilReportText: String,
    councilProgressText: String,
    councilProgress: Int?,
    onRunCouncil: (String, Double, String) -> Unit,
    onRoleMenu: (Boolean) -> Unit,"""
source = replace_once(source, signature_anchor, signature_new, "ModelScreen parameters")

council_section = """        item {
            SectionTitle("Hội đồng AI tìm cơ hội thị trường", "Dự báo chuỗi thời gian + cảm xúc tin tức + toán/rủi ro + chiến lược + phản biện")
            Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = marketSymbol,
                        onValueChange = onMarketSymbolChange,
                        label = { Text("Mã thị trường / công cụ") },
                        placeholder = { Text("XAUUSD, EURUSD, BTCUSDT…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy
                    )
                    OutlinedTextField(
                        value = roundTripCostText,
                        onValueChange = onRoundTripCostChange,
                        label = { Text("Chi phí khứ hồi ước tính (basis points)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy
                    )
                    OutlinedTextField(
                        value = marketNewsText,
                        onValueChange = onMarketNewsChange,
                        label = { Text("Tin tức / headline tùy chọn (hiện chưa tự lấy tin live)") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy
                    )
                    Text(
                        "Nguồn giá hiện tại: CSV do bạn cung cấp. Cần 5.760 nến M15 theo thứ tự thời gian; cho phép nghỉ phiên/cuối tuần, không chèn nến giả. Header: timestamp,open,high,low,close,volume.",
                        color = SoftText, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Text(marketCsvStatus, color = if (marketBarsCount >= 5760) Mint else Amber, fontSize = 11.sp)
                    Button(
                        onClick = onImportMarketCsv,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Chọn CSV OHLCV M15") }
                    if (councilProgress != null) {
                        LinearProgressIndicator(
                            progress = { (councilProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(councilProgressText, color = Mint, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            onRunCouncil(marketSymbol.trim(), roundTripCostText.toDoubleOrNull() ?: -1.0, marketNewsText)
                        },
                        enabled = !isBusy && marketSymbol.isNotBlank() &&
                            roundTripCostText.toDoubleOrNull() != null &&
                            (roundTripCostText.toDoubleOrNull() ?: -1.0) >= 0.0 && marketBarsCount >= 5760,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isBusy) "Đang chạy hội đồng AI…" else "Chạy hội đồng AI trên thị trường này") }
                    Text(
                        "Lần đầu có thể tải ~131 MB Chronos-2, Qwen điều phối và các AI chuyên môn cần thiết. Mỗi GGUF được nạp tuần tự để giảm RAM. Không có lệnh giao dịch tiền thật.",
                        color = SoftText, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
            if (councilReportText.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Biên bản thảo luận", color = Mint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(councilReportText, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
"""
specialist_section = council_section + """        item {
            SectionTitle("AI chuyên môn có trọng số thật", "Tải mô hình GGUF thật vào vùng riêng của app; một model chạy tại một thời điểm")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                com.localtrading.council.data.SpecialistModelId.values().forEach { specialist ->
                    val installed = specialist in installedSpecialistIds
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PanelRaised),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(specialist.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        when {
                                            activeSpecialist == specialist -> "ĐANG CHẠY TRONG RAM"
                                            installed -> "ĐÃ CÀI · CHƯA NẠP"
                                            else -> "MÔ HÌNH CÓ THỂ TẢI"
                                        },
                                        color = if (activeSpecialist == specialist) Mint else SoftText,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(specialist.sizeLabel, color = Mint, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(specialist.description, color = SoftText, fontSize = 11.sp, lineHeight = 16.sp)
                            if (downloadingSpecialist == specialist && specialistProgress != null) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { specialistProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    if (specialistProgress >= 100) "Đang xác minh / nạp model…" else "Đang tải ${specialistProgress}%",
                                    color = Mint,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            Button(
                                onClick = { onActivateSpecialist(specialist) },
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    when {
                                        downloadingSpecialist == specialist -> "Đang tải / nạp…"
                                        activeSpecialist == specialist -> "Đang hoạt động"
                                        installed -> "Nạp AI này vào RAM"
                                        else -> "Tải và nạp AI thật · ${specialist.sizeLabel}"
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Lưu ý: FinSenti chuyên phân loại cảm xúc văn bản tài chính, không dự báo giá. Model toán và lập trình là trợ lý chuyên biệt, không phải bộ dự báo giá. Chưa bật giao dịch tiền thật.",
                color = SoftText,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        item {
            SectionTitle("Thư viện mô hình", "Tệp được sao chép vào vùng riêng của ứng dụng")"""
source = replace_once(
    source,
    '        item {\n            SectionTitle("Thư viện mô hình", "Tệp được sao chép vào vùng riêng của ứng dụng")',
    specialist_section,
    "specialist models screen"
)

activity_path.write_text(source)
print("Installed specialist model catalog and updated ModelScreen.")
