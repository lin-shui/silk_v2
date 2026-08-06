@file:Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")

package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val ROOM_FILE_INPUT_ID = "silk-room-file-upload-input"
private const val ROOM_FOLDER_INPUT_ID = "silk-room-folder-upload-input"

private val roomToolsGetUserMedia = js("(function() { return navigator.mediaDevices.getUserMedia({audio: true}); })")
private val roomToolsNewArray = js("(function() { return []; })")
private val roomToolsCreateRecorder = js("(function(stream) { var opts = {mimeType: 'audio/webm;codecs=opus'}; try { return new MediaRecorder(stream, opts); } catch(e) { return new MediaRecorder(stream); } })")
private val roomToolsCreateBlob = js("(function(chunks) { return new Blob(chunks, {type: 'audio/webm'}); })")
private val roomToolsBlobToArrayBuffer = js("(function(blob) { return blob.arrayBuffer(); })")
private val roomToolsArrayBufferToBase64 = js("(function(ab) { var u8 = new Uint8Array(ab); var b = ''; for (var i = 0; i < u8.length; i++) b += String.fromCharCode(u8[i]); return btoa(b); })")
private val roomToolsStopTracks = js("(function(stream) { if (stream && stream.getTracks) { stream.getTracks().forEach(function(t) { t.stop(); }); } })")

