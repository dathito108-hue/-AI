package com.localtrading.council.data

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Actual fine-tuned or pretrained GGUF weights for specialist tasks. */
enum class SpecialistModelId(
    val displayName: String,
    val description: String,
    val repository: String,
    val remoteFilename: String,
    val localFilename: String,
    val approximateBytes: Long,
    val sizeLabel: String
) {
    FINANCE_SENTIMENT(
        "FinSenti-Qwen3.5-0.8B",
        "Phân tích cảm xúc tin tức/báo cáo tài chính; mô hình chuyên biệt này chủ yếu dùng tiếng Anh.",
        "Ayansk11/FinSenti-Qwen3.5-0.8B-GGUF",
        "FinSenti-Qwen3.5-0.8B.Q4_K_M.gguf",
        "finsenti-qwen3.5-0.8b-q4_k_m.gguf",
        529_000_000L,
        "~529 MB"
    ),
    MATH_REASONING(
        "Qwen2-Math-1.5B-Instruct",
        "Mô hình toán học để kiểm tra phép tính, tỷ lệ rủi ro và kịch bản định lượng.",
        "tensorblock/Qwen2-Math-1.5B-Instruct-GGUF",
        "Qwen2-Math-1.5B-Instruct-Q4_K_M.gguf",
        "qwen2-math-1.5b-instruct-q4_k_m.gguf",
        918_000_000L,
        "~918 MB"
    ),
    CODE_STRATEGY(
        "Qwen2.5-Coder-1.5B-Instruct",
        "Mô hình lập trình hỗ trợ tạo/đọc mã EA và kiểm thử chiến lược.",
        "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
        "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        1_120_000_000L,
        "~1.12 GB"
    );

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$remoteFilename"
}

object SpecialistModelRepository {
    private const val DIRECTORY = "specialist-models"
    private const val EXTRA_FREE_BYTES = 192L * 1024L * 1024L

    private data class RemoteMetadata(val sha256: String, val sizeBytes: Long)

    fun installedFile(context: Context, model: SpecialistModelId): File? {
        val file = File(File(context.filesDir, DIRECTORY), model.localFilename)
        return file.takeIf { it.isFile && it.length() >= model.approximateBytes * 8L / 10L }
    }

    suspend fun download(
        context: Context,
        model: SpecialistModelId,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        installedFile(context, model)?.let { return@withContext it }

        val metadata = fetchMetadata(model.downloadUrl)
        require(metadata.sizeBytes >= model.approximateBytes * 8L / 10L) {
            "Kích thước nguồn không hợp lệ cho ${model.displayName}."
        }
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val partial = File(directory, model.localFilename + ".part")
        val destination = File(directory, model.localFilename)
        var existingBytes = partial.length()
        if (existingBytes >= metadata.sizeBytes) {
            partial.delete()
            existingBytes = 0L
        }

        val storage = StatFs(directory.absolutePath)
        val requiredFree = (metadata.sizeBytes - existingBytes).coerceAtLeast(0L) + EXTRA_FREE_BYTES
        require(storage.availableBytes >= requiredFree) {
            "Không đủ dung lượng trống. Cần khoảng ${requiredFree / (1024L * 1024L)} MiB để cài ${model.displayName}."
        }

        var connection: HttpURLConnection? = null
        try {
            val request = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MultiAI-Trading-Council-Android/0.3")
                setRequestProperty("Accept-Encoding", "identity")
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            connection = request
            val status = request.responseCode
            val append = existingBytes > 0L && status == HttpURLConnection.HTTP_PARTIAL
            require(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                "Máy chủ tải mô hình trả về HTTP $status."
            }
            if (!append) {
                existingBytes = 0L
                partial.delete()
            }

            val totalFromRange = request.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val bodyLength = request.contentLengthLong
            val totalBytes = totalFromRange ?: if (bodyLength > 0L) bodyLength + existingBytes else metadata.sizeBytes
            require(totalBytes == metadata.sizeBytes) {
                partial.delete()
                "Kích thước tệp thay đổi trong lúc tải; đã hủy để tránh cài sai mô hình."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            if (append && partial.isFile) {
                BufferedInputStream(FileInputStream(partial)).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }

            var downloaded = existingBytes
            var lastPercent = -1
            onProgress(((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99))
            BufferedInputStream(request.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partial, append)).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            }

            require(downloaded == metadata.sizeBytes && partial.length() == metadata.sizeBytes) {
                "Tệp tải về chưa đầy đủ; có thể tiếp tục khi mạng ổn định."
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualSha.equals(metadata.sha256, ignoreCase = true)) {
                partial.delete()
                error("SHA-256 không khớp metadata nguồn. Đã xóa tệp lỗi.")
            }
            if (destination.exists()) require(destination.delete()) { "Không thể thay thế mô hình cũ." }
            require(partial.renameTo(destination)) { "Không thể lưu mô hình đã xác minh vào thư viện." }
            onProgress(100)
            destination
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchMetadata(url: String): RemoteMetadata {
        probe(url, followRedirects = false)?.let { return it }
        probe(url, followRedirects = true)?.let { return it }
        error("Nguồn không cung cấp SHA-256/kích thước hợp lệ qua HTTP metadata; chưa tải mô hình.")
    }

    private fun probe(url: String, followRedirects: Boolean): RemoteMetadata? {
        var connection: HttpURLConnection? = null
        return try {
            val request = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = followRedirects
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "MultiAI-Trading-Council-Android/0.3")
                setRequestProperty("Accept-Encoding", "identity")
            }
            connection = request
            val status = request.responseCode
            if (status !in 200..399) return null
            val rawSha = request.getHeaderField("X-Linked-Etag") ?: request.getHeaderField("ETag")
            val sha = normalizeSha(rawSha) ?: return null
            val size = request.getHeaderField("X-Linked-Size")?.toLongOrNull()
                ?: request.getHeaderField("Content-Length")?.toLongOrNull()
                ?: return null
            if (size > 0L) RemoteMetadata(sha, size) else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeSha(value: String?): String? {
        val normalized = value?.trim()?.trim('"')?.lowercase(Locale.ROOT)?.removePrefix("sha256:") ?: return null
        return normalized.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}
