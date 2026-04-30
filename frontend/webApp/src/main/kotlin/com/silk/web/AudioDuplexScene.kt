/**
 * AudioDuplexScene - 音频全双工 Web Tab 页面
 * 使用 Web Audio API + WebSocket 实现 MiniCPM-o 全双工语音对话
 */
package com.silk.web

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 对话记录条目 */
private data class TranscriptItem(
    val role: String,
    val text: String,
    val timestamp: Long
)

/** 初始化音频双工 JS session（一次性） */
private val jsInitSession = js("""
(function() {
    if (window.__ad_session) return; // 避免重复初始化
    var s = {
        ws: null,
        captureCtx: null,
        captureStream: null,
        playCtx: null,
        isRunning: false,
        sessionId: '',
        pcmBuffer: [],
        statusText: '点击开始语音对话',
        state: 'IDLE',
        transcriptQueue: []
    };

    function setStatus(text, st) {
        s.statusText = text;
        s.state = st;
    }

    function playAudio(b64) {
        try {
            if (!s.playCtx) {
                s.playCtx = new (window.AudioContext || window.webkitAudioContext)({sampleRate: 24000});
            }
            if (s.playCtx.state === 'suspended') s.playCtx.resume();
            var bin = atob(b64);
            var u8 = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
            var f32 = new Float32Array(u8.buffer);
            var ab = s.playCtx.createBuffer(1, f32.length, 24000);
            ab.getChannelData(0).set(f32);
            var src = s.playCtx.createBufferSource();
            src.buffer = ab;
            src.connect(s.playCtx.destination);
            src.start();
        } catch(e) { console.error('playAudio error:', e); }
    }

    window.__ad_start = function(wsBaseUrl) {
        if (s.isRunning) return;
        s.isRunning = true;
        s.pcmBuffer = [];
        s.transcriptQueue = [];
        s.sessionId = 'adx_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6);

        setStatus('连接中…', 'CONNECTING');
        s.ws = new WebSocket(wsBaseUrl + '/ws/audio-duplex?sessionId=' + s.sessionId);

        s.ws.onopen = function() {
            setStatus('准备中…', 'PREPARING');
            try {
                s.ws.send(JSON.stringify({
                    type: 'prepare',
                    system_prompt: '你是一个智能语音助手，请用中文回答用户问题。保持自然、友好的对话风格。'
                }));
            } catch(e) { console.error('send prepare error:', e); }
        };

        var accumulatedText = '';
        s.ws.onmessage = function(event) {
            try {
                var msg = JSON.parse(event.data);
                switch (msg.type) {
                    case 'queued':
                        setStatus('排队等待中…', 'QUEUED');
                        break;
                    case 'prepared':
                        setStatus('对话中，点击结束', 'STREAMING');
                        startCapture();
                        break;
                    case 'result':
                        if (msg.text) accumulatedText += msg.text;
                        if (msg.is_listen && accumulatedText.length > 0) {
                            s.transcriptQueue.push({role: 'ai', text: accumulatedText, timestamp: Date.now()});
                            accumulatedText = '';
                        }
                        if (msg.audio_data) playAudio(msg.audio_data);
                        break;
                    case 'audio_only':
                        if (msg.audio_data) playAudio(msg.audio_data);
                        break;
                    case 'error':
                        setStatus('连接异常', 'ERROR');
                        break;
                    case 'stopped':
                        cleanup();
                        break;
                }
            } catch(e) { console.error('parse msg error:', e); }
        };

        s.ws.onclose = function() {
            if (accumulatedText.length > 0) {
                s.transcriptQueue.push({role: 'ai', text: accumulatedText, timestamp: Date.now()});
            }
            setStatus('已断开', 'DISCONNECTED');
            cleanup();
        };

        s.ws.onerror = function() {
            setStatus('连接异常', 'ERROR');
        };
    };

    function startCapture() {
        try {
            navigator.mediaDevices.getUserMedia({audio: true}).then(function(stream) {
                s.captureStream = stream;
                s.captureCtx = new (window.AudioContext || window.webkitAudioContext)({sampleRate: 16000});
                var source = s.captureCtx.createMediaStreamSource(stream);
                var processor = s.captureCtx.createScriptProcessor(4096, 1, 1);

                processor.onaudioprocess = function(e) {
                    if (!s.isRunning || !s.ws || s.ws.readyState !== WebSocket.OPEN) return;
                    var data = e.inputBuffer.getChannelData(0);
                    for (var i = 0; i < data.length; i++) {
                        s.pcmBuffer.push(data[i]);
                        if (s.pcmBuffer.length >= 16000) {
                            var chunk = s.pcmBuffer.splice(0, 16000);
                            var f32 = new Float32Array(chunk);
                            var u8 = new Uint8Array(f32.buffer);
                            var b = '';
                            for (var j = 0; j < u8.length; j++) b += String.fromCharCode(u8[j]);
                            try {
                                s.ws.send(JSON.stringify({type: 'audio_chunk', audio_base64: btoa(b)}));
                            } catch(e) {}
                        }
                    }
                };

                source.connect(processor);
                processor.connect(s.captureCtx.destination);
            }).catch(function(e) {
                console.error('getUserMedia error:', e);
                setStatus('麦克风不可用', 'ERROR');
            });
        } catch(e) {
            console.error('startCapture error:', e);
            setStatus('麦克风不可用', 'ERROR');
        }
    }

    function cleanup() {
        s.isRunning = false;
        s.pcmBuffer = [];
        if (s.captureStream) {
            s.captureStream.getTracks().forEach(function(t) { t.stop(); });
            s.captureStream = null;
        }
        if (s.captureCtx) {
            s.captureCtx.close().catch(function(){});
            s.captureCtx = null;
        }
        if (s.ws) {
            try { s.ws.close(); } catch(e) {}
            s.ws = null;
        }
    }

    window.__ad_stop = function() {
        if (s.ws && s.ws.readyState === WebSocket.OPEN) {
            try { s.ws.send(JSON.stringify({type: 'stop'})); } catch(e) {}
        }
        cleanup();
        if (s.playCtx) {
            s.playCtx.close().catch(function(){});
            s.playCtx = null;
        }
        setStatus('点击开始语音对话', 'IDLE');
    };

    window.__ad_getState = function() {
        var items = [];
        while (s.transcriptQueue.length > 0) {
            items.push(s.transcriptQueue.shift());
        }
        return {
            statusText: s.statusText,
            state: s.state,
            newTranscripts: items
        };
    };

    window.__ad_session = s;
})();
""")

