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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.silk.shared.models.TranscriptItem
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

private const val DUPLEX_IDLE_STATUS = "点击开始语音对话"
private const val DUPLEX_PREPARE_MESSAGE =
    """{"type":"prepare","system_prompt":"你是一个智能语音助手，请用中文回答用户问题。保持自然、友好的对话风格。"}"""

@Composable
fun AudioDuplexScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDuplexActive by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(DUPLEX_IDLE_STATUS) }
    var transcript by remember { mutableStateOf<List<TranscriptItem>>(emptyList()) }
    var sessionJob by remember { mutableStateOf<Job?>(null) }

    val httpClient = remember {
        HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            install(WebSockets)
        }
    }
    val stopChannel = remember { Channel<Unit>(Channel.CONFLATED) }

    val hasMicPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(Unit) {
        onDispose {
            sessionJob?.cancel()
            httpClient.close()
        }
    }

    val onToggleDuplex: () -> Unit = {
        if (isDuplexActive) {
            stopDuplexSession(
                scope = scope,
                sessionJob = sessionJob,
                stopChannel = stopChannel,
                isDuplexActive = { isDuplexActive },
                onStatusChange = { statusText = it },
                onActiveChange = { isDuplexActive = it }
            )
        } else if (!hasMicPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startDuplexSession(
                scope = scope,
                httpClient = httpClient,
                stopChannel = stopChannel,
                onStatusChange = { statusText = it },
                onActiveChange = { isDuplexActive = it },
                onTranscriptReset = { transcript = emptyList() },
                onTranscriptAppend = { transcript = transcript + it },
                onSessionJobChange = { sessionJob = it },
                isDuplexActive = { isDuplexActive }
            )
        }
    }

    AudioDuplexContent(
        transcript = transcript,
        statusText = statusText,
        onToggleDuplex = onToggleDuplex
    )
}

private fun stopDuplexSession(
    scope: CoroutineScope,
    sessionJob: Job?,
    stopChannel: Channel<Unit>,
    isDuplexActive: () -> Boolean,
    onStatusChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit
) {
    onStatusChange("正在结束…")
    stopChannel.trySend(Unit)
    scope.launch {
        delay(3000)
        if (isDuplexActive()) {
            sessionJob?.cancel()
            onActiveChange(false)
            onStatusChange(DUPLEX_IDLE_STATUS)
        }
    }
}

private fun startDuplexSession(
    scope: CoroutineScope,
    httpClient: HttpClient,
    stopChannel: Channel<Unit>,
    onStatusChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    onTranscriptReset: () -> Unit,
    onTranscriptAppend: (TranscriptItem) -> Unit,
    onSessionJobChange: (Job?) -> Unit,
    isDuplexActive: () -> Boolean
) {
    stopChannel.tryReceive()
    onActiveChange(true)
    onStatusChange("连接中…")
    onTranscriptReset()

    val wsUrl = buildAudioDuplexWsUrl()
    val sessionJob = scope.launch(Dispatchers.IO) {
        runAudioDuplexSession(
            httpClient = httpClient,
            wsUrl = wsUrl,
            stopChannel = stopChannel,
            isDuplexActive = isDuplexActive,
            onStatusChange = onStatusChange,
            onTranscriptAppend = onTranscriptAppend,
            onActiveChange = onActiveChange
        )
    }
    onSessionJobChange(sessionJob)
}

private fun buildAudioDuplexWsUrl(): String {
    val sessionId = "adx_${System.currentTimeMillis().toString(36)}_${(1000..9999).random().toString(36)}"
    return BackendUrlHolder.getBaseUrl()
        .replace("http://", "ws://")
        .replace("https://", "wss://") +
        "/ws/audio-duplex?sessionId=$sessionId"
}