@Composable
internal fun ConversationRoomComposerTools(
    roomId: String,
    userId: String,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onScreenshotCaptured: (blob: dynamic, objectUrl: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember(roomId) { mutableStateOf(false) }
    var isCapturingScreen by remember(roomId) { mutableStateOf(false) }
    var isVoiceRecording by remember(roomId) { mutableStateOf(false) }
    var isTranscribing by remember(roomId) { mutableStateOf(false) }
    var mediaRecorder by remember(roomId) { mutableStateOf<dynamic>(null) }

    ConversationComposerToolsRow {
        ConversationComposerToolButton(
            label = "上传整个目录",
            content = if (isUploading) "⏳" else "📁",
            enabled = !isUploading,
        ) {
            (document.getElementById(ROOM_FOLDER_INPUT_ID) as? org.w3c.dom.HTMLElement)?.click()
        }
        ConversationComposerToolButton(
            label = "上传单个文件",
            content = if (isUploading) "⏳" else "📎",
            enabled = !isUploading,
        ) {
            (document.getElementById(ROOM_FILE_INPUT_ID) as? org.w3c.dom.HTMLElement)?.click()
        }
        ConversationComposerToolButton(
            label = "截取屏幕区域",
            content = if (isCapturingScreen) "…" else "📷",
            enabled = !isUploading && !isCapturingScreen,
        ) {
            isCapturingScreen = true
            window.asDynamic().__screenshotDone = {
                val blob = js("window.__pendingScreenshotBlob")
                if (blob != null) {
                    val objectUrl = js("window.URL.createObjectURL(blob)").toString()
                    onScreenshotCaptured(blob, objectUrl)
                    js("window.__pendingScreenshotBlob = null")
                }
                isCapturingScreen = false
            }
            val runtime = window.asDynamic()
            runtime.__silkScreenshotRoomId = roomId
            runtime.__silkScreenshotUserId = userId
            runtime.__silkScreenshotUploadUrl = "${backendHttpOrigin()}/api/files/upload"
            js("""
                (function() {
                    navigator.mediaDevices.getDisplayMedia().then(function(stream) {
                        var video = document.createElement('video');
                        video.srcObject = stream;
                        video.onloadedmetadata = function() {
                            video.play();
                            var canvas = document.createElement('canvas');
                            canvas.width = video.videoWidth;
                            canvas.height = video.videoHeight;
                            var ctx = canvas.getContext('2d');
                            ctx.drawImage(video, 0, 0);
                            stream.getTracks().forEach(function(track) { track.stop(); });
                            window.showCropOverlay(
                                canvas.toDataURL('image/png'),
                                window.__silkScreenshotRoomId,
                                window.__silkScreenshotUserId,
                                window.__silkScreenshotUploadUrl
                            );
                        };
                    }).catch(function() {
                        if (window.__screenshotDone) {
                            window.__screenshotDone();
                            window.__screenshotDone = undefined;
                        }
                    });
                })();
            """)
        }

        when {
            isTranscribing -> ConversationComposerToolButton(
                label = "正在识别语音",
                content = "识别中...",
                enabled = false,
            ) {}
            isVoiceRecording -> ConversationComposerToolButton(
                label = "停止录音并识别",
                content = "⏹ 停止",
                danger = true,
            ) {
                isVoiceRecording = false
                try {
                    mediaRecorder?.stop()
                } catch (error: dynamic) {
                    console.error("停止录音失败:", error)
                    isTranscribing = false
                }
            }
            else -> ConversationComposerToolButton(label = "语音输入", content = "🎤") {
                scope.launch {
                    try {
                        val stream = roomToolsGetUserMedia()
                            .unsafeCast<kotlin.js.Promise<dynamic>>()
                            .await()
                        val chunks = roomToolsNewArray()
                        val recorder = roomToolsCreateRecorder(stream)
                        recorder.ondataavailable = { event: dynamic ->
                            chunks.push(event.data)
                            Unit
                        }
                        recorder.onstop = {
                            isTranscribing = true
                            scope.launch {
                                try {
                                    val blob = roomToolsCreateBlob(chunks)
                                    val arrayBuffer = roomToolsBlobToArrayBuffer(blob)
                                        .unsafeCast<kotlin.js.Promise<dynamic>>()
                                        .await()
                                    val base64 = roomToolsArrayBufferToBase64(arrayBuffer) as String
                                    val result = ApiClient.transcribeAudio(base64, "webm")
                                    if (result.success && result.text.isNotBlank()) {
                                        val updated = if (messageText.isNotBlank()) {
                                            "$messageText ${result.text}"
                                        } else {
                                            result.text
                                        }
                                        onMessageTextChange(updated)
                                    }
                                } catch (error: Throwable) {
                                    console.error("语音识别失败:", error)
                                } finally {
                                    isTranscribing = false
                                    try {
                                        roomToolsStopTracks(stream)
                                    } catch (_: dynamic) {
                                    }
                                }
                            }
                            Unit
                        }
                        mediaRecorder = recorder
                        recorder.start()
                        isVoiceRecording = true
                    } catch (error: dynamic) {
                        console.error("无法启动录音:", error)
                    }
                }
            }
        }
    }

    Input(InputType.File) {
        id(ROOM_FILE_INPUT_ID)
        style { display(DisplayStyle.None) }
        attr("accept", "*/*")
        onChange {
            isUploading = true
            installRoomUploadRuntime(roomId, userId) { isUploading = false }
            js("""
                (function() {
                    var input = document.getElementById('silk-room-file-upload-input');
                    if (!input || !input.files || input.files.length === 0) {
                        window.__silkRoomUploadDone();
                        return;
                    }
                    var file = input.files[0];
                    var formData = new FormData();
                    formData.append('sessionId', window.__silkRoomUploadRoomId);
                    formData.append('userId', window.__silkRoomUploadUserId);
                    formData.append('file', file);
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', window.__silkRoomUploadUrl, true);
                    xhr.onload = function() {
                        if (xhr.status === 200) {
                            var response = JSON.parse(xhr.responseText);
                            window.alert('文件上传成功: ' + response.fileName);
                        } else {
                            window.alert('文件上传失败: ' + xhr.statusText);
                        }
                        input.value = '';
                        window.__silkRoomUploadDone();
                    };
                    xhr.onerror = function() {
                        input.value = '';
                        window.alert('文件上传失败，请检查网络连接');
                        window.__silkRoomUploadDone();
                    };
                    xhr.send(formData);
                })();
            """)
        }
    }

    Input(InputType.File) {
        id(ROOM_FOLDER_INPUT_ID)
        style { display(DisplayStyle.None) }
        attr("webkitdirectory", "true")
        attr("directory", "true")
        attr("multiple", "true")
        onChange {
            isUploading = true
            installRoomUploadRuntime(roomId, userId) { isUploading = false }
            js("""
                (function() {
                    var input = document.getElementById('silk-room-folder-upload-input');
                    if (!input || !input.files || input.files.length === 0) {
                        window.__silkRoomUploadDone();
                        return;
                    }
                    var supportedExtensions = [
                        '.txt', '.md', '.markdown', '.json', '.xml', '.html', '.htm', '.css',
                        '.yaml', '.yml', '.csv', '.log', '.ini', '.conf', '.cfg',
                        '.js', '.ts', '.jsx', '.tsx', '.kt', '.kts', '.java', '.py', '.pyw',
                        '.c', '.cpp', '.cc', '.h', '.hpp', '.cs', '.go', '.rs', '.rb',
                        '.php', '.swift', '.scala', '.groovy', '.lua', '.r', '.m', '.mm',
                        '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd', '.sql', '.graphql',
                        '.proto', '.pdf'
                    ];
                    var filesToUpload = [];
                    for (var index = 0; index < input.files.length; index++) {
                        var file = input.files[index];
                        var extension = '.' + file.name.split('.').pop().toLowerCase();
                        if (supportedExtensions.indexOf(extension) !== -1) filesToUpload.push(file);
                    }
                    if (filesToUpload.length === 0) {
                        input.value = '';
                        window.alert('所选目录中没有支持的文件类型');
                        window.__silkRoomUploadDone();
                        return;
                    }
                    var uploaded = 0;
                    var failed = 0;
                    window.alert('准备上传 ' + filesToUpload.length + ' 个文件...');
                    function uploadNext(index) {
                        if (index >= filesToUpload.length) {
                            input.value = '';
                            window.alert('上传完成！成功: ' + uploaded + ', 失败: ' + failed);
                            window.__silkRoomUploadDone();
                            return;
                        }
                        var file = filesToUpload[index];
                        var formData = new FormData();
                        formData.append('sessionId', window.__silkRoomUploadRoomId);
                        formData.append('userId', window.__silkRoomUploadUserId);
                        formData.append('file', file);
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', window.__silkRoomUploadUrl, true);
                        xhr.onload = function() {
                            if (xhr.status === 200) uploaded++; else failed++;
                            uploadNext(index + 1);
                        };
                        xhr.onerror = function() {
                            failed++;
                            uploadNext(index + 1);
                        };
                        xhr.send(formData);
                    }
                    uploadNext(0);
                })();
            """)
        }
    }
}

@Composable
internal fun ConversationComposerImagePreview(
    objectUrl: String,
    onRemove: () -> Unit,
) {
    ConversationComposerPreview {
        Div({ attr("class", "silk-conversation-composer-image-preview") }) {
            Img(src = objectUrl) {
                attr("class", "silk-conversation-composer-image-preview-thumbnail")
                attr("alt", "待发送截图")
            }
            Span({ attr("class", "silk-conversation-composer-image-preview-label") }) {
                Text("与消息一起发送")
            }
            Button({
                attr("class", "silk-conversation-composer-image-preview-remove")
                attr("title", "移除截图")
                attr("aria-label", "移除截图")
                onClick { onRemove() }
            }) { Text("×") }
        }
    }
}

@Composable
private fun ConversationComposerToolButton(
    label: String,
    content: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val stateClass = if (danger) " is-danger" else ""
    Button({
        attr("class", "silk-conversation-composer-tool$stateClass")
        attr("title", label)
        attr("aria-label", label)
        if (!enabled) attr("disabled", "")
        onClick { if (enabled) onClick() }
    }) { Text(content) }
}

private fun installRoomUploadRuntime(roomId: String, userId: String, onDone: () -> Unit) {
    val runtime = window.asDynamic()
    runtime.__silkRoomUploadRoomId = roomId
    runtime.__silkRoomUploadUserId = userId
    runtime.__silkRoomUploadUrl = "${backendHttpOrigin()}/api/files/upload"
    runtime.__silkRoomUploadDone = onDone
}

internal fun uploadConversationImage(
    roomId: String,
    userId: String,
    imageBlob: dynamic,
    text: String,
) {
    val runtime = window.asDynamic()
    runtime.__silkComposerImageRoomId = roomId
    runtime.__silkComposerImageUserId = userId
    runtime.__silkComposerImageUploadUrl = "${backendHttpOrigin()}/api/files/upload"
    runtime.__silkComposerImageBlob = imageBlob
    runtime.__silkComposerImageText = text
    js("""
        (function() {
            var formData = new FormData();
            formData.append('sessionId', window.__silkComposerImageRoomId);
            formData.append('userId', window.__silkComposerImageUserId);
            formData.append('file', window.__silkComposerImageBlob, 'screenshot.png');
            formData.append('text', window.__silkComposerImageText || '');
            var xhr = new XMLHttpRequest();
            xhr.open('POST', window.__silkComposerImageUploadUrl, true);
            xhr.onerror = function() { window.alert('图片上传失败，请检查网络连接'); };
            xhr.send(formData);
        })();
    """)
}
