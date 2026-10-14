from pathlib import Path

repo_root = Path(".")
android_root = Path("MultiAITradingAndroid/app/src/main/java/com/localtrading/council")
market_source = Path("ci/patches/MarketRealtimeDataSource.kt")
market_target = android_root / "ai" / "MarketRealtimeDataSource.kt"
market_target.parent.mkdir(parents=True, exist_ok=True)
market_target.write_text(market_source.read_text())

# Keep the WebSocket dependency deterministic in CI and avoid duplicate declarations.
gradle_file = Path("MultiAITradingAndroid/app/build.gradle.kts")
gradle_text = gradle_file.read_text()
if "com.squareup.okhttp3:okhttp" not in gradle_text:
    anchor = "dependencies {"
    if gradle_text.count(anchor) != 1:
        raise SystemExit("Could not uniquely find app dependencies block for OkHttp")
    gradle_text = gradle_text.replace(
        anchor,
        anchor + '\n    implementation("com.squareup.okhttp3:okhttp:4.12.0")',
        1
    )
    gradle_file.write_text(gradle_text)

activity_path = android_root / "MainActivity.kt"
source = activity_path.read_text()

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)

state_anchor = '    var councilProgress by remember { mutableStateOf<Int?>(null) }\n'
state_new = state_anchor + (
    '    var autonomousMarketScope by remember { mutableStateOf("AUTO") }\n'
    '    var twelveDataApiKey by remember { mutableStateOf("") }\n'
    '    var autonomousRunning by remember { mutableStateOf(false) }\n'
    '    var autonomousStatus by remember { mutableStateOf("Sẵn sàng. AUTO sẽ tự khám phá các cặp Binance spot đủ thanh khoản.") }\n'
    '    var autonomousQuotes by remember { mutableStateOf<List<com.localtrading.council.ai.LiveMarketQuote>>(emptyList()) }\n'
    '    var autonomousReportText by remember { mutableStateOf("") }\n'
    '    var autonomousJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }\n'
)
source = replace_once(source, state_anchor, state_new, "autonomous scanner state")

callback_anchor = "                        onRoleMenu = { roleMenuExpanded = it },"
callback_new = """                        autonomousMarketScope = autonomousMarketScope,
                        onAutonomousMarketScopeChange = { autonomousMarketScope = it },
                        twelveDataApiKey = twelveDataApiKey,
                        onTwelveDataApiKeyChange = { twelveDataApiKey = it },
                        autonomousRunning = autonomousRunning,
                        autonomousStatus = autonomousStatus,
                        autonomousQuotes = autonomousQuotes,
                        autonomousReportText = autonomousReportText,
                        onToggleAutonomous = { scopeText, apiKey ->
                            if (autonomousRunning) {
                                autonomousJob?.cancel()
                                autonomousJob = null
                                autonomousRunning = false
                                autonomousStatus = "Đang dừng bộ quét…"
                            } else {
                                autonomousRunning = true
                                autonomousStatus = "Đang khởi tạo nguồn dữ liệu realtime…"
                                autonomousReportText = ""
                                autonomousJob = scope.launch(Dispatchers.IO) {
                                    try {
                                        com.localtrading.council.ai.AutonomousMarketScanner.runContinuously(
                                            context = context,
                                            marketScope = scopeText,
                                            twelveDataApiKey = apiKey,
                                            onStatus = { status ->
                                                scope.launch(Dispatchers.Main) { autonomousStatus = status }
                                            },
                                            onQuote = { quote ->
                                                scope.launch(Dispatchers.Main) {
                                                    autonomousQuotes = (autonomousQuotes.filterNot { it.symbol == quote.symbol } + quote)
                                                        .sortedByDescending { it.receivedAtEpochMillis }
                                                        .take(12)
                                                }
                                            },
                                            onOpportunity = { target, report ->
                                                scope.launch(Dispatchers.Main) {
                                                    autonomousReportText =
                                                        "Mã: " + target.symbol + " · Nguồn: " + target.feed.name + "\\n\\n" +
                                                        report.toDisplayText().take(18000)
                                                }
                                            },
                                            onProgress = { stage, progress ->
                                                scope.launch(Dispatchers.Main) {
                                                    autonomousStatus = stage + " — " + progress + "%"
                                                }
                                            }
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        scope.launch(Dispatchers.Main) {
                                            autonomousStatus = "Bộ quét gặp lỗi: " + (e.message ?: e.javaClass.simpleName)
                                        }
                                    } finally {
                                        scope.launch(Dispatchers.Main) {
                                            autonomousRunning = false
                                            autonomousJob = null
                                        }
                                    }
                                }
                            }
                        },
""" + callback_anchor
source = replace_once(source, callback_anchor, callback_new, "autonomous scanner callback")

