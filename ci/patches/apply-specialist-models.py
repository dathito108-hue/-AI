from pathlib import Path

root = Path("MultiAITradingAndroid/app/src/main/java/com/localtrading/council")
repository_source = Path("ci/patches/SpecialistModelRepository.kt")
repository_target = root / "data" / "SpecialistModelRepository.kt"
repository_target.parent.mkdir(parents=True, exist_ok=True)
repository_target.write_text(repository_source.read_text())

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
)
source = replace_once(source, state_anchor, state_new, "specialist state")

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
    onRoleMenu: (Boolean) -> Unit,"""
source = replace_once(source, signature_anchor, signature_new, "ModelScreen parameters")

specialist_section = """        item {
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
