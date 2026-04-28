package com.silk.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.silk.shared.models.TranscriptItem
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@Composable
fun AudioDuplexScreen(appState: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDuplexActive by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("点击开始语音对话") }
    var transcript by remember { mutableStateOf<List<TranscriptItem>>(emptyList()) }

    // HTTP client for WebSocket (lifetime of composable)
    val httpClient = remember {
        HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            install(WebSockets)
        }
    }

    var sessionJob by remember { mutableStateOf<Job?>(null) }
    // Used to signal stop from the UI thread to the WebSocket coroutine
    val stopChannel = remember { Channel<Unit>(Channel.CONFLATED) }

    // Permission
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Will be triggered by re-composition when the button is clicked again
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            sessionJob?.cancel()
            httpClient.close()
        }
    }

    // Helper: convert float[] to base64 (16kHz PCM float32 LE)
    fun floatArrayToBase64(floats: FloatArray): String {
        val bb = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) bb.putFloat(f)
        return Base64.getEncoder().encodeToString(bb.array())
    }

    // Helper: decode base64 to float[] (24kHz PCM float32 LE)
    fun base64ToFloatArray(b64: String): FloatArray {
        val decoded = Base64.getDecoder().decode(b64)
        val fb = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val result = FloatArray(fb.remaining())
        fb.get(result)
        return result
    }

    fun toggleDuplex() {
        if (isDuplexActive) {
            statusText = "正在结束…"
            stopChannel.trySend(Unit)
            scope.launch {
                delay(3000)
                if (isDuplexActive) {
                    sessionJob?.cancel()
                    isDuplexActive = false
                    statusText = "点击开始语音对话"
                }
            }
        } else {
            if (!hasMicPermission) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }

            isDuplexActive = true
            statusText = "连接中…"
            transcript = emptyList()

            val sessionId = "adx_${System.currentTimeMillis().toString(36)}_" +
                    (1000..9999).random().toString(36)
            val baseUrl = BackendUrlHolder.getBaseUrl()
            val wsUrl = baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://") +
                    "/ws/audio-duplex?sessionId=$sessionId"

            sessionJob = scope.launch(Dispatchers.IO) {
                var audioRecord: AudioRecord? = null
                var audioTrack: AudioTrack? = null
                val accumulatedText = StringBuilder()

                try {
                    // Playback track: 24kHz mono float
                    val trackBufSize = AudioTrack.getMinBufferSize(
                        24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                    ) * 4
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setBufferSizeInBytes(trackBufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack.play()
                    withContext(Dispatchers.Main) { statusText = "准备中…" }

                    // Playback queue (ensure ordered playback)
                    val playChannel = Channel<FloatArray>(Channel.BUFFERED)

                    httpClient.webSocket(urlString = wsUrl) {
                        // Monitor stop signal
                        launch {
                            stopChannel.receive()
                            try { send(Frame.Text("""{"type":"stop"}""")) } catch (_: Exception) {}
                        }

                        // Playback consumer
                        launch {
                            for (array in playChannel) {
                                try {
                                    audioTrack.write(array, 0, array.size, AudioTrack.WRITE_BLOCKING)
                                } catch (_: Exception) { break }
                            }
                        }

                        // Prepare
                        send(Frame.Text("""{"type":"prepare","system_prompt":"你是一个智能语音助手，请用中文回答用户问题。保持自然、友好的对话风格。"}"""))

                        var captureJob: Job? = null

                        // Receive loop
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val json = try {
                                Json.parseToJsonElement(text).jsonObject
                            } catch (_: Exception) { continue }
                            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: continue

                            when (type) {
                                "queued" -> {
                                    withContext(Dispatchers.Main) { statusText = "排队等待中…" }
                                }
                                "prepared" -> {
                                    withContext(Dispatchers.Main) { statusText = "对话中，点击结束" }

                                    // Create capture recorder: 16kHz mono float
                                    val recBufSize = AudioRecord.getMinBufferSize(
                                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
                                    )
                                    audioRecord = AudioRecord.Builder()
                                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                                        .setAudioFormat(AudioFormat.Builder()
                                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                            .setSampleRate(16000)
                                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                            .build())
                                        .setBufferSizeInBytes(recBufSize)
                                        .build()
                                    audioRecord!!.startRecording()

                                    captureJob = launch {
                                        val readBuf = FloatArray(4096)
                                        val pending = mutableListOf<Float>()
                                        while (isActive) {
                                            val read = audioRecord!!.read(
                                                readBuf, 0, readBuf.size,
                                                AudioRecord.READ_NON_BLOCKING
                                            )
                                            if (read > 0) {
                                                for (i in 0 until read) {
                                                    pending.add(readBuf[i])
                                                    if (pending.size >= 16000) {
                                                        val chunk = pending.take(16000).toFloatArray()
                                                        pending.clear()
                                                        val b64 = floatArrayToBase64(chunk)
                                                        try {
                                                            send(Frame.Text("""{"type":"audio_chunk","audio_base64":"$b64"}"""))
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                            }
                                            delay(50)
                                        }
                                        // Send remaining
                                        if (pending.isNotEmpty()) {
                                            val b64 = floatArrayToBase64(pending.toFloatArray())
                                            try { send(Frame.Text("""{"type":"audio_chunk","audio_base64":"$b64"}""")) } catch (_: Exception) {}
                                        }
                                    }
                                }
                                "result" -> {
                                    val resultText = json["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val audioB64 = json["audio_data"]?.jsonPrimitive?.contentOrNull
                                    val isListen = json["is_listen"]?.jsonPrimitive?.booleanOrNull ?: false

                                    if (resultText.isNotEmpty()) accumulatedText.append(resultText)

                                    if (audioB64 != null) {
                                        try {
                                            val floats = base64ToFloatArray(audioB64)
                                            playChannel.trySend(floats)
                                        } catch (_: Exception) {}
                                    }

                                    if (isListen && accumulatedText.isNotEmpty()) {
                                        val item = TranscriptItem(
                                            "ai", accumulatedText.toString(),
                                            System.currentTimeMillis()
                                        )
                                        withContext(Dispatchers.Main) {
                                            transcript = transcript + item
                                        }
                                        accumulatedText.clear()
                                    }
                                }
                                "audio_only" -> {
                                    val audioB64 = json["audio_data"]?.jsonPrimitive?.contentOrNull
                                    if (audioB64 != null) {
                                        try {
                                            val floats = base64ToFloatArray(audioB64)
                                            playChannel.trySend(floats)
                                        } catch (_: Exception) {}
                                    }
                                }
                                "error" -> {
                                    withContext(Dispatchers.Main) { statusText = "连接异常" }
                                }
                                "stopped" -> {
                                    withContext(Dispatchers.Main) { statusText = "已断开" }
                                    break
                                }
                            }
                        }

                        captureJob?.cancel()
                    }
                } catch (e: CancellationException) {
                    // Normal cancellation
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (isDuplexActive) statusText = "连接异常"
                    }
                } finally {
                    audioRecord?.let {
                        try { it.stop() } catch (_: Exception) {}
                        try { it.release() } catch (_: Exception) {}
                    }
                    audioTrack?.let {
                        try { it.stop() } catch (_: Exception) {}
                        try { it.release() } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        isDuplexActive = false
                        if (statusText == "连接中…" || statusText == "准备中…" || statusText == "连接异常") {
                            statusText = "点击开始语音对话"
                        }
                    }
                }
            }
        }
    }

    // ── UI ──

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SilkColors.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Transcript area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📞", fontSize = 48.sp)
                    Text(
                        text = "点击下方按钮开始语音对话",
                        fontSize = 14.sp,
                        color = SilkColors.textLight,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(transcript) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (item.role == "ai") SilkColors.secondary
                                    else SilkColors.surface,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (item.role == "ai") "🤖" else "👑",
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = item.text,
                                    fontSize = 14.sp,
                                    color = SilkColors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Status text
        Text(
            text = statusText,
            fontSize = 14.sp,
            color = when {
                    statusText.contains("对话中") -> Color(0xFFFF4D4F)
                    statusText.contains("异常") || statusText.contains("不可用") -> Color(0xFFFF4D4F)
                    else -> SilkColors.textLight
                },
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Big circular button
        Button(
            onClick = { toggleDuplex() },
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                        statusText.contains("对话中") -> Color(0xFFFF4D4F)
                        statusText.contains("连接") || statusText.contains("排队") || statusText.contains("准备") -> Color(0xFFFAAD14)
                        statusText.contains("异常") || statusText.contains("断开") || statusText.contains("不可用") -> Color(0xFF999999)
                        else -> SilkColors.primary
                    }
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("📞", fontSize = 48.sp)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}
