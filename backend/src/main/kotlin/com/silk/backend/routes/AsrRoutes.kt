package com.silk.backend.routes

import com.silk.backend.ai.AIConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("AsrRoutes")

private val httpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 120_000
        endpoint {
            connectTimeout = 10_000
            socketTimeout = 120_000
        }
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun Route.asrRoutes() {
    route("/api/asr") {

        /**
         * POST /api/asr/transcribe
         *
         * Accepts JSON body: { "audio": "<base64>", "format": "wav" }
         * Forwards to vLLM ASR server and returns { "success": true, "text": "..." }
         */
        post("/transcribe") {
            try {
                val body = call.receiveText()
                val root = json.parseToJsonElement(body).jsonObject
                val audioBase64 = root["audio"]?.jsonPrimitive?.contentOrNull
                val format = root["format"]?.jsonPrimitive?.contentOrNull ?: "wav"

                if (audioBase64.isNullOrBlank()) {
                    call.respondText(
                        buildJsonObject { put("success", false); put("error", "Missing audio data") }.toString(),
                        ContentType.Application.Json, HttpStatusCode.BadRequest
                    )
                    return@post
                }

                val audioBytes: ByteArray
                try {
                    audioBytes = Base64.getDecoder().decode(audioBase64)
                } catch (e: IllegalArgumentException) {
                    call.respondText(
                        buildJsonObject { put("success", false); put("error", "Invalid base64 audio") }.toString(),
                        ContentType.Application.Json, HttpStatusCode.BadRequest
                    )
                    return@post
                }

                val asrUrl = AIConfig.ASR_VLLM_URL.trimEnd('/')
                val asrModel = AIConfig.ASR_MODEL
                val transcribeUrl = "$asrUrl/v1/audio/transcriptions"

                val normalizedFormat = format.lowercase().trim().removePrefix(".")
                var bytesToSend = audioBytes
                var fileExt = normalizedFormat
                var mime = contentTypeForFormat(normalizedFormat)

                if (AIConfig.ASR_TRANSCODE_TO_WAV && normalizedFormat != "wav") {
                    val wav = transcodeToWavWithFfmpeg(audioBytes, normalizedFormat)
                    if (wav == null) {
                        call.respondText(
                            buildJsonObject {
                                put("success", false)
                                put("error", "音频转码失败：请安装 ffmpeg 并确保可在 PATH 中执行，或设置 ASR_TRANSCODE_TO_WAV=false 并改用 WAV 输入")
                            }.toString(),
                            ContentType.Application.Json, HttpStatusCode.ServiceUnavailable
                        )
                        return@post
                    }
                    bytesToSend = wav
                    fileExt = "wav"
                    mime = "audio/wav"
                    logger.info("ASR transcoded to WAV: {} -> {} bytes", audioBytes.size, wav.size)
                }

                logger.info("ASR request: {} bytes, format={}, url={}", bytesToSend.size, fileExt, transcribeUrl)

                val response: HttpResponse = httpClient.submitFormWithBinaryData(
                    url = transcribeUrl,
                    formData = formData {
                        append("file", bytesToSend, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"audio.$fileExt\"")
                            append(HttpHeaders.ContentType, mime)
                        })
                        append("model", asrModel)
                    }
                )

                val responseBody = response.bodyAsText()
                logger.debug("ASR response status={}, body={}", response.status, responseBody)

                if (response.status != HttpStatusCode.OK) {
                    logger.warn("ASR server returned {}: {}", response.status, responseBody)
                    call.respondText(
                        buildJsonObject { put("success", false); put("error", "ASR service error: ${response.status}") }.toString(),
                        ContentType.Application.Json, HttpStatusCode.BadGateway
                    )
                    return@post
                }

                val result = json.parseToJsonElement(responseBody).jsonObject
                val text = result["text"]?.jsonPrimitive?.contentOrNull ?: ""

                call.respondText(
                    buildJsonObject { put("success", true); put("text", text) }.toString(),
                    ContentType.Application.Json
                )

            } catch (e: java.net.ConnectException) {
                logger.warn("Cannot connect to ASR server: {}", e.message)
                call.respondText(
                    buildJsonObject { put("success", false); put("error", "语音识别服务不可用，请确认 vLLM ASR 已启动") }.toString(),
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable
                )
            } catch (e: Exception) {
                logger.error("ASR transcribe failed", e)
                call.respondText(
                    buildJsonObject { put("success", false); put("error", "语音识别失败: ${e.message}") }.toString(),
                    ContentType.Application.Json, HttpStatusCode.InternalServerError
                )
            }
        }
    }
}

/**
 * 使用 ffmpeg 将任意常见录音格式转为 16kHz 单声道 PCM WAV，供 mlx_audio/miniaudio 解码。
 */
private fun transcodeToWavWithFfmpeg(input: ByteArray, format: String): ByteArray? {
    val ext = when (format.lowercase()) {
        "webm", "m4a", "mp3", "mp4", "ogg", "flac", "aac", "wav" -> format.lowercase()
        else -> format.lowercase().ifEmpty { "bin" }
    }
    val ffmpeg = AIConfig.ASR_FFMPEG_PATH
    var inPath: java.nio.file.Path? = null
    var outPath: java.nio.file.Path? = null
    return try {
        inPath = Files.createTempFile("silk_asr_in_", ".$ext")
        outPath = Files.createTempFile("silk_asr_out_", ".wav")
        Files.write(inPath, input)
        val pb = ProcessBuilder(
            ffmpeg,
            "-hide_banner",
            "-loglevel", "error",
            "-y",
            "-i", inPath.toAbsolutePath().toString(),
            "-f", "wav",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            outPath.toAbsolutePath().toString()
        ).redirectErrorStream(true)
        val process = pb.start()
        val stderr = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) {
            logger.warn("ffmpeg transcoding failed (exit={}): {}", if (finished) process.exitValue() else "timeout", stderr)
            return null
        }
        Files.readAllBytes(outPath)
    } catch (e: Exception) {
        logger.warn("ffmpeg transcoding error: {}", e.message)
        null
    } finally {
        try {
            inPath?.let { Files.deleteIfExists(it) }
        } catch (_: Exception) {
        }
        try {
            outPath?.let { Files.deleteIfExists(it) }
        } catch (_: Exception) {
        }
    }
}

private fun contentTypeForFormat(format: String): String = when (format.lowercase()) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "webm" -> "audio/webm"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}