@Composable
fun AudioDuplexScene(appState: WebAppState) {
    var statusText by remember { mutableStateOf("点击开始语音对话") }
    var transcript by remember { mutableStateOf<List<TranscriptItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // 初始化 JS session（确保只初始化一次）
    remember { jsInitSession; Unit }

    // 轮询 JS 状态更新
    LaunchedEffect(Unit) {
        while (true) {
            val state: dynamic = js("window.__ad_getState()")
            if (state != null) {
                statusText = state.statusText as String
                val newItems: Array<dynamic> = state.newTranscripts as Array<dynamic>
                if (newItems.isNotEmpty()) {
                    val additions = newItems.map { item ->
                        TranscriptItem(
                            role = item.role as String,
                            text = item.text as String,
                            timestamp = item.timestamp as Long
                        )
                    }
                    transcript = transcript + additions
                }
            }
            delay(100)
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.vh)
            backgroundColor(Color(SilkColors.background))
        }
    }) {
        // 对话记录区域
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                width(100.percent)
                padding(16.px, 16.px, 8.px, 16.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
            }
        }) {
            if (transcript.isEmpty()) {
                Div({
                    style {
                        property("flex", "1")
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        justifyContent(JustifyContent.Center)
                        alignItems(AlignItems.Center)
                    }
                }) {
                    Span({
                        style { fontSize(48.px); opacity(0.3) }
                    }) { Text("📞") }
                    Span({
                        style {
                            fontSize(14.px)
                            color(Color(SilkColors.textLight))
                            marginTop(8.px)
                        }
                    }) { Text("点击下方按钮开始语音对话") }
                }
            } else {
                transcript.forEach { item ->
                    Div({
                        style {
                            width(100.percent)
                            padding(12.px)
                            marginBottom(6.px)
                            backgroundColor(
                                if (item.role == "ai") Color(SilkColors.secondary)
                                else Color(SilkColors.surface)
                            )
                            borderRadius(12.px)
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.FlexStart)
                            property("word-break", "break-word")
                        }
                    }) {
                        Span({
                            style {
                                fontSize(20.px)
                                marginRight(8.px)
                                property("flex-shrink", "0")
                            }
                        }) { Text(if (item.role == "ai") "🤖" else "👑") }
                        Span({
                            style {
                                fontSize(14.px)
                                color(Color(SilkColors.textPrimary))
                            }
                        }) { Text(item.text) }
                    }
                }
            }
        }

        // 状态文字
        Span({
            style {
                fontSize(14.px)
                color(
                    when {
                        statusText.contains("对话中") -> Color("#FF4D4F")
                        statusText.contains("异常") || statusText.contains("不可用") -> Color("#FF4D4F")
                        else -> Color(SilkColors.textLight)
                    }
                )
                margin(0.px, 0.px, 12.px, 0.px)
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
            }
        }) { Text(statusText) }

        // 大圆形按钮
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                paddingBottom(40.px)
            }
        }) {
            Button({
                style {
                    width(100.px)
                    height(100.px)
                    borderRadius(50.px)
                    fontSize(48.px)
                    border(0.px)
                    property("cursor", "pointer")
                    property("transition", "all 0.2s ease")
                    property("box-shadow", "0 4px 16px rgba(0,0,0,0.15)")
                    backgroundColor(Color(
                        when {
                            statusText.contains("对话中") -> "#FF4D4F"
                            statusText.contains("连接") || statusText.contains("排队") || statusText.contains("准备") -> "#FAAD14"
                            statusText.contains("异常") || statusText.contains("断开") || statusText.contains("不可用") -> "#999999"
                            else -> SilkColors.primary
                        }
                    ))
                }
                onClick {
                    scope.launch {
                        val state: dynamic = js("window.__ad_getState()")
                        val currentState: String = state?.state as? String ?: "IDLE"
                        if (currentState == "STREAMING") {
                            js("window.__ad_stop()")
                        } else {
                            val wsBaseUrl = backendWsOrigin()
                            js("window.__ad_start(wsBaseUrl)")
                        }
                    }
                }
            }) {
                Text("📞")
            }
        }
    }
}
