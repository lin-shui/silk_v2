/**
 * AudioDuplexScene - 音频全双工 Web Tab 页面
 * 使用 Web Audio API + WebSocket 实现 MiniCPM-o 全双工语音对话
 */
package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.margin
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginRight
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.opacity
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.paddingBottom
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

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

@Suppress("CyclomaticComplexMethod", "UnusedParameter", "UnusedPrivateProperty")
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
                val additions = parseTranscriptItems(state)
                if (additions.isNotEmpty()) {
                    transcript = transcript + additions
                }
            }
            delay(100)
        }
    }

    Div({
        attr("data-user-id", appState.currentUser?.id.orEmpty())
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.vh)
            backgroundColor(Color(SilkColors.background))
        }
    }) {
        AudioTranscriptPane(transcript)
        AudioStatusText(statusText)
        AudioCallButton(statusText = statusText) {
            scope.launch {
                val state: dynamic = js("window.__ad_getState()")
                val currentState: String = state?.state as? String ?: "IDLE"
                if (currentState == "STREAMING") {
                    js("window.__ad_stop()")
                } else {
                    val wsBaseUrl = backendWsOrigin()
                    window.asDynamic().__ad_start(wsBaseUrl)
                }
            }
        }
    }
}