signature_anchor = "    onRunCouncil: (String, Double, String) -> Unit,\n    onRoleMenu: (Boolean) -> Unit,"
signature_new = """    onRunCouncil: (String, Double, String) -> Unit,
    autonomousMarketScope: String,
    onAutonomousMarketScopeChange: (String) -> Unit,
    twelveDataApiKey: String,
    onTwelveDataApiKeyChange: (String) -> Unit,
    autonomousRunning: Boolean,
    autonomousStatus: String,
    autonomousQuotes: List<com.localtrading.council.ai.LiveMarketQuote>,
    autonomousReportText: String,
    onToggleAutonomous: (String, String) -> Unit,
    onRoleMenu: (Boolean) -> Unit,"""
source = replace_once(source, signature_anchor, signature_new, "ModelScreen autonomous parameters")

realtime_section = """        item {
            SectionTitle("Tự tìm cơ hội thị trường · REALTIME", "Tự khám phá thị trường, cập nhật giá trực tiếp và chạy hội đồng AI theo chu kỳ")
            Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = autonomousMarketScope,
                        onValueChange = onAutonomousMarketScopeChange,
                        label = { Text("Phạm vi thị trường") },
                        placeholder = { Text("AUTO hoặc BTCUSDT hoặc XAU/USD,EUR/USD") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !autonomousRunning
                    )
                    OutlinedTextField(
                        value = twelveDataApiKey,
                        onValueChange = onTwelveDataApiKeyChange,
                        label = { Text("Twelve Data API key (FX/vàng/cổ phiếu)") },
                        placeholder = { Text("Để trống nếu chỉ quét crypto công khai") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !autonomousRunning
                    )
                    Text(
                        "AUTO: không cần tệp dữ liệu; app tự khám phá các cặp Binance spot USDT theo thanh khoản/biến động. " +
                            "Nhập mã để giới hạn phạm vi. Forex, vàng và cổ phiếu dùng Twelve Data API key; key chỉ nằm trong bộ nhớ phiên app, không ghi vào nhật ký.",
                        color = SoftText, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Button(
                        onClick = { onToggleAutonomous(autonomousMarketScope.trim(), twelveDataApiKey.trim()) },
                        enabled = autonomousRunning || autonomousMarketScope.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (autonomousRunning) "Dừng quét tự động" else "Bắt đầu tìm kiếm + thảo luận AI")
                    }
                    Text(autonomousStatus, color = if (autonomousRunning) Mint else SoftText, fontSize = 11.sp, lineHeight = 16.sp)
                    if (autonomousQuotes.isNotEmpty()) {
                        SectionTitle("Giá realtime", "Dữ liệu được đẩy trực tiếp từ nhà cung cấp")
                        autonomousQuotes.forEach { quote ->
                            Text(
                                quote.displayLine(),
                                color = if (quote.isStale) Amber else Color.White,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    if (autonomousReportText.isNotBlank()) {
                        Divider(color = Border)
                        Text("Biên bản hội đồng gần nhất", color = Mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(autonomousReportText, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Text(
                        "AI quét dữ liệu realtime nhưng chỉ đánh giá trên nến M15 đã đóng; mục tiêu chu kỳ 15 phút. " +
                            "Mô hình tự đề xuất và phản biện chiến lược, lưu nhật ký nghiên cứu trong bộ nhớ riêng. " +
                            "Chưa có lệnh tiền thật; đừng xem kết quả AI là bảo đảm lợi nhuận.",
                        color = SoftText, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
        }
"""
section_anchor = '        item {\n            SectionTitle("Hội đồng AI tìm cơ hội thị trường",'
if source.count(section_anchor) != 1:
    raise SystemExit("Could not uniquely find council section to insert realtime UI")
source = source.replace(section_anchor, realtime_section + section_anchor, 1)

activity_path.write_text(source)
print("Integrated realtime market streams, AUTO discovery, Twelve Data market routing and the autonomous scanner UI.")