private suspend fun runAudioDuplexSession(
    httpClient: HttpClient,
    wsUrl: String,
    stopChannel: Channel<Unit>,
    isDuplexActive: () -> Boolean,
    onStatusChange: (String) -> Unit,
    onTranscriptAppend: (TranscriptItem) -> Unit,
    onActiveChange: (Boolean) -> Unit
) {
    var audioRecord: AudioRecord? = null
    var audioTrack: AudioTrack? = null
    val accumulatedText = StringBuilder()

    try {
        runCatching {
            val playbackTrack = createPlaybackTrack()
            audioTrack = playbackTrack
            withContext(Dispatchers.Main) { onStatusChange("准备中…") }

            val playChannel = Channel<FloatArray>(Channel.BUFFERED)
            httpClient.webSocket(urlString = wsUrl) {
                launchStopSignalForwarder(stopChannel)
                launchPlaybackConsumer(playChannel, playbackTrack)
                sendPrepareMessage()

                audioRecord = processDuplexFrames(
                    playChannel = playChannel,
                    accumulatedText = accumulatedText,
                    onStatusChange = onStatusChange,
                    onTranscriptAppend = onTranscriptAppend
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            withContext(Dispatchers.Main) {
                if (isDuplexActive()) {
                    onStatusChange("连接异常")
                }
            }
        }
    } finally {
        releaseAudioRecord(audioRecord)
        releaseAudioTrack(audioTrack)
        withContext(Dispatchers.Main) {
            onActiveChange(false)
            onStatusChange(DUPLEX_IDLE_STATUS)
        }
    }
}

private suspend fun DefaultClientWebSocketSession.processDuplexFrames(
    playChannel: Channel<FloatArray>,
    accumulatedText: StringBuilder,
    onStatusChange: (String) -> Unit,
    onTranscriptAppend: (TranscriptItem) -> Unit
): AudioRecord? {
    var audioRecord: AudioRecord? = null
    var captureJob: Job? = null

    for (frame in incoming) {
        val shouldStop = processIncomingDuplexFrame(
            frame = frame,
            playChannel = playChannel,
            accumulatedText = accumulatedText,
            onStatusChange = onStatusChange,
            onTranscriptAppend = onTranscriptAppend,
            onAudioRecordChange = { audioRecord = it },
            captureJob = { captureJob },
            onCaptureJobChange = { captureJob = it }
        )
        if (shouldStop) {
            captureJob?.cancel()
            return audioRecord
        }
    }

    captureJob?.cancel()
    return audioRecord
}

private suspend fun DefaultClientWebSocketSession.processIncomingDuplexFrame(
    frame: Frame,
    playChannel: Channel<FloatArray>,
    accumulatedText: StringBuilder,
    onStatusChange: (String) -> Unit,
    onTranscriptAppend: (TranscriptItem) -> Unit,
    onAudioRecordChange: (AudioRecord) -> Unit,
    captureJob: () -> Job?,
    onCaptureJobChange: (Job?) -> Unit
): Boolean {
    if (frame !is Frame.Text) {
        return false
    }
    val event = parseAudioDuplexEvent(frame.readText()) ?: return false

    return when (event.type) {
        "queued" -> {
            withContext(Dispatchers.Main) { onStatusChange("排队等待中…") }
            false
        }
        "prepared" -> {
            withContext(Dispatchers.Main) { onStatusChange("对话中，点击结束") }
            val captureRecorder = createCaptureRecorder()
            onAudioRecordChange(captureRecorder)
            captureRecorder.startRecording()
            captureJob()?.cancel()
            onCaptureJobChange(
                launchCaptureJob(captureRecorder) { payload ->
                    sendAudioChunk(payload)
                }
            )
            false
        }
        "result" -> {
            handleResultEvent(
                event = event,
                playChannel = playChannel,
                accumulatedText = accumulatedText,
                onTranscriptAppend = onTranscriptAppend
            )
            false
        }
        "audio_only" -> {
            enqueueAudioPayload(event.audioBase64, playChannel)
            false
        }
        "error" -> {
            withContext(Dispatchers.Main) { onStatusChange("连接异常") }
            false
        }
        "stopped" -> {
            withContext(Dispatchers.Main) { onStatusChange("已断开") }
            true
        }
        else -> false
    }
}

private fun DefaultClientWebSocketSession.launchStopSignalForwarder(
    stopChannel: Channel<Unit>
) = launch {
    stopChannel.receive()
    try {
        send(Frame.Text("""{"type":"stop"}"""))
    } catch (_: Exception) {
        // The server may have already closed the socket.
    }
}

private fun CoroutineScope.launchPlaybackConsumer(
    playChannel: Channel<FloatArray>,
    audioTrack: AudioTrack
) = launch {
    for (array in playChannel) {
        try {
            audioTrack.write(array, 0, array.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {
            break
        }
    }
}

private suspend fun DefaultClientWebSocketSession.sendPrepareMessage() {
    send(Frame.Text(DUPLEX_PREPARE_MESSAGE))
}

private fun parseAudioDuplexEvent(text: String): AudioDuplexEvent? {
    val json = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }

    val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return AudioDuplexEvent(
        type = type,
        text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        audioBase64 = json["audio_data"]?.jsonPrimitive?.contentOrNull,
        isListen = json["is_listen"]?.jsonPrimitive?.booleanOrNull ?: false
    )
}

private suspend fun handleResultEvent(
    event: AudioDuplexEvent,
    playChannel: Channel<FloatArray>,
    accumulatedText: StringBuilder,
    onTranscriptAppend: (TranscriptItem) -> Unit
) {
    if (event.text.isNotEmpty()) {
        accumulatedText.append(event.text)
    }
    enqueueAudioPayload(event.audioBase64, playChannel)
    if (event.isListen && accumulatedText.isNotEmpty()) {
        val item = TranscriptItem("ai", accumulatedText.toString(), System.currentTimeMillis())
        withContext(Dispatchers.Main) { onTranscriptAppend(item) }
        accumulatedText.clear()
    }
}

private fun enqueueAudioPayload(audioBase64: String?, playChannel: Channel<FloatArray>) {
    if (audioBase64 == null) {
        return
    }
    try {
        playChannel.trySend(base64ToFloatArray(audioBase64))
    } catch (_: Exception) {
        // Ignore a malformed chunk and keep the session alive.
    }
}

private fun DefaultClientWebSocketSession.launchCaptureJob(
    audioRecord: AudioRecord?,
    sendChunk: suspend DefaultClientWebSocketSession.(String) -> Unit
) = launch {
    if (audioRecord == null) {
        return@launch
    }

    val readBuffer = FloatArray(4096)
    val pendingSamples = mutableListOf<Float>()
    while (isActive) {
        val read = audioRecord.read(
            readBuffer,
            0,
            readBuffer.size,
            AudioRecord.READ_NON_BLOCKING
        )
        if (read > 0) {
            appendAudioSamples(
                readBuffer = readBuffer,
                read = read,
                pendingSamples = pendingSamples
            ) { chunk ->
                sendChunk(floatArrayToBase64(chunk))
            }
        }
        delay(50)
    }

    flushPendingSamples(pendingSamples) { chunk ->
        sendChunk(floatArrayToBase64(chunk))
    }
}

private suspend fun DefaultClientWebSocketSession.sendAudioChunk(audioBase64: String) {
    try {
        send(Frame.Text("""{"type":"audio_chunk","audio_base64":"$audioBase64"}"""))
    } catch (_: Exception) {
        // A disconnected socket will stop the session on the next receive loop tick.
    }
}

private suspend fun appendAudioSamples(
    readBuffer: FloatArray,
    read: Int,
    pendingSamples: MutableList<Float>,
    onChunkReady: suspend (FloatArray) -> Unit
) {
    repeat(read) { index ->
        pendingSamples.add(readBuffer[index])
        if (pendingSamples.size >= 16000) {
            val chunk = pendingSamples.take(16000).toFloatArray()
            pendingSamples.clear()
            onChunkReady(chunk)
        }
    }
}

private suspend fun flushPendingSamples(
    pendingSamples: MutableList<Float>,
    onChunkReady: suspend (FloatArray) -> Unit
) {
    if (pendingSamples.isNotEmpty()) {
        onChunkReady(pendingSamples.toFloatArray())
    }
}

private fun createPlaybackTrack(): AudioTrack {
    val bufferSize = AudioTrack.getMinBufferSize(
        24000,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT
    ) * 4
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.play() }
}

private fun createCaptureRecorder(): AudioRecord {
    val bufferSize = AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_FLOAT
    )
    return AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .build()
}

private fun releaseAudioRecord(audioRecord: AudioRecord?) {
    audioRecord?.let {
        try {
            it.stop()
        } catch (_: Exception) {
            // Already stopped or never started.
        }
        try {
            it.release()
        } catch (_: Exception) {
            // Ignore release failures on teardown.
        }
    }
}

private fun releaseAudioTrack(audioTrack: AudioTrack?) {
    audioTrack?.let {
        try {
            it.stop()
        } catch (_: Exception) {
            // Already stopped or never started.
        }
        try {
            it.release()
        } catch (_: Exception) {
            // Ignore release failures on teardown.
        }
    }
}

private fun floatArrayToBase64(floats: FloatArray): String {
    val byteBuffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (value in floats) {
        byteBuffer.putFloat(value)
    }
    return Base64.getEncoder().encodeToString(byteBuffer.array())
}

private fun base64ToFloatArray(base64: String): FloatArray {
    val decoded = Base64.getDecoder().decode(base64)
    val floatBuffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(floatBuffer.remaining()).also(floatBuffer::get)
}

@Composable
private fun AudioDuplexContent(
    transcript: List<TranscriptItem>,
    statusText: String,
    onToggleDuplex: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SilkColors.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AudioDuplexTranscriptPane(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transcript = transcript
        )
        Text(
            text = statusText,
            fontSize = 14.sp,
            color = audioDuplexStatusColor(statusText),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(
            onClick = onToggleDuplex,
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = audioDuplexButtonColor(statusText)
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("📞", fontSize = 48.sp)
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun AudioDuplexTranscriptPane(
    modifier: Modifier,
    transcript: List<TranscriptItem>
) {
    Box(modifier = modifier) {
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
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center
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
                    AudioDuplexTranscriptItem(item)
                }
            }
        }
    }
}

@Composable
private fun AudioDuplexTranscriptItem(item: TranscriptItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (item.role == "ai") SilkColors.secondary else SilkColors.surface,
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

private fun audioDuplexStatusColor(statusText: String): Color =
    when {
        statusText.contains("对话中") -> Color(0xFFFF4D4F)
        statusText.contains("异常") || statusText.contains("不可用") -> Color(0xFFFF4D4F)
        else -> SilkColors.textLight
    }

private fun audioDuplexButtonColor(statusText: String): Color =
    when {
        statusText.contains("对话中") -> Color(0xFFFF4D4F)
        statusText.contains("连接") || statusText.contains("排队") || statusText.contains("准备") -> Color(0xFFFAAD14)
        statusText.contains("异常") || statusText.contains("断开") || statusText.contains("不可用") -> Color(0xFF999999)
        else -> SilkColors.primary
    }

private data class AudioDuplexEvent(
    val type: String,
    val text: String,
    val audioBase64: String?,
    val isListen: Boolean
)
